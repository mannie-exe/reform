package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.*;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual file-owner write boundaries and native stopped-Batch results, not synthetic receipts. */
final class PlatformBatchFailureChecks {
  record Setting(int count) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 100));
  private static final RecordType<Setting> TYPE =
      RecordType.<Setting>builder(v -> new Setting(v.get(COUNT)))
          .field(COUNT, Setting::count)
          .build();
  private static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
  private static final String FOREIGN = "{foreign input after actual replacement";
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final UUID run = UUID.randomUUID();
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final AtomicBoolean armed = new AtomicBoolean(), writable = new AtomicBoolean(true);
  private final AtomicInteger firstWrites = new AtomicInteger(), secondWrites = new AtomicInteger();
  private final List<CompletableFuture<?>> initialization = new ArrayList<>();
  private OwnerContext context;
  private DocumentActions<Setting> first, second;
  private DocumentBatch batch;
  private BatchReviewScreen screen;
  private CompletableFuture<Void> pending;
  private DocumentBatch.Review review;
  private OperationHandle<DocumentBatch.Result> submission;
  private OperationHandle<DocumentBatch.Result> repeat;
  private Path firstPath, secondPath;
  private byte[] secondSource;
  private int mode, stage;

  PlatformBatchFailureChecks(BiConsumer<String, Boolean> check) {
    this(check, null);
  }

  private PlatformBatchFailureChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  static java.util.concurrent.CompletionStage<com.google.gson.JsonObject> run(
      ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "BATCH-FAILURES",
        driver ->
            new PlatformFormProbe.Scene() {
              final com.google.gson.JsonArray checks = new com.google.gson.JsonArray();
              final PlatformBatchFailureChecks probe =
                  new PlatformBatchFailureChecks(
                      (label, valid) -> {
                        if (!valid) throw new AssertionError(label);
                        checks.add(label);
                      },
                      driver::capture);
              boolean complete;

              public boolean tick() throws Exception {
                return complete = probe.tick();
              }

              public String diagnostic() {
                return "batch mode=" + probe.mode + ",stage=" + probe.stage;
              }

              public com.google.gson.JsonObject report() {
                var value = new com.google.gson.JsonObject();
                value.addProperty("status", complete ? "PASS" : "RUNNING");
                value.add("checks", checks.deepCopy());
                return value;
              }

              public void close() {
                if (probe.context != null) probe.context.close();
              }
            });
  }

  private void leaveResult() {
    var current = NativeScreens.current(client);
    var back =
        widgets(current).stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(
                w ->
                    w.getMessage().getString().equals("Back")
                        || w.getMessage().getString().startsWith("Back to "))
            .findFirst()
            .orElseThrow();
    click(current, back);
    stage = 5;
  }

  private DocumentActions<Setting> owner(Path root, String name, boolean leading) {
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:batch_edge/" + mode + "/" + name),
            1,
            TYPE,
            () -> new Setting(1),
            value -> ValidationReport.VALID);
    var file = new OwnedFile(StorageBinding.relative(root, name + ".json"), 8192, 4096);
    var store =
        new JsonStore<>(
            definition, file, JsonStore.Format.NEW_ENVELOPE, JSON, DraftLimits.CONFIGURATION);
    var source = new FileOwner<>(context, store, 16);
    var access = context.access(() -> true, () -> leading || writable.get());
    var session = source.open(access);
    initialization.add(session.initialize().toCompletableFuture());
    return ClientEditors.open(context, access, session, definition, JSON, actions -> actions);
  }

  private String ownerSummary(String label) {
    var text = measuredText(NativeScreens.current(client));
    int at = text.indexOf(label);
    if (at < 0 || at + 1 == text.size())
      throw new AssertionError("Missing actual owner summary: " + label + " in " + text);
    return text.get(at + 1);
  }

  boolean tick() throws Exception {
    if (mode == 2) return true;
    switch (stage) {
      case 0 -> {
        armed.set(false);
        writable.set(true);
        firstWrites.set(0);
        secondWrites.set(0);
        initialization.clear();
        var root =
            client
                .gameDirectory
                .toPath()
                .toAbsolutePath()
                .normalize()
                .resolve("config/reform_platform_example/batch_edges_" + run + "_" + mode);
        Files.createDirectories(root);
        firstPath = root.resolve("first.json");
        secondPath = root.resolve("second.json");
        context =
            new OwnerContext(
                client::execute,
                NativeClientProbe.ioPool(),
                () -> {
                  if (!client.isSameThread())
                    throw new AssertionError("Batch fixture left native thread");
                },
                OwnerContext.Limits.STANDARD);
        context.onClose(
            PlatformBatchWriteFault.install(
                path -> {
                  if (path.equals(firstPath)) {
                    firstWrites.incrementAndGet();
                    if (armed.get()) {
                      if (mode == 0) writable.set(false);
                      else {
                        Files.writeString(firstPath, FOREIGN);
                        throw new IOException(
                            "Controlled foreign change after actual file replacement");
                      }
                    }
                  } else if (path.equals(secondPath)) secondWrites.incrementAndGet();
                }));
        first = owner(root, "first", true);
        second = owner(root, "second", false);
        pending = CompletableFuture.allOf(initialization.toArray(CompletableFuture[]::new));
        stage = 1;
      }
      case 1 -> {
        if (!pending.isDone()) return false;
        pending.join();
        pending =
            CompletableFuture.allOf(
                DocumentActions.result(first.ensureLoaded(EntryPoint.GUI)).toCompletableFuture(),
                DocumentActions.result(second.ensureLoaded(EntryPoint.GUI)).toCompletableFuture());
        stage = 10;
      }
      case 10 -> {
        if (!pending.isDone()) return false;
        pending.join();
        secondSource = Files.readAllBytes(secondPath);
        check.accept(
            "Batch edge owns two actually initialized JSON sources: " + mode,
            firstWrites.get() == 1 && secondWrites.get() == 1);
        pending =
            CompletableFuture.allOf(
                DocumentActions.result(first.edit(EntryPoint.GUI, "count", "4"))
                    .toCompletableFuture(),
                DocumentActions.result(second.edit(EntryPoint.GUI, "count", "8"))
                    .toCompletableFuture());
        stage = 2;
      }
      case 2 -> {
        if (!pending.isDone()) return false;
        pending.join();
        batch =
            new DocumentBatch(
                context,
                context.access(() -> true, () -> true),
                DocumentId.parse("fixture:batch_edge/" + mode),
                List.of(
                    DocumentBatch.member(first, DocumentBatch.Intent.COMMIT),
                    DocumentBatch.member(second, DocumentBatch.Intent.COMMIT)));
        screen =
            new BatchReviewScreen(
                Component.literal(mode == 0 ? "Later owner refusal" : "Uncertain write"),
                batch,
                context::isOpen,
                id ->
                    Component.literal(
                        id.equals(DocumentId.parse("fixture:batch_edge/" + mode + "/first"))
                            ? "First owner"
                            : "Second owner"),
                (entry, origin) -> {
                  throw new AssertionError("No field inspection requested");
                },
                () -> NativeScreens.open(client, parent),
                context::close);
        NativeScreens.open(client, screen);
        stage = 3;
      }
      case 3 -> {
        if (screen.reviewedInput().isEmpty() || !button(screen, "Save reviewed documents").active)
          return false;
        review = screen.reviewedInput().orElseThrow();
        check.accept(
            "Batch edge captures both valid COMMIT members before fault admission: " + mode,
            review.valid() && review.entries().size() == 2);
        armed.set(true);
        click(screen, button(screen, "Save reviewed documents"));
        stage = 4;
      }
      case 4 -> {
        if (screen.submission().isEmpty()
            || !screen.submission().orElseThrow().snapshot().terminal()) return false;
        submission = screen.submission().orElseThrow();
        var result = submission.snapshot().result().orElseThrow();
        var current = NativeScreens.current(client);
        if (current == screen
            || widgets(current).stream()
                .noneMatch(w -> w.getMessage().getString().equals("Show technical details")))
          return false;
        var receipt =
            ((EditorActor.Commit<?>)
                    result.outcomes().getFirst().operation().orElseThrow().result().orElseThrow())
                .receipt()
                .orElseThrow();
        check.accept(
            "Stopped Batch retains first actual receipt and never writes the later owner: " + mode,
            !result.completed()
                && firstWrites.get() == 2
                && secondWrites.get() == 1
                && Arrays.equals(secondSource, Files.readAllBytes(secondPath)));
        var summary = ownerSummary("First owner");
        if (mode == 0) {
          check.accept(
              "Later WRITE revocation preserves native Saved prefix and refused later outcome",
              receipt.persistence() == CommitReceipt.Persistence.SAVED
                  && summary.startsWith("Saved")
                  && !result.outcomes().get(1).report().valid());
        } else {
          check.accept(
              "Post-replacement foreign bytes produce actual UNKNOWN and native reconciliation feedback",
              receipt.persistence() == CommitReceipt.Persistence.UNKNOWN
                  && first.view().pending().isPresent()
                  && Files.readString(firstPath).equals(FOREIGN)
                  && summary.toLowerCase(Locale.ROOT).contains("unknown")
                  && !summary.startsWith("Saved")
                  && result.outcomes().get(1).operation().isEmpty());
        }
        var ownerOperation = result.outcomes().getFirst().operation().orElseThrow().id().toString();
        String ordinary = String.join("\n", measuredText(current));
        check.accept(
            "Stopped Batch keeps raw identities out of ordinary outcome feedback: " + mode,
            !ordinary.contains(result.review().toString()) && !ordinary.contains(ownerOperation));
        click(current, button(current, "Show technical details"));
        String technical = String.join("\n", measuredText(current));
        check.accept(
            "Stopped Batch technical disclosure retains exact reviewed and actual operation IDs: "
                + mode,
            technical.contains(result.review().toString()) && technical.contains(ownerOperation));
        click(current, button(current, "Hide technical details"));
        check.accept(
            "Hiding Batch technical facts preserves same result and write counts: " + mode,
            screen.submission().orElseThrow() == submission
                && firstWrites.get() == 2
                && secondWrites.get() == 1);
        stage = 9;
        if (capture == null) leaveResult();
        else capture.accept("batch-edge-" + mode + ".png", this::leaveResult);
      }
      case 5 -> {
        if (NativeScreens.current(client) != screen) return false;
        check.accept(
            "Consumed or unknown Batch cannot be submitted again through its actual control: "
                + mode,
            screen.reviewedInput().orElseThrow() == review
                && !button(screen, "Save reviewed documents").active
                && screen.submission().orElseThrow() == submission);
        repeat = batch.commit(EntryPoint.GUI, review);
        stage = 6;
      }
      case 6 -> {
        if (!repeat.snapshot().terminal()) return false;
        check.accept(
            "Rejected repeat never rewrites saved or uncertain source: " + mode,
            repeat.snapshot().state() == OperationSnapshot.State.REFUSED
                && repeat.snapshot().report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("batch.review"))
                && firstWrites.get() == 2
                && secondWrites.get() == 1);
        first.close();
        second.close();
        batch.close();
        context.close();
        NativeScreens.open(client, parent);
        mode++;
        stage = 0;
      }
      case 9 -> {}
      default -> throw new AssertionError("Unknown Batch edge stage " + stage);
    }
    return false;
  }
}
