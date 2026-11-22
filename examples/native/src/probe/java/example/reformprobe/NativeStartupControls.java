package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.OperationScreen;
import design.inherent.reform.minecraft.client.ReviewScreen;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.minecraft.client.UnsavedChangesScreen;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import example.reform.StartupExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Real startup-selected inputs, saved next-start choices and pending construction shutdown. */
final class NativeStartupControls {
  private static final CountDownLatch HELD = new CountDownLatch(1), RELEASE = new CountDownLatch(1);
  private static ClientFileSettings<StartupExample.Choice> held;
  private static boolean heldClosed;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final boolean reopen;
  private final JsonArray checks = new JsonArray();
  private SettingsScreen<?> form;
  private ReviewScreen<?> review;
  private OperationScreen<?> operation;
  private int stage;
  private boolean complete;

  static void registerHeld(IEventBus bus, ModContainer container) {
    var original = StartupExample.KNOWN.type();
    var type =
        ValueType.of(
            original.codec(),
            original.shape(),
            new SnapshotPolicy<StartupExample.Choice>() {
              @Override
              public StartupExample.Choice freeze(
                  StartupExample.Choice value, SnapshotContext context) {
                if (value.level() == 5
                    && Thread.currentThread().getName().startsWith("reform-client-settings-")) {
                  HELD.countDown();
                  try {
                    if (!RELEASE.await(60, TimeUnit.SECONDS))
                      throw new IllegalStateException("Startup construction fixture timed out");
                  } catch (InterruptedException failed) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failed);
                  }
                }
                return value;
              }

              @Override
              public boolean equivalent(StartupExample.Choice a, StartupExample.Choice b) {
                return a.equals(b);
              }
            });
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse(container.getModId() + ":startup_held"),
            1,
            type,
            () -> new StartupExample.Choice(4));
    var actual =
        DraftTree.from(type, new StartupExample.Choice(5), DraftLimits.CONFIGURATION)
            .inspect(definition, new JsonLimits(8192, 16, 256, 256, 32, 256))
            .candidate()
            .orElseThrow();
    held =
        ClientFileSettings.startupProperties(
            NeoForgeClientRegistration.of(bus, container),
            definition,
            Optional.of(actual),
            "startup-tools",
            "held.properties");
  }

  static void shutdownHeld() {
    if (held == null) return;
    try {
      heldClosed =
          held.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED
              && held.initialization().toCompletableFuture().isDone()
              && held.initialization().toCompletableFuture().join().lifecycle()
                  == ClientFileSettings.Lifecycle.CLOSED
              && held.runtime().isEmpty()
              && !Files.exists(
                  Minecraft.getInstance()
                      .gameDirectory
                      .toPath()
                      .resolve("config/startup-tools/held.properties"));
      if (!heldClosed)
        throw new AssertionError(
            "Pending startup owner survived native shutdown or created a source");
    } finally {
      RELEASE.countDown();
    }
  }

  NativeStartupControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  private Path path(String name) {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/startup-tools")
        .resolve(name + ".properties");
  }

  private void check(String label, boolean value) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && button(form, "Done").active;
  }

  private void open(ClientFileSettings<?> source, String title) {
    form = (SettingsScreen<?>) source.screen(parent, Component.literal(title));
    client.setScreen(form);
  }

  private void edit(String value) {
    var path = FieldPath.ROOT.field("level");
    check("startup field uses the normal native editor", form.focusField(path));
    replace(form, (EditBox) form.fieldControl(path).orElseThrow(), value);
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (StartupExample.known().status().lifecycle() != ClientFileSettings.Lifecycle.READY
            || StartupExample.unknown().status().lifecycle() != ClientFileSettings.Lifecycle.READY)
          return false;
        if (reopen) {
          check(
              "restart selects actual newly saved consumer inputs",
              StartupExample.knownSelected().level() == 10
                  && StartupExample.brokenSelected().level() == 11
                  && StartupExample.known()
                          .runtime()
                          .orElseThrow()
                          .view()
                          .value()
                          .orElseThrow()
                          .level()
                      == 10
                  && StartupExample.broken()
                          .runtime()
                          .orElseThrow()
                          .view()
                          .value()
                          .orElseThrow()
                          .level()
                      == 11);
          check(
              "restart does not invent an unavailable selection",
              StartupExample.unknown().runtime().orElseThrow().view().value().isEmpty()
                  && Files.readString(path("unknown")).equals("level=8\n"));
          complete = true;
          return true;
        }
        if (StartupExample.broken().status().lifecycle() != ClientFileSettings.Lifecycle.UNAVAILABLE
            || HELD.getCount() != 0) return false;
        check(
            "known active selection differs from current saved choices",
            StartupExample.knownSelected().level() == 2
                && StartupExample.known()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .level()
                    == 2
                && Files.readString(path("known")).equals("level=6\n"));
        check(
            "unknown actual selection is not supplied by saved choices or defaults",
            StartupExample.unknown().status().effective().orElseThrow().value().isEmpty()
                && Files.readString(path("unknown")).equals("level=7\n"));
        check(
            "malformed saved choices preserve a separately selected active value",
            StartupExample.broken().runtime().orElseThrow().view().value().orElseThrow().level()
                    == 3
                && StartupExample.brokenSelected().level() == 3);
        check(
            "held construction has no source or published handle",
            held.status().lifecycle() == ClientFileSettings.Lifecycle.INITIALIZING
                && held.runtime().isEmpty()
                && !Files.exists(path("held")));
        open(StartupExample.known(), "Saved choices and active selection");
        next("known startup editor opened");
      }
      case 1 -> {
        if (!ready()) return false;
        check(
            "startup action names saving rather than live application",
            button(form, "Save for restart").active);
        edit("9");
        next("next-start choice entered");
      }
      case 2 -> {
        if (!ready() || !form.editorView().dirty()) return false;
        click(form, button(form, "Review changes…"));
        review = (ReviewScreen<?>) client.screen;
        next("startup change review opened");
      }
      case 3 -> {
        if (review.reviewedInput().isEmpty()) return false;
        var capture = review.reviewedInput().orElseThrow();
        var entry = capture.entries().getFirst();
        check(
            "review separates saved draft and actually selected startup inputs",
            entry.baseline().equals("\"6\"")
                && entry.draft().equals("\"9\"")
                && entry.effective().orElseThrow().equals("\"2\"")
                && entry
                    .application()
                    .orElseThrow()
                    .timings()
                    .contains(ApplicationEffects.Timing.GAME_RESTART));
        geometry(review);
        this.capture.accept("form-startup-review.png", () -> next("startup distinction captured"));
      }
      case 4 -> {
        click(review, button(review, "Save reviewed choices"));
        operation = (OperationScreen<?>) client.screen;
        next("reviewed startup save requested");
      }
      case 5 -> {
        if (operation.observedOperation().isEmpty()
            || !operation.observedOperation().orElseThrow().terminal()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        check(
            "saved startup receipt is staged with an explicit restart requirement",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.STAGED
                && receipt.requirements().stream()
                    .anyMatch(v -> v.kind() == CommitReceipt.RequirementKind.GAME_RESTART)
                && StartupExample.known()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .level()
                    == 2
                && Files.readString(path("known")).equals("level=9\n"));
        check(
            "startup owner never offers an application-only shortcut",
            !button(operation, "Apply saved values").active);
        click(operation, button(operation, "Back"));
        edit("10");
        next("startup close choice staged");
      }
      case 6 -> {
        if (!ready() || !form.editorView().dirty()) return false;
        form.onClose();
        check(
            "dirty startup close retains owner-specific labels",
            client.screen instanceof UnsavedChangesScreen
                && button(client.screen, "Save for restart and close").active);
        click(client.screen, button(client.screen, "Save for restart and close"));
        next("startup save-and-close requested");
      }
      case 7 -> {
        if (client.screen != parent
            || form.editorView().availability() != EditorActor.Availability.CLOSED) return false;
        check(
            "confirmed staged save can close without changing active selection",
            Files.readString(path("known")).equals("level=10\n")
                && StartupExample.knownSelected().level() == 2);
        open(StartupExample.unknown(), "Active selection unavailable");
        next("unknown startup selection editor opened");
      }
      case 8 -> {
        if (!ready()) return false;
        edit("8");
        next("unknown-selection saved choice entered");
      }
      case 9 -> {
        if (!ready() || !form.editorView().dirty()) return false;
        click(form, button(form, "Save for restart"));
        next("unknown-selection save requested");
      }
      case 10 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "saved unknown selection stays unknown",
            form.editorView().lastReceipt().orElseThrow().application()
                    == CommitReceipt.Application.STAGED
                && StartupExample.unknown().runtime().orElseThrow().view().value().isEmpty()
                && Files.readString(path("unknown")).equals("level=8\n"));
        click(form, button(form, "Done"));
        client.setScreen(
            StartupExample.broken()
                .screen(parent, Component.literal("Repair saved startup choices")));
        Files.writeString(path("broken"), "level=11\n");
        click(client.screen, button(client.screen, "Retry"));
        next("saved startup choices repaired explicitly");
      }
      case 11 -> {
        if (!(client.screen instanceof SettingsScreen<?> current)
            || StartupExample.broken().status().lifecycle() != ClientFileSettings.Lifecycle.READY)
          return false;
        form = current;
        if (!ready()) return false;
        check(
            "source repair does not replace the already selected startup input",
            StartupExample.broken().runtime().orElseThrow().view().value().orElseThrow().level()
                    == 3
                && Files.readString(path("broken")).equals("level=11\n"));
        click(form, button(form, "Done"));
        check(
            "pending construction is still held until actual game shutdown",
            HELD.getCount() == 0
                && held.status().lifecycle() == ClientFileSettings.Lifecycle.INITIALIZING);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown startup fixture stage " + stage);
    }
    return false;
  }

  JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "PENDING");
    report.addProperty("stage", stage);
    report.addProperty(
        "factoriesClosed",
        List.of(StartupExample.known(), StartupExample.unknown(), StartupExample.broken()).stream()
            .allMatch(v -> v.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED));
    report.addProperty("heldConstructionClosed", reopen || heldClosed);
    report.add("checks", checks);
    return report;
  }
}
