package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.neoforge.client.OperationScreen;
import design.inherent.reform.neoforge.client.ReviewScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.client.UnsavedChangesScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Actual files, publication refusals and held acknowledgements through public APIs only. */
final class NativeCloseControls {
  private record Options(int count, String label) {}

  private enum Effect {
    NORMAL,
    REVOKE_WRITE,
    HOLD
  }

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 100));
  private static final FieldKey<String> LABEL = FieldKey.of("label", ValueTypes.text(128));
  private static final FieldPath COUNT_PATH = FieldPath.ROOT.field("count");
  private static final FieldPath LABEL_PATH = FieldPath.ROOT.field("label");
  private static final RecordType<Options> TYPE =
      RecordType.<Options>builder(v -> new Options(v.get(COUNT), v.get(LABEL)))
          .field(COUNT, Options::count)
          .field(LABEL, Options::label)
          .build();
  private static final JsonLimits JSON = new JsonLimits(8192, 16, 512, 256, 64, 1024);
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final boolean reopen, outcomes;
  private final long nativeThread = Thread.currentThread().threadId();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          task -> {
            var thread = new Thread(task, "reform-close-fixture");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicBoolean read = new AtomicBoolean(true), write = new AtomicBoolean(true);
  private final AtomicInteger confirmedSaves = new AtomicInteger();
  private final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
  private final JsonArray checks = new JsonArray();
  private final OwnerContext context;
  private final DocumentDefinition<Options> definition;
  private final JsonStore<Options> source;
  private final FileOwner<Options> owner;
  private final OwnerContext.Access access;
  private final CompletableFuture<FileOwner.Receipt<Options>> initialized;
  private volatile Effect effect = Effect.NORMAL;
  private SettingsScreen<Options> form;
  private DocumentActions<Options> actions;
  private CompletableFuture<EditorActor.Commit<Options>> recovery;
  private UnsavedChangesScreen decision;
  private ReviewScreen<?> review;
  private OperationScreen<EditorActor.Commit<Options>> outcome;
  private DraftRevision reviewed;
  private java.util.UUID operationId;
  private Button retired;
  private String original;
  private SavedFile originalFile, recoveryFile;
  private int stage;
  private boolean complete, closed;

  NativeCloseControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture)
      throws IOException {
    this(reopen, false, progress, capture);
  }

  NativeCloseControls(
      boolean reopen,
      boolean outcomes,
      Consumer<String> progress,
      BiConsumer<String, Runnable> capture)
      throws IOException {
    this.reopen = reopen;
    this.outcomes = outcomes;
    this.progress = progress;
    this.capture = capture;
    var type =
        ValueType.of(
            TYPE.codec(),
            TYPE.shape(),
            new SnapshotPolicy<Options>() {
              @Override
              public Options freeze(Options value, SnapshotContext context) {
                return value; // This record contains only an int and an immutable String.
              }

              @Override
              public boolean equivalent(Options first, Options second) {
                if (first.count() != second.count()) beforePublication();
                return first.equals(second);
              }
            });
    definition =
        new DocumentDefinition<>(
            DocumentId.parse(
                outcomes ? "reform_native_example:outcomes" : "reform_native_example:close"),
            1,
            type,
            () -> new Options(4, "before"));
    context =
        new OwnerContext(
            client::execute,
            worker,
            () -> {
              if (Thread.currentThread().threadId() != nativeThread)
                throw new AssertionError("Close fixture used another native thread");
            },
            OwnerContext.Limits.STANDARD);
    source =
        new JsonStore<>(
            definition,
            new OwnedFile(
                StorageBinding.newJson(
                    client.gameDirectory.toPath().resolve("config").toAbsolutePath(),
                    definition.id()),
                8192,
                1024),
            JsonStore.Format.NEW_ENVELOPE,
            JSON,
            DraftLimits.CONFIGURATION);
    owner = new FileOwner<>(context, source, 8);
    if (reopen) originalFile = savedFile();
    access = context.access(read::get, write::get);
    var session = owner.open(access);
    initialized = session.initialize().toCompletableFuture();
    initialized.whenComplete((ignored, failure) -> session.close());
    if (!reopen) {
      client.options.guiScale().set(2);
      GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 854, 480);
      client.resizeDisplay();
    }
  }

  private void beforePublication() {
    if (Thread.currentThread().threadId() == nativeThread)
      throw new AssertionError("Publication value comparison ran on the native thread");
    if (effect == Effect.REVOKE_WRITE) write.set(false);
    if (effect == Effect.HOLD) {
      reached.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS))
          throw new IllegalStateException("Held fixture publication timed out");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
  }

  private record SavedFile(String text, FileTime modified, String key) {}

  private SavedFile savedFile() throws IOException {
    var path = source.binding().path();
    var attributes = Files.readAttributes(path, BasicFileAttributes.class);
    if (attributes.fileKey() == null)
      throw new IllegalStateException("Fixture filesystem has no replacement identity");
    return new SavedFile(
        Files.readString(path), attributes.lastModifiedTime(), attributes.fileKey().toString());
  }

  private void check(String label, boolean value) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private void open() {
    var editor =
        new EditorActor<>(
            context, owner.open(access), definition, JSON, DraftLimits.CONFIGURATION, 16);
    actions = new DocumentActions<>(context, access, editor);
    editor.load();
    form =
        new SettingsScreen<>(
            parent,
            Component.literal("Close review fixture"),
            Component.literal("Disposable local file · actual saved and effective outcomes"),
            definition,
            actions);
    client.setScreen(form);
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && button(form, "Done").active;
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) form.editorView().snapshot().orElseThrow().draft().at(path)).raw();
  }

  private EditBox field(FieldPath path) {
    return (EditBox) form.fieldControl(path).orElseThrow();
  }

  private void press(Button button) {
    check("decision control has native focus", focus(client.screen, button));
    click(client.screen, button);
  }

  @SuppressWarnings("unchecked") // The current form owns this exact typed operation.
  private OperationScreen<EditorActor.Commit<Options>> outcome() {
    check("native operation view is public and typed", client.screen instanceof OperationScreen<?>);
    return (OperationScreen<EditorActor.Commit<Options>>) client.screen;
  }

  private boolean settled() {
    return outcome
            .observedOperation()
            .filter(value -> value.terminal() && value.result().isPresent())
            .isPresent()
        && button(form, "Done").active;
  }

  private CommitReceipt<Options> receipt() {
    return outcome.observedOperation().orElseThrow().result().orElseThrow().receipt().orElseThrow();
  }

  private boolean tickOutcomes() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!initialized.isDone()) return false;
        check(
            "actual outcome owner initialized",
            initialized.join().application() == CommitReceipt.Application.APPLIED);
        originalFile = savedFile();
        confirmedSaves.incrementAndGet();
        open();
        next("outcome owner ready");
      }
      case 1 -> {
        if (!ready()) return false;
        replace(form, field(COUNT_PATH), "-");
        next("invalid review draft entered");
      }
      case 2 -> {
        if (!ready() || form.editorView().validation().valid()) return false;
        press(button(form, "Review changes…"));
        review = (ReviewScreen<?>) client.screen;
        next("invalid submit review opened");
      }
      case 3 -> {
        if (review.reviewedInput().isEmpty()) return false;
        retired = button(review, "Save reviewed changes");
        check("invalid reviewed submission is disabled", !retired.active);
        retired.onPress();
        check(
            "invalid reviewed callback cannot write",
            client.screen == review && savedFile().equals(originalFile));
        review.onClose();
        replace(form, field(COUNT_PATH), "7");
        next("valid reviewed draft entered");
      }
      case 4 -> {
        if (!ready() || !raw(COUNT_PATH).equals("7")) return false;
        press(button(form, "Review changes…"));
        review = (ReviewScreen<?>) client.screen;
        next("identified valid review opened");
      }
      case 5 -> {
        if (review.reviewedInput().isEmpty()) return false;
        reviewed = review.reviewedInput().orElseThrow().target();
        retired = button(review, "Save reviewed changes");
        check(
            "valid review retains actual source and changes",
            retired.active
                && review.reviewedInput().orElseThrow().comparison().changes().size() == 1
                && review
                    .reviewedInput()
                    .orElseThrow()
                    .source()
                    .location()
                    .orElseThrow()
                    .equals(source.binding().path().toString()));
        check("identified submit control accepts native focus", focus(review, retired));
        actions.edit(EntryPoint.COMMAND, "label", "queued");
        next("another admitted edit changed the captured revision");
      }
      case 6 -> {
        if (!raw(LABEL_PATH).equals("queued") || button(review, "Save reviewed changes").active)
          return false;
        check(
            "a stale review cannot restore focus to its disabled submit control",
            review.getFocused() != button(review, "Save reviewed changes"));
        retired.onPress();
        check(
            "stale native review callback never submits a replacement draft",
            client.screen == review && savedFile().equals(originalFile));
        press(button(review, "Refresh view"));
        next("explicit review refresh requested");
      }
      case 7 -> {
        if (review.reviewedInput().isEmpty()
            || review.reviewedInput().orElseThrow().target().equals(reviewed)
            || !button(review, "Save reviewed changes").active) return false;
        geometry(review);
        capture.accept("form-submit-wide.png", () -> next("wide identified submission captured"));
      }
      case 8 -> {
        effect = Effect.REVOKE_WRITE;
        press(button(review, "Save reviewed changes"));
        outcome = outcome();
        next("actual reviewed save submitted with refused publication");
      }
      case 9 -> {
        if (!settled()) return false;
        check(
            "completed operation retains actual saved-but-refused effects",
            receipt().persistence() == CommitReceipt.Persistence.SAVED
                && receipt().application() == CommitReceipt.Application.REFUSED
                && !form.editorView().dirty()
                && owner.handle().view().value().orElseThrow().count() == 4
                && source
                    .read()
                    .candidate()
                    .orElseThrow()
                    .value()
                    .equals(new Options(7, "queued")));
        confirmedSaves.incrementAndGet();
        operationId = outcome.observedOperation().orElseThrow().id();
        recoveryFile = savedFile();
        check(
            "outcome narration distinguishes saved and unapplied effects",
            outcome
                .getNarrationMessage()
                .getString()
                .contains("Saved; application did not complete"));
        geometry(outcome);
        capture.accept("form-outcome-wide.png", () -> next("actual split outcome captured"));
      }
      case 10 -> {
        press(button(outcome, "Back"));
        check(
            "leaving feedback retains its editing lifetime",
            client.screen == form && actions.view().snapshot().isPresent());
        press(button(form, "Operation…"));
        outcome = outcome();
        check(
            "operation result reopens by exact retained identity",
            outcome.observedOperation().orElseThrow().id().equals(operationId));
        retired = button(outcome, "Apply saved values");
        read.set(false);
        next("result disclosure revoked");
      }
      case 11 -> {
        if (outcome.observedOperation().isPresent()) return false;
        check(
            "revoked outcome narration drops earlier receipt details",
            !outcome.getNarrationMessage().getString().contains("Saved;"));
        retired.onPress();
        check(
            "READ revocation hides result and rejects retired recovery control",
            client.screen == outcome
                && savedFile().equals(recoveryFile)
                && owner.handle().view().value().orElseThrow().count() == 4);
        read.set(true);
        write.set(true);
        effect = Effect.NORMAL;
        next("actual result access restored");
      }
      case 12 -> {
        if (outcome.observedOperation().isEmpty() || !button(outcome, "Apply saved values").active)
          return false;
        press(button(outcome, "Apply saved values"));
        outcome = outcome();
        next("native application-only recovery requested");
      }
      case 13 -> {
        if (!settled()) return false;
        check(
            "native recovery changes only application, not bytes mtime or replacement identity",
            receipt().persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                && receipt().application() == CommitReceipt.Application.APPLIED
                && !outcome.observedOperation().orElseThrow().id().equals(operationId)
                && owner.handle().view().value().orElseThrow().equals(new Options(7, "queued"))
                && savedFile().equals(recoveryFile));
        check(
            "successful application does not offer redundant recovery",
            !button(outcome, "Apply saved values").active);
        check(
            "recovery narration confirms application without inventing a write",
            outcome
                .getNarrationMessage()
                .getString()
                .contains("Values applied without attempting persistence."));
        press(button(outcome, "Back"));
        replace(form, field(COUNT_PATH), "8");
        next("held reviewed candidate entered");
      }
      case 14 -> {
        if (!ready() || !raw(COUNT_PATH).equals("8")) return false;
        press(button(form, "Review changes…"));
        review = (ReviewScreen<?>) client.screen;
        next("held submission review opened");
      }
      case 15 -> {
        if (review.reviewedInput().isEmpty() || !button(review, "Save reviewed changes").active)
          return false;
        effect = Effect.HOLD;
        press(button(review, "Save reviewed changes"));
        outcome = outcome();
        next("reviewed source replaced with publication held");
      }
      case 16 -> {
        if (reached.getCount() != 0) return false;
        check(
            "actual pending save reports unknown total without success",
            outcome.observedOperation().isPresent()
                && !outcome.observedOperation().orElseThrow().terminal()
                && outcome.observedOperation().orElseThrow().progress().total().isEmpty()
                && source.read().candidate().orElseThrow().value().count() == 8);
        operationId = outcome.observedOperation().orElseThrow().id();
        press(button(outcome, "Request cancellation"));
        next("cancellation requested after actual source replacement");
      }
      case 17 -> {
        if (!outcome.observedOperation().orElseThrow().cancellationRequested()) return false;
        check(
            "cancellation request remains nonterminal until entered work drains",
            !outcome.observedOperation().orElseThrow().terminal());
        retired = button(outcome, "Request cancellation");
        press(button(outcome, "Back"));
        check(
            "returning during drain leaves actual editing and operation alive",
            client.screen == form);
        replace(form, field(LABEL_PATH), "later");
        next("later native input entered during drain");
      }
      case 18 -> {
        check(
            "native input is retained while its worker admission waits",
            field(LABEL_PATH).getValue().equals("later"));
        release.countDown();
        next("entered publication released");
      }
      case 19 -> {
        if (!ready() || form.editorView().lastReceipt().isEmpty()) return false;
        check(
            "entered cancellation drains a successful receipt without losing later input",
            form.editorView().lastReceipt().orElseThrow().application()
                    == CommitReceipt.Application.APPLIED
                && form.editorView().dirty()
                && raw(LABEL_PATH).equals("later")
                && source
                    .read()
                    .candidate()
                    .orElseThrow()
                    .value()
                    .equals(new Options(8, "queued")));
        confirmedSaves.incrementAndGet();
        press(button(form, "Operation…"));
        outcome = outcome();
        check(
            "cancelled request and actual completed result remain separate",
            outcome.observedOperation().orElseThrow().id().equals(operationId)
                && outcome.observedOperation().orElseThrow().cancellationRequested()
                && receipt().persistence() == CommitReceipt.Persistence.SAVED
                && receipt().application() == CommitReceipt.Application.APPLIED
                && !button(outcome, "Request cancellation").active);
        retired.onPress();
        check("retired cancellation control is inert", client.screen == outcome);
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        next("native feedback compact reflow requested");
      }
      case 20 -> {
        if (outcome.width > 340 || outcome.height > 250) return false;
        geometry(outcome);
        check(
            "compact result retains actual identity and return focus",
            outcome.observedOperation().orElseThrow().id().equals(operationId)
                && outcome.getFocused() == button(outcome, "Back"));
        capture.accept("form-outcome-narrow.png", () -> next("compact actual result captured"));
      }
      case 21 -> {
        press(button(outcome, "Back"));
        check(
            "feedback return keeps later unsaved draft",
            raw(LABEL_PATH).equals("later") && form.editorView().dirty());
        press(button(form, "Revert"));
        next("later draft explicitly reverted");
      }
      case 22 -> {
        if (!ready() || form.editorView().dirty()) return false;
        replace(form, field(COUNT_PATH), "9");
        replace(form, field(LABEL_PATH), "before");
        next("final source candidate authored");
      }
      case 23 -> {
        if (!ready() || !raw(COUNT_PATH).equals("9") || !raw(LABEL_PATH).equals("before"))
          return false;
        effect = Effect.NORMAL;
        press(button(form, "Save and apply"));
        next("ordinary direct save remains available");
      }
      case 24 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "ordinary save preserves actual final source and effective value",
            source.read().candidate().orElseThrow().value().equals(new Options(9, "before"))
                && owner.handle().view().value().orElseThrow().equals(new Options(9, "before")));
        confirmedSaves.incrementAndGet();
        form.onClose();
        shutdown();
        next("outcome fixture context ended");
      }
      case 25 -> {
        if (!worker.isTerminated()) return false;
        check("outcome native context and worker actually close", closed && !context.isOpen());
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unexpected outcome stage " + stage);
    }
    return false;
  }

  boolean tick() throws Exception {
    if (outcomes && !reopen) return tickOutcomes();
    switch (stage) {
      case 0 -> {
        if (!initialized.isDone()) return false;
        var receipt = initialized.join();
        if (receipt.persistence() == CommitReceipt.Persistence.SAVED)
          confirmedSaves.incrementAndGet();
        if (reopen)
          check(
              "initialization leaves the existing file untouched",
              savedFile().equals(originalFile));
        else originalFile = savedFile();
        check(
            "actual startup initialization completed",
            receipt.application() == CommitReceipt.Application.APPLIED);
        original = Files.readString(source.binding().path());
        open();
        next("close fixture owner ready");
      }
      case 1 -> {
        if (!ready()) return false;
        if (reopen) {
          check(
              "saved close reopens actual source and runtime",
              raw(COUNT_PATH).equals("9")
                  && raw(LABEL_PATH).equals("before")
                  && owner.handle().view().value().orElseThrow().count() == 9
                  && !form.editorView().dirty()
                  && confirmedSaves.get() == 0);
          form.onClose();
          check(
              "clean reopen closes without a decision or write",
              client.screen == parent
                  && form.editorView().availability() == EditorActor.Availability.CLOSED
                  && savedFile().equals(originalFile));
          shutdown();
          stage = 16;
          return false;
        }
        check("only lifecycle initialization has written", confirmedSaves.get() == 1);
        replace(form, field(COUNT_PATH), "-");
        next("invalid close draft entered");
      }
      case 2 -> {
        if (!ready() || form.editorView().validation().valid()) return false;
        form.onClose();
        check(
            "dirty close opens native three-way decision",
            client.screen instanceof UnsavedChangesScreen);
        decision = (UnsavedChangesScreen) client.screen;
        retired = button(decision, "Save and close");
        check(
            "invalid save is disabled with narrated reason",
            !retired.active && !decision.getNarrationMessage().getString().isBlank());
        retired.onPress();
        check(
            "direct disabled callback performs no file effect",
            client.screen == decision
                && confirmedSaves.get() == 1
                && savedFile().equals(originalFile));
        geometry(decision);
        capture.accept("form-close-wide.png", () -> next("wide draft decision captured"));
      }
      case 3 -> {
        check(
            "initial close focus prefers retaining the draft",
            decision.getFocused() == button(decision, "Keep editing"));
        check(
            "explicit decision focus is native",
            focus(decision, button(decision, "Discard draft")));
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        next("close decision native resize requested");
      }
      case 4 -> {
        if (decision.width > 340 || decision.height > 250) return false;
        geometry(decision);
        check(
            "decision focus survives native resize",
            decision.getFocused() == button(decision, "Discard draft"));
        check(
            "compact decision retains all three choices",
            button(decision, "Keep editing").active
                && button(decision, "Discard draft").active
                && !button(decision, "Save and close").active);
        capture.accept("form-close-narrow.png", () -> next("compact draft decision captured"));
      }
      case 5 -> {
        retired.onPress();
        check(
            "retired decision callback stays inert",
            client.screen == decision && confirmedSaves.get() == 1);
        decision.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        check(
            "Escape keeps actual invalid draft",
            client.screen == form && raw(COUNT_PATH).equals("-") && form.editorView().dirty());
        replace(form, field(COUNT_PATH), "7");
        next("valid close draft repaired");
      }
      case 6 -> {
        if (!ready() || !form.editorView().validation().valid() || !raw(COUNT_PATH).equals("7"))
          return false;
        effect = Effect.REVOKE_WRITE;
        form.onClose();
        check(
            "valid native Save choice is available",
            button(client.screen, "Save and close").active);
        press(button(client.screen, "Save and close"));
        next("actual saved-but-unapplied close requested");
      }
      case 7 -> {
        if (!ready() || form.editorView().lastReceipt().isEmpty()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        if (receipt.persistence() == CommitReceipt.Persistence.SAVED)
          confirmedSaves.incrementAndGet();
        check(
            "saved but unapplied receipt keeps editor open",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.REFUSED
                && confirmedSaves.get() == 2
                && !form.editorView().dirty()
                && owner.handle().view().value().orElseThrow().count() == 4);
        check(
            "actual saved source differs from active value",
            source.read().candidate().orElseThrow().value().count() == 7);
        write.set(true);
        effect = Effect.NORMAL;
        recoveryFile = savedFile();
        recovery =
            DocumentActions.result(actions.applySaved(EntryPoint.COMMAND)).toCompletableFuture();
        next("explicit application-only recovery requested");
      }
      case 8 -> {
        if (!recovery.isDone() || !ready()) return false;
        var receipt = recovery.join().receipt().orElseThrow();
        check(
            "application-only recovery never repeats persistence",
            receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                && receipt.application() == CommitReceipt.Application.APPLIED
                && confirmedSaves.get() == 2
                && owner.handle().view().value().orElseThrow().count() == 7
                && savedFile().equals(recoveryFile));
        form.onClose();
        check(
            "explicit clean close ends only its editor",
            client.screen == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED
                && owner.handle().view().availability() == SettingsHandle.Availability.AVAILABLE);
        open();
        next("second actor opened for held save");
      }
      case 9 -> {
        if (!ready()) return false;
        replace(form, field(COUNT_PATH), "8");
        next("held close draft entered");
      }
      case 10 -> {
        if (!ready() || !raw(COUNT_PATH).equals("8")) return false;
        effect = Effect.HOLD;
        form.onClose();
        press(button(client.screen, "Save and close"));
        next("publication is held after source replacement");
      }
      case 11 -> {
        if (reached.getCount() != 0) return false;
        check(
            "held publication follows actual source replacement",
            source.read().candidate().orElseThrow().value().count() == 8);
        check("held save returns to the same editable form", client.screen == form);
        replace(form, field(LABEL_PATH), "later");
        release.countDown();
        next("later native input retained before actual acknowledgement");
      }
      case 12 -> {
        if (!ready()
            || !raw(LABEL_PATH).equals("later")
            || form.editorView().lastReceipt().isEmpty()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        if (receipt.persistence() == CommitReceipt.Persistence.SAVED)
          confirmedSaves.incrementAndGet();
        check(
            "later input prevents automatic close after a successful save",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.APPLIED
                && form.editorView().dirty()
                && confirmedSaves.get() == 3
                && client.screen == form);
        var saved = source.read().candidate().orElseThrow().value();
        check(
            "captured save excludes later unsaved input",
            saved.count() == 8
                && saved.label().equals("before")
                && owner.handle().view().value().orElseThrow().equals(saved));
        press(button(form, "Revert"));
        next("later input explicitly reverted");
      }
      case 13 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "revert uses newly saved baseline",
            raw(COUNT_PATH).equals("8") && raw(LABEL_PATH).equals("before"));
        replace(form, field(COUNT_PATH), "9");
        next("ordinary successful close draft entered");
      }
      case 14 -> {
        if (!ready() || !raw(COUNT_PATH).equals("9")) return false;
        effect = Effect.NORMAL;
        form.onClose();
        press(button(client.screen, "Save and close"));
        next("ordinary native save and close requested");
      }
      case 15 -> {
        if (client.screen != parent
            || form.editorView().availability() != EditorActor.Availability.CLOSED) return false;
        check(
            "successful native Save closes after real source and publication",
            confirmedSaves.incrementAndGet() == 4
                && source.read().candidate().orElseThrow().value().equals(new Options(9, "before"))
                && owner.handle().view().value().orElseThrow().equals(new Options(9, "before")));
        shutdown();
        next("close fixture context released");
      }
      case 16 -> {
        if (!worker.isTerminated()) return false;
        check(
            "actual context and borrowed worker lifetime ended",
            closed && owner.handle().view().availability() == SettingsHandle.Availability.CLOSED);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown close fixture stage");
    }
    return false;
  }

  void shutdown() {
    release.countDown();
    if (closed) return;
    closed = true;
    if (actions != null) actions.close();
    context.close();
    worker.shutdown();
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "PENDING");
    result.addProperty("stage", stage);
    result.addProperty("workflow", outcomes ? "review-and-outcome" : "draft-close");
    result.addProperty(
        "contextClosed",
        closed && owner.handle().view().availability() == SettingsHandle.Availability.CLOSED);
    result.addProperty("workerTerminated", worker.isTerminated());
    result.addProperty("confirmedSaves", confirmedSaves.get());
    result.add("checks", checks);
    return result;
  }
}
