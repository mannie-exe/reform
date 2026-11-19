package example.reformprobe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.ClientKeyBindings;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.client.UnsavedChangesScreen;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import example.reform.ClientExample;
import example.reform.NativeExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;

/** Real widgets and owner/file roundtrips; no reflection into private library state. */
@Mod(value = "reform_native_example_probe", dist = Dist.CLIENT)
public final class NativeFormProbe {
  private final Minecraft mc = Minecraft.getInstance();
  private final JsonObject report = new JsonObject();
  private final JsonArray controls = new JsonArray();
  private int stage, ticks, stageTick, captureFrames;
  private boolean finished;
  private Screen parent, form;
  private Screen captureScreen;
  private EditBox distance, label;
  private String savedBytes, capture;
  private Runnable afterCapture;
  private int cursor;
  private String selected;
  private final String mode;
  private volatile Throwable registrationFailure;
  private java.nio.file.attribute.FileTime repairedTime;
  private java.util.concurrent.CompletableFuture<JsonObject> commands;
  private NativeWorldRecipeControls recipe;
  private NativeStructuredControls structured;
  private NativeReferenceControls references;
  private NativeNumericControls numeric;
  private NativeDependencyControls dependencies;
  private NativeCollectionControls collections;
  private NativeImportControls imports;
  private NativeChooserControls chooser;
  private NativeConflictControls conflicts;
  private NativeImportCommandControls commandImports;
  private java.util.concurrent.CompletableFuture<JsonObject> importCommands;
  private NativeConflictCommandControls commandConflicts;
  private java.util.concurrent.CompletableFuture<JsonObject> conflictCommands;
  private NativeKeyOwnerControls keyOwner;
  private java.util.concurrent.CompletableFuture<JsonObject> keyOwnerResult;
  private NativeKeyControls keyControls;
  private NativeKeyFactoryControls keyFactory;
  private NativeCatalogControls catalog;
  private NativeReviewControls review;
  private NativeCloseControls closing;
  private NativeFileFormatsControls files;
  private NativeStartupControls startup;
  private NativeRegisteredControls registered;
  private NativeWorkflowControls workflow;
  private NativeBatchControls batch;
  private NativeWorldDefaultsControls defaults;
  private ClientKeyBindings invalidKeys;
  private final java.util.concurrent.atomic.AtomicInteger invalidKeySelections =
      new java.util.concurrent.atomic.AtomicInteger();
  private final java.util.concurrent.atomic.AtomicReference<byte[]> invalidKeySource =
      new java.util.concurrent.atomic.AtomicReference<>();

  public record LifecyclePreferences(boolean enabled) {}

  private ClientFileSettings<LifecyclePreferences> reentrantSettings;
  private java.util.concurrent.CompletableFuture<ClientFileSettings.Status<LifecyclePreferences>>
      reentrantRetry;
  private final java.util.concurrent.atomic.AtomicBoolean holdRetry =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final java.util.concurrent.CountDownLatch releaseRetry =
      new java.util.concurrent.CountDownLatch(1);

  public NativeFormProbe(IEventBus bus, ModContainer probeContainer) {
    try {
      var modeFile = mc.gameDirectory.toPath().resolve("native-form-mode.txt");
      if (Files.size(modeFile) > 32) throw new IllegalStateException("Invalid form fixture mode");
      mode = Files.readString(modeFile).strip();
      if (!List.of(
              "first",
              "reopen",
              "malformed",
              "reentrant",
              "recipe",
              "structured",
              "structured-reopen",
              "references",
              "references-reopen",
              "numeric",
              "numeric-reopen",
              "dependencies",
              "dependencies-reopen",
              "collections",
              "collections-reopen",
              "imports",
              "imports-reopen",
              "command-imports",
              "command-imports-reopen",
              "chooser",
              "conflicts",
              "conflicts-reopen",
              "command-conflicts",
              "command-conflicts-reopen",
              "keys-owner",
              "keys-owner-reopen",
              "keys-controls",
              "keys-controls-reopen",
              "keys-factory",
              "keys-factory-reopen",
              "catalog",
              "catalog-reopen",
              "review",
              "review-reopen",
              "close",
              "close-reopen",
              "outcomes",
              "outcomes-reopen",
              "files",
              "files-reopen",
              "startup",
              "startup-reopen",
              "registered",
              "registered-reopen",
              "workflow",
              "workflow-reopen",
              "batch",
              "batch-reopen",
              "defaults",
              "defaults-reopen",
              "defaults-invalid")
          .contains(mode)) throw new IllegalStateException("Unknown form fixture mode");
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("Missing disposable fixture mode", unavailable);
    }
    report.addProperty("status", "RUNNING");
    report.addProperty("mode", mode);
    report.add("controls", controls);
    if (mode.equals("reentrant")) registerReentrant(bus, probeContainer);
    if (mode.equals("startup")) NativeStartupControls.registerHeld(bus, probeContainer);
    if (mode.startsWith("keys-factory")) {
      invalidKeys =
          ClientKeyBindings.clientKeys(
              bus,
              probeContainer,
              design.inherent.reform.definition.DocumentId.parse(
                  probeContainer.getModId() + ":invalid_keys"),
              () -> {
                if (!mc.isSameThread())
                  throw new AssertionError("Native mapping supplier escaped client executor");
                invalidKeySelections.incrementAndGet();
                try {
                  invalidKeySource.set(Files.readAllBytes(mc.options.getFile().toPath()));
                } catch (java.io.IOException unavailable) {
                  throw new java.io.UncheckedIOException(unavailable);
                }
                return List.of(
                    example.reform.KeyExample.PRIMARY, example.reform.KeyExample.PRIMARY);
              });
    }
    bus.addListener((FMLClientSetupEvent ignored) -> registration(bus, probeContainer));
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::rendered);
    NeoForge.EVENT_BUS.addListener(this::renderedFrame);
    NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::shutdown);
  }

  private void registerReentrant(IEventBus bus, ModContainer probeContainer) {
    var definition =
        new design.inherent.reform.definition.DocumentDefinition<>(
            design.inherent.reform.definition.DocumentId.parse(
                probeContainer.getModId() + ":lifecycle"),
            1,
            design.inherent.reform.definition.RecordTypes.record(LifecyclePreferences.class)
                .build(),
            () -> new LifecyclePreferences(true),
            value -> {
              if (holdRetry.get()) {
                try {
                  if (!releaseRetry.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Fixture retry hold timed out");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(interrupted);
                }
              }
              return design.inherent.reform.validation.ValidationReport.VALID;
            });
    reentrantSettings = ClientFileSettings.clientJson(bus, probeContainer, definition);
    reentrantSettings
        .initialization()
        .whenComplete(
            (observed, failed) -> {
              try {
                if (failed != null)
                  throw new AssertionError("Initial lifecycle observation failed", failed);
                check(mc.isSameThread(), "Expected native initialization completion");
                check(
                    observed.lifecycle() == ClientFileSettings.Lifecycle.UNAVAILABLE,
                    "Expected the deliberately malformed lifecycle source");
                var source =
                    net.neoforged.fml.loading.FMLPaths.CONFIGDIR
                        .get()
                        .resolve(probeContainer.getModId())
                        .resolve("lifecycle.json");
                Files.writeString(source, "{\"schemaVersion\":1,\"values\":{\"enabled\":true}}");
                holdRetry.set(true);
                reentrantRetry = reentrantSettings.retryInitialization().toCompletableFuture();
                recordArtifact();
                controls.add(
                    "initialization observer immediately starts a held retry; close is deferred to an actual game tick");
              } catch (Throwable failure) {
                finish(failure);
              }
            });
  }

  private void registration(IEventBus bus, ModContainer probeContainer) {
    try {
      var settings = ClientExample.settings();
      check(
          settings.status().lifecycle() == ClientFileSettings.Lifecycle.REGISTERED,
          "Settings started before admitted client setup");
      check(
          settings.runtime().isEmpty() && settings.status().effective().isEmpty(),
          "Registration supplied an invented runtime value");
      check(
          !settings.initialization().toCompletableFuture().isDone(),
          "Registration did not preserve pending initialization");
      settings.initialization().toCompletableFuture().cancel(false);
      if (mode.equals("first"))
        check(Files.notExists(path()), "Registration initialized the JSON file");
      controls.add(
          "metadata registration has no file/runtime defaults; cancelled future copy does not cancel owner");
      var keys = example.reform.KeyExample.settings();
      check(
          keys.status().lifecycle() == ClientKeyBindings.Lifecycle.REGISTERED
              || keys.status().lifecycle() == ClientKeyBindings.Lifecycle.WAITING_FOR_NATIVE,
          "Native keys bound before the completed native mapping registry");
      check(
          keys.definition().isEmpty() && !keys.initialization().toCompletableFuture().isDone(),
          "Key metadata registration supplied an invented native definition");
      keys.initialization().toCompletableFuture().cancel(false);
      var actualContainer = ModList.get().getModContainerById(NativeExample.ID).orElseThrow();
      if (mode.equals("startup")) {
        check(
            example.reform.StartupExample.knownSelected().level() == 2
                && example.reform.StartupExample.brokenSelected().level() == 3,
            "Consumer bootstrap did not read the actual initial choices");
        var sourceRoot = mc.gameDirectory.toPath().resolve("config/startup-tools");
        Files.writeString(sourceRoot.resolve("known.properties"), "level=6\n");
        Files.writeString(sourceRoot.resolve("broken.properties"), "level=invalid\n");
        controls.add(
            "fixture changes saved choices after actual consumer bootstrap, before native source initialization");
      }
      check(
          example.reform.FileFormatsExample.DEFAULT_CALLS.get()
              == example.reform.FileFormatsExample.VALIDATED_DEFAULTS,
          "File factory reconstructed previously validated definition defaults");
      var sameFile =
          new design.inherent.reform.definition.DocumentDefinition<>(
              design.inherent.reform.definition.DocumentId.parse(NativeExample.ID + ":file_alias"),
              1,
              example.reform.FileFormatsExample.CHOICES.type(),
              () -> new example.reform.FileFormatsExample.Choices(true, 4, "before"));
      expectRefusal(
          () ->
              ClientFileSettings.properties(
                  bus, actualContainer, sameFile, "legacy-tools", "choices.properties"));
      expectRefusal(
          () ->
              ClientFileSettings.properties(
                  bus, actualContainer, sameFile, "LEGACY-TOOLS", "CHOICES.properties"));
      controls.add(
          "owned file aliases and portable-case collisions refuse metadata without defaults or IO");
      expectRefusal(
          () ->
              ClientKeyBindings.clientKeys(
                  bus,
                  actualContainer,
                  example.reform.KeyExample.DOCUMENT,
                  () -> {
                    throw new AssertionError("Duplicate entered provider");
                  }));
      expectRefusal(
          () ->
              ClientKeyBindings.clientKeys(
                  bus,
                  probeContainer,
                  example.reform.KeyExample.DOCUMENT,
                  () -> {
                    throw new AssertionError("Foreign namespace entered provider");
                  }));
      var duplicate =
          new design.inherent.reform.definition.DocumentDefinition<>(
              example.reform.KeyExample.DOCUMENT,
              1,
              NativeExample.DEFINITION.type(),
              NativeExample.DEFINITION::defaults);
      expectRefusal(() -> ClientFileSettings.clientJson(bus, actualContainer, duplicate));
      expectRefusal(
          () -> ClientFileSettings.clientJson(bus, actualContainer, NativeExample.DEFINITION));
      expectRefusal(
          () -> ClientFileSettings.clientJson(bus, probeContainer, NativeExample.DEFINITION));
      for (String path : List.of("../escape", "double//slash", "trailing./leaf", "con")) {
        var invalid =
            new design.inherent.reform.definition.DocumentDefinition<>(
                design.inherent.reform.definition.DocumentId.parse(NativeExample.ID + ":" + path),
                1,
                NativeExample.DEFINITION.type(),
                NativeExample.DEFINITION::defaults);
        expectRefusal(() -> ClientFileSettings.clientJson(bus, actualContainer, invalid));
      }
      var existing = actualContainer.getCustomExtension(IConfigScreenFactory.class).orElseThrow();
      expectRefusal(
          () ->
              keys.registerMenu(net.minecraft.network.chat.Component.literal("Replacement keys")));
      expectRefusal(
          () -> settings.registerMenu(net.minecraft.network.chat.Component.literal("Replacement")));
      check(
          actualContainer.getCustomExtension(IConfigScreenFactory.class).orElseThrow() == existing,
          "Duplicate menu registration replaced another factory");
      controls.add(
          "duplicate document/foreign namespace/nonportable path/menu replacement refused before effects");
    } catch (Throwable failure) {
      registrationFailure = failure;
    }
  }

  private static void expectRefusal(Runnable operation) {
    try {
      operation.run();
    } catch (IllegalArgumentException | IllegalStateException expected) {
      return;
    }
    throw new AssertionError("Invalid registration was accepted");
  }

  private void next(String control) {
    controls.add(control);
    stage++;
    stageTick = ticks;
  }

  private void tick(ClientTickEvent.Post event) {
    if (finished) return;
    ticks++;
    try {
      if (registrationFailure != null)
        throw new AssertionError("Registration contract failed", registrationFailure);
      if (ticks > (mode.equals("chooser") ? 6000 : 2400)
          || ticks - stageTick > (mode.equals("chooser") ? 1800 : 600))
        throw new AssertionError("Native form stage timed out: " + stage);
      if (mc.getOverlay() != null) return;
      if (capture != null) return;
      if (afterCapture != null) {
        var action = afterCapture;
        afterCapture = null;
        action.run();
        return;
      }
      if (mode.startsWith("defaults")) {
        if (defaults == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          defaults =
              new NativeWorldDefaultsControls(
                  mode,
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (defaults.tick()) {
          report.add("defaults", defaults.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("batch")) {
        if (batch == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          batch =
              new NativeBatchControls(
                  mode.endsWith("-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (batch.tick()) {
          report.add("batch", batch.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("workflow")) {
        if (workflow == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          workflow =
              new NativeWorkflowControls(
                  mode.endsWith("-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (workflow.tick()) {
          report.add("workflow", workflow.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("registered")) {
        if (registered == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          registered =
              new NativeRegisteredControls(
                  mode.endsWith("-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (registered.tick()) {
          report.add("nativeRegistered", registered.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("startup")) {
        if (startup == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          startup =
              new NativeStartupControls(
                  mode.endsWith("-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (startup.tick()) {
          report.add("startup", startup.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("files")) {
        if (files == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          files =
              new NativeFileFormatsControls(
                  mode.endsWith("-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (files.tick()) {
          report.add("files", files.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("close") || mode.startsWith("outcomes")) {
        if (closing == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          closing =
              new NativeCloseControls(
                  mode.endsWith("-reopen"),
                  mode.startsWith("outcomes"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (closing.tick()) {
          report.add(mode.startsWith("outcomes") ? "outcome" : "close", closing.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("review")) {
        if (review == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.CatalogExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          review =
              new NativeReviewControls(
                  mode.equals("review-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (review.tick()) {
          report.add("review", review.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("catalog")) {
        if (catalog == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.CatalogExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          catalog =
              new NativeCatalogControls(
                  mode.equals("catalog-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (catalog.tick()) {
          report.add("catalog", catalog.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("keys-factory")) {
        if (keyFactory == null) {
          if (!(mc.screen instanceof TitleScreen)
              || ClientExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          keyFactory =
              new NativeKeyFactoryControls(
                  mode.equals("keys-factory-reopen"),
                  invalidKeys,
                  invalidKeySelections,
                  invalidKeySource,
                  this::next);
        }
        if (keyFactory.tick()) {
          report.add("keyFactory", keyFactory.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("keys-controls")) {
        if (keyControls == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          keyControls =
              new NativeKeyControls(
                  mode.equals("keys-controls-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (keyControls.tick()) {
          report.add("keyControls", keyControls.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("keys-owner")) {
        if (keyOwner == null) {
          if (!(mc.screen instanceof TitleScreen)) return;
          recordArtifact();
          keyOwner = new NativeKeyOwnerControls();
          keyOwnerResult = keyOwner.run(mode.equals("keys-owner-reopen")).toCompletableFuture();
        }
        if (keyOwnerResult.isDone()) {
          report.add("keyOwner", keyOwnerResult.join());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("command-conflicts")) {
        if (commandConflicts == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.ConflictExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          commandConflicts = new NativeConflictCommandControls();
          conflictCommands =
              commandConflicts.run(mode.equals("command-conflicts-reopen")).toCompletableFuture();
        }
        if (conflictCommands.isDone()) {
          report.add("commandConflicts", conflictCommands.join());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("command-imports")) {
        if (commandImports == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.ImportExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          commandImports = new NativeImportCommandControls();
          importCommands =
              commandImports.run(mode.equals("command-imports-reopen")).toCompletableFuture();
        }
        if (importCommands.isDone()) {
          report.add("commandImports", importCommands.join());
          finish(null);
        }
        return;
      }
      if (mode.equals("chooser")) {
        if (chooser == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.ImportExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          chooser = new NativeChooserControls(this::next);
        }
        if (chooser.tick()) {
          report.add("chooser", chooser.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("conflicts")) {
        if (conflicts == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.ConflictExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          conflicts =
              new NativeConflictControls(
                  mode.equals("conflicts-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (conflicts.tick()) {
          report.add("conflicts", conflicts.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("imports")) {
        if (imports == null) {
          var status = example.reform.ImportExample.settings().status();
          if (status.lifecycle() == ClientFileSettings.Lifecycle.UNAVAILABLE)
            throw new AssertionError("Import initialization unavailable: " + status.report());
          if (!(mc.screen instanceof TitleScreen)
              || status.lifecycle() != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          imports =
              new NativeImportControls(
                  mode.equals("imports-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (imports.tick()) {
          report.add("imports", imports.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("collections")) {
        if (collections == null) {
          var status = example.reform.CollectionExample.settings().status();
          if (status.lifecycle() == ClientFileSettings.Lifecycle.UNAVAILABLE)
            throw new AssertionError("Collection initialization unavailable: " + status.report());
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.CollectionExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY
              || example.reform.ReferenceExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          collections =
              new NativeCollectionControls(
                  mode.equals("collections-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (collections.tick()) {
          report.add("collections", collections.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("dependencies")) {
        if (dependencies == null) {
          var status = example.reform.DependencyExample.settings().status();
          if (status.lifecycle() == ClientFileSettings.Lifecycle.UNAVAILABLE)
            throw new AssertionError(
                "Dependent fixture initialization unavailable: " + status.report());
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.DependencyExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          dependencies =
              new NativeDependencyControls(
                  mode.equals("dependencies-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (dependencies.tick()) {
          report.add("dependencies", dependencies.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("numeric")) {
        if (numeric == null) {
          var lifecycle = example.reform.NumericExample.settings().status().lifecycle();
          if (!(mc.screen instanceof TitleScreen)
              || lifecycle != ClientFileSettings.Lifecycle.READY
                  && (mode.equals("numeric-reopen")
                      || lifecycle != ClientFileSettings.Lifecycle.UNAVAILABLE)) return;
          recordArtifact();
          numeric =
              new NativeNumericControls(
                  mode.equals("numeric-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (numeric.tick()) {
          report.add("numeric", numeric.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("references")) {
        if (references == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.ReferenceExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          references =
              new NativeReferenceControls(
                  mode.equals("references-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (references.tick()) {
          report.add("references", references.report());
          finish(null);
        }
        return;
      }
      if (mode.startsWith("structured")) {
        if (structured == null) {
          if (!(mc.screen instanceof TitleScreen)
              || example.reform.StructuredExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          structured =
              new NativeStructuredControls(
                  mode.equals("structured-reopen"),
                  this::next,
                  (name, action) -> {
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (structured.tick()) {
          report.add("structured", structured.report());
          finish(null);
        }
        return;
      }
      if (mode.equals("recipe")) {
        if (recipe == null) {
          if (!(mc.screen instanceof TitleScreen)
              || ClientExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          recipe =
              new NativeWorldRecipeControls(
                  this::next,
                  (name, action) -> {
                    if (capture != null || afterCapture != null)
                      throw new IllegalStateException("Capture already pending");
                    capture = name;
                    afterCapture = action;
                  });
        }
        if (recipe.tick()) {
          report.add("recipe", recipe.report());
          finish(null);
        }
        return;
      }
      if (mode.equals("malformed")) {
        malformed();
        return;
      }
      if (mode.equals("reentrant")) {
        if (reentrantRetry != null) {
          check(mc.isRunning(), "Expected a running native client for the shutdown control");
          report.addProperty("nativeRunningBeforeClose", true);
          finish(null);
        }
        return;
      }
      switch (stage) {
        case 0 -> {
          if (!(mc.screen instanceof TitleScreen)
              || ClientExample.settings().status().lifecycle()
                  != ClientFileSettings.Lifecycle.READY) return;
          recordArtifact();
          parent = mc.screen;
          open();
          next("packaged library loaded; actual registered config factory opened");
        }
        case 1 -> {
          if (!controlsReady()) return;
          distance = input(form, "Distance");
          label = input(form, "Label");
          if (mode.equals("reopen")) {
            check(
                distance.getValue().equals("128") && label.getValue().equals("Example"),
                "Second JVM did not retain saved preferences");
            check(
                view().validation().valid() && !view().dirty(),
                "Second JVM has an invalid/dirty baseline");
            form.onClose();
            next("actual saved file and effective values reopen in a second JVM");
            finish(null);
            return;
          }
          check(
              distance.getValue().equals("64") && label.getValue().equals("Example"),
              "Initial factory values");
          check(!view().dirty() && view().validation().valid(), "Initial editor observation");
          savedBytes = Files.readString(path());
          geometry(form);
          capture = "form-initial.png";
          afterCapture = () -> replace(distance, "-");
          next("native defaults/field controls/geometry");
        }
        case 2 -> {
          if (view().validation().valid()) return;
          check(
              distance.getValue().equals("-") && raw("distance").equals("-"),
              "Invalid raw draft retained");
          check(!button(form, "Save and apply").active, "Invalid submission disabled");
          check(Files.readString(path()).equals(savedBytes), "Invalid typing wrote the file");
          var diagnostic = view().validation().issues().getFirst().fallback();
          check(
              form.getNarrationMessage().getString().contains(diagnostic),
              "Invalid diagnostic narration");
          capture = "form-invalid.png";
          afterCapture = () -> replace(distance, "128");
          next("invalid native input retained; complete validation blocks saving");
        }
        case 3 -> {
          if (!view().validation().valid()
              || !raw("distance").equals("128")
              || !button(form, "Save and apply").active) return;
          click(button(form, "Save and apply"));
          next("native save control invoked");
        }
        case 4 -> {
          if (view().lastReceipt().isEmpty() || view().pending().isPresent() || view().dirty())
            return;
          var receipt = view().lastReceipt().orElseThrow();
          check(
              receipt.persistence() == CommitReceipt.Persistence.SAVED
                  && receipt.application() == CommitReceipt.Application.APPLIED,
              "Actual save/application receipt");
          check(Files.readString(path()).contains("128"), "Saved numeric value absent");
          savedBytes = Files.readString(path());
          capture = "form-saved.png";
          afterCapture = () -> replace(label, "Native label selection");
          next("file saved and domain publication acknowledged");
        }
        case 5 -> {
          if (!raw("label").equals("Native label selection")) return;
          label.setCursorPosition(12);
          label.setHighlightPos(6);
          cursor = label.getCursorPosition();
          selected = label.getHighlighted();
          mc.options.guiScale().set(3);
          GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 480, 360);
          mc.resizeDisplay();
          next("native GUI-scale resize requested with focused selection");
        }
        case 6 -> {
          if (form.width > 340 || form.height > 250) return;
          report.addProperty("narrowWidth", form.width);
          report.addProperty("narrowHeight", form.height);
          check(input(form, "Label") == label, "Resize replaced an active native editor");
          check(
              label.getValue().equals("Native label selection")
                  && label.getCursorPosition() == cursor
                  && label.getHighlighted().equals(selected),
              "Resize lost raw text/caret/selection");
          check(label.isFocused(), "Resize lost field focus");
          geometry(form);
          capture = "form-narrow.png";
          afterCapture = form::onClose;
          next("narrow reflow preserves native field identity/focus/caret/selection");
        }
        case 7 -> {
          check(
              mc.screen instanceof UnsavedChangesScreen, "Dirty close did not request a decision");
          click(button(mc.screen, "Keep editing"));
          next("dirty close kept draft");
        }
        case 8 -> {
          check(
              mc.screen == form
                  && view().availability() == EditorActor.Availability.READY
                  && label.getValue().equals("Native label selection"),
              "Keep editing closed or replaced the draft");
          click(button(form, "Revert"));
          next("native revert requested");
        }
        case 9 -> {
          if (view().dirty() || !label.getValue().equals("Example")) return;
          check(
              distance.getValue().equals("128") && Files.readString(path()).equals(savedBytes),
              "Revert changed saved values");
          click(button(form, "Defaults"));
          next("revert retains actual saved baseline");
        }
        case 10 -> {
          if (!distance.getValue().equals("64") || !view().dirty()) return;
          check(Files.readString(path()).equals(savedBytes), "Reset wrote factory defaults");
          form.onClose();
          next("defaults stage a dirty draft without writing");
        }
        case 11 -> {
          check(mc.screen instanceof UnsavedChangesScreen, "Default draft closed without review");
          click(button(mc.screen, "Discard draft"));
          check(
              mc.screen == parent && view().availability() == EditorActor.Availability.CLOSED,
              "Discard leaked editor or parent context");
          open();
          next("explicit discard closes actor; real factory reopened");
        }
        case 12 -> {
          if (!controlsReady()) return;
          check(
              input(form, "Distance").getValue().equals("128"),
              "Reopen used unsaved factory defaults");
          form.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
          check(form.getCurrentFocusPath() != null, "Native Tab failed to find a focus path");
          report.addProperty("narration", form.getNarrationMessage().getString());
          report.addProperty("width", form.width);
          report.addProperty("height", form.height);
          form.onClose();
          check(mc.screen == parent, "Clean close failed to restore parent");
          next("saved values reopened; native keyboard path and clean close");
          commands = NativeCommandControls.run(path()).toCompletableFuture();
        }
        case 13 -> {
          if (!commands.isDone()) return;
          report.add("commands", commands.join());
          finish(null);
        }
        default -> throw new AssertionError("Unexpected stage " + stage);
      }
    } catch (Throwable failure) {
      finish(failure);
    }
  }

  private void recordArtifact() throws Exception {
    var artifact = ModList.get().getModFileById("reform").getFile().getFilePath();
    report.addProperty("libraryPath", artifact.toString());
    report.addProperty(
        "librarySha256",
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
    report.addProperty(
        "libraryCodeSource",
        SettingsScreen.class.getProtectionDomain().getCodeSource().getLocation().toString());
  }

  private void malformed() throws Exception {
    var settings = ClientExample.settings();
    switch (stage) {
      case 0 -> {
        if (!(mc.screen instanceof TitleScreen)
            || settings.status().lifecycle() != ClientFileSettings.Lifecycle.UNAVAILABLE) return;
        recordArtifact();
        check(
            Files.readString(path()).equals("{invalid retained input"),
            "Malformed file was replaced during initialization");
        check(
            settings.runtime().orElseThrow().view().value().isEmpty()
                && !settings.status().report().valid(),
            "Malformed input became effective defaults");
        check(
            settings.initialization().toCompletableFuture().join().lifecycle()
                == ClientFileSettings.Lifecycle.UNAVAILABLE,
            "Initialization future invented success");
        parent = mc.screen;
        open();
        next(
            "malformed saved bytes remain intact; no effective defaults and unavailable initialization receipt");
      }
      case 1 -> {
        check(
            !(form instanceof SettingsScreen<?>)
                && widgets(form).stream().noneMatch(EditBox.class::isInstance),
            "Unavailable owner exposed editable factory defaults");
        check(
            form.getNarrationMessage()
                .getString()
                .contains(settings.status().report().issues().getFirst().fallback()),
            "Unavailable reason missing from narration");
        geometry(form);
        capture = "form-unavailable.png";
        afterCapture =
            () -> {
              try {
                // Simulate an explicit external repair with the first boot's actual saved bytes.
                savedBytes =
                    Files.readString(mc.gameDirectory.toPath().resolve("native-form-valid.json"));
                Files.writeString(path(), savedBytes);
                repairedTime = Files.getLastModifiedTime(path());
                click(button(form, "Retry"));
              } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
            };
        next("unavailable native screen reports error with explicit recovery");
      }
      case 2 -> {
        if (settings.status().lifecycle() != ClientFileSettings.Lifecycle.READY
            || !(mc.screen instanceof SettingsScreen<?>)) return;
        form = mc.screen;
        if (!controlsReady()) return;
        check(
            input(form, "Distance").getValue().equals("128"),
            "Recovery published factory values instead of repaired saved data");
        check(
            Files.readString(path()).equals(savedBytes)
                && Files.getLastModifiedTime(path()).equals(repairedTime),
            "Recovery rewrote an already valid saved file");
        check(
            settings.status().initializationReceipt().orElseThrow().persistence()
                == CommitReceipt.Persistence.UNCHANGED,
            "Recovery repeated persistence");
        check(
            settings.initialization().toCompletableFuture().join().lifecycle()
                == ClientFileSettings.Lifecycle.UNAVAILABLE,
            "First-attempt history changed after retry");
        form.onClose();
        next(
            "explicit retry reads repaired data without rewriting; first-attempt failure remains historical");
        finish(null);
      }
      default -> throw new AssertionError("Unknown malformed stage");
    }
  }

  private void open() {
    var container = ModList.get().getModContainerById(NativeExample.ID).orElseThrow();
    var factory = container.getCustomExtension(IConfigScreenFactory.class).orElseThrow();
    form = factory.createScreen(container, parent);
    mc.setScreen(form);
  }

  private boolean controlsReady() {
    return view().snapshot().isPresent()
        && widgets(form).stream()
            .anyMatch(
                widget ->
                    widget instanceof EditBox && widget.getMessage().getString().equals("Distance"))
        && button(form, "Done").active;
  }

  private EditorActor.View<?> view() {
    return ((SettingsScreen<?>) form).editorView();
  }

  private String raw(String name) {
    var node = (DraftNode.ObjectValue) view().snapshot().orElseThrow().draft().root();
    return ((DraftNode.Scalar) node.fields().get(name)).raw();
  }

  private static Path path() {
    return net.neoforged.fml.loading.FMLPaths.CONFIGDIR
        .get()
        .resolve(NativeExample.ID)
        .resolve("preferences.json");
  }

  private void replace(EditBox input, String value) {
    replace(form, input, value);
  }

  static void replace(Screen form, EditBox input, String value) {
    check(focus(form, input), "Input not in the native focus tree");
    input.moveCursorToEnd(false);
    input.setHighlightPos(0);
    for (char next : value.toCharArray())
      check(form.charTyped(next, 0), "Native text event was not consumed");
  }

  static boolean focus(ContainerEventHandler parent, GuiEventListener target) {
    for (var child : parent.children()) {
      if (child == target) {
        parent.setFocused(child);
        return true;
      }
      if (child instanceof ContainerEventHandler nested && widgets(nested).contains(target)) {
        parent.setFocused(child);
        return focus(nested, target);
      }
    }
    return false;
  }

  private void click(Button button) {
    click(mc.screen, button);
  }

  static void click(Screen screen, Button button) {
    check(
        button.active && button.visible,
        "Inactive native control " + button.getMessage().getString());
    check(
        screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0),
        "Native mouse event not consumed");
    screen.mouseReleased(button.getX() + 1, button.getY() + 1, 0);
  }

  static EditBox input(ContainerEventHandler parent, String label) {
    return widgets(parent).stream()
        .filter(EditBox.class::isInstance)
        .map(EditBox.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(label))
        .findFirst()
        .orElseThrow();
  }

  static Button button(ContainerEventHandler parent, String label) {
    return widgets(parent).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(label))
        .findFirst()
        .orElseThrow();
  }

  static List<AbstractWidget> widgets(ContainerEventHandler parent) {
    var found = new ArrayList<AbstractWidget>();
    for (var child : parent.children()) {
      if (child instanceof AbstractWidget widget) found.add(widget);
      if (child instanceof ContainerEventHandler nested) found.addAll(widgets(nested));
    }
    return found;
  }

  static void geometry(Screen screen) {
    var buttons =
        screen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .toList();
    for (var button : buttons) {
      check(
          button.getX() >= 0
              && button.getY() >= 0
              && button.getRight() <= screen.width
              && button.getBottom() <= screen.height,
          "Footer outside native screen bounds");
      for (var other : buttons)
        if (button != other)
          check(
              button.getRight() <= other.getX()
                  || other.getRight() <= button.getX()
                  || button.getBottom() <= other.getY()
                  || other.getBottom() <= button.getY(),
              "Native footer controls overlap");
    }
  }

  private void rendered(ScreenEvent.Render.Post event) {
    if (capture != null && event.getScreen() == mc.screen) captureScreen = event.getScreen();
  }

  private void renderedFrame(RenderFrameEvent.Post event) {
    boolean matching = capture != null && captureScreen != null && captureScreen == mc.screen;
    captureScreen = null;
    if (matching) {
      // Read back only after GameRenderer completes and flushes its GUI batches.
      if (++captureFrames == 1)
        Screenshot.grab(
            mc.gameDirectory,
            capture.replace(".png", "-entry.png"),
            mc.getMainRenderTarget(),
            ignored -> {});
      // Retain a matched settled frame as well, including after native viewport resize.
      if (captureFrames < 4) return;
      Screenshot.grab(mc.gameDirectory, capture, mc.getMainRenderTarget(), ignored -> {});
      capture = null;
      captureFrames = 0;
    }
  }

  private void finish(Throwable failure) {
    finished = true;
    report.addProperty("stage", stage);
    report.addProperty("ticks", ticks);
    report.addProperty("status", failure == null ? "PASS" : "FAIL");
    if (workflow != null) report.add("workflow", workflow.report());
    if (batch != null) report.add("batch", batch.report());
    if (defaults != null) report.add("defaults", defaults.report());
    if (numeric != null) report.add("numeric", numeric.report());
    if (dependencies != null) report.add("dependencies", dependencies.report());
    if (collections != null) report.add("collections", collections.report());
    if (imports != null) report.add("imports", imports.report());
    if (chooser != null) report.add("chooser", chooser.report());
    if (conflicts != null) report.add("conflicts", conflicts.report());
    if (commandImports != null) report.add("commandImports", commandImports.report());
    if (commandConflicts != null) report.add("commandConflicts", commandConflicts.report());
    if (keyOwner != null) report.add("keyOwner", keyOwner.report());
    if (keyControls != null) report.add("keyControls", keyControls.report());
    if (keyFactory != null) report.add("keyFactory", keyFactory.report());
    if (catalog != null) report.add("catalog", catalog.report());
    if (review != null) report.add("review", review.report());
    if (closing != null) {
      closing.shutdown();
      report.add("close", closing.report());
    }
    if (mode.startsWith("dependencies")) {
      var status = example.reform.DependencyExample.settings().status();
      report.addProperty("dependencyLifecycle", status.lifecycle().name());
      report.addProperty("dependencyInitialization", status.report().toString());
    }
    if (structured != null) report.add("structured", structured.report());
    if (references != null) report.add("references", references.report());
    if (failure != null) {
      report.addProperty("failure", failure.toString());
      var stack = new java.io.StringWriter();
      failure.printStackTrace(new java.io.PrintWriter(stack));
      report.addProperty("stack", stack.toString());
    }
    try {
      writeReport();
    } catch (Exception writeFailure) {
      throw new IllegalStateException("Cannot retain native form evidence", writeFailure);
    } finally {
      mc.stop();
    }
  }

  private void writeReport() throws java.io.IOException {
    Files.writeString(
        mc.gameDirectory.toPath().resolve("native-form-report.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report));
  }

  private void shutdown(GameShuttingDownEvent event) {
    if (workflow != null) report.add("workflow", workflow.report());
    if (batch != null) report.add("batch", batch.report());
    if (defaults != null) report.add("defaults", defaults.report());
    if (files != null) report.add("files", files.report());
    if (keyFactory != null) report.add("keyFactory", keyFactory.report());
    if (keyControls != null) {
      keyControls.shutdown();
      report.add("keyControls", keyControls.report());
    }
    if (keyOwner != null) {
      keyOwner.shutdown();
      report.add("keyOwner", keyOwner.report());
    }
    if (imports != null) imports.shutdown();
    if (commandImports != null) {
      commandImports.shutdown();
      report.add("commandImports", commandImports.report());
    }
    if (commandConflicts != null) {
      commandConflicts.shutdown();
      report.add("commandConflicts", commandConflicts.report());
    }
    try {
      NativeStartupControls.shutdownHeld();
      if (startup != null) report.add("startup", startup.report());
      if (registered != null) report.add("nativeRegistered", registered.report());
      check(
          example.reform.KeyExample.settings().status().lifecycle()
              == ClientKeyBindings.Lifecycle.CLOSED,
          "Native key factory survived game shutdown");
      if (reentrantSettings != null) {
        check(
            reentrantSettings.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED,
            "Reentrant client context survived shutdown");
        check(
            reentrantRetry != null && reentrantRetry.isDone(),
            "Reentrant retry future stranded at shutdown");
        check(
            reentrantRetry.join().lifecycle() == ClientFileSettings.Lifecycle.CLOSED,
            "Held retry reported an invented effect instead of context closure");
        report.addProperty("reentrantRetryClosed", true);
      }
      if (recipe != null) recipe.shutdown();
      check(
          example.reform.StructuredExample.settings().status().lifecycle()
              == ClientFileSettings.Lifecycle.CLOSED,
          "Structured settings context survived shutdown");
      check(
          example.reform.ReferenceExample.settings().status().lifecycle()
              == ClientFileSettings.Lifecycle.CLOSED,
          "Reference settings context survived shutdown");
      check(
          example.reform.CollectionExample.settings().status().lifecycle()
              == ClientFileSettings.Lifecycle.CLOSED,
          "Collection settings context survived shutdown");
      check(
          example.reform.ImportExample.settings().status().lifecycle()
              == ClientFileSettings.Lifecycle.CLOSED,
          "Import settings context survived shutdown");
      check(
          example.reform.ConflictExample.settings().status().lifecycle()
              == ClientFileSettings.Lifecycle.CLOSED,
          "Conflict settings context survived shutdown");
      var catalogClosed =
          example.reform.CatalogExample.settings().status().lifecycle()
                  == ClientFileSettings.Lifecycle.CLOSED
              && example.reform.CatalogExample.settings()
                      .runtime()
                      .orElseThrow()
                      .view()
                      .availability()
                  == design.inherent.reform.owner.SettingsHandle.Availability.CLOSED;
      check(catalogClosed, "Catalog settings context survived shutdown");
      if (catalog != null) {
        var state = catalog.report();
        state.addProperty("factoryClosed", catalogClosed);
        report.add("catalog", state);
      }
      if (review != null) {
        var state = review.report();
        state.addProperty("factoryClosed", catalogClosed);
        report.add("review", state);
      }
      var settings = ClientExample.settings();
      check(
          settings.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED,
          "Client settings context survived game shutdown");
      check(
          settings.runtime().orElseThrow().view().availability()
              == design.inherent.reform.owner.SettingsHandle.Availability.CLOSED,
          "Published runtime survived client shutdown");
      check(
          settings.initialization().toCompletableFuture().isDone(),
          "Initialization future stranded at shutdown");
      report.addProperty("nativeShutdownClosed", true);
    } catch (Throwable failure) {
      report.addProperty("status", "FAIL");
      report.addProperty("shutdownFailure", failure.toString());
    } finally {
      releaseRetry.countDown();
    }
    try {
      writeReport();
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private static void check(boolean passed, String message) {
    if (!passed) throw new AssertionError(message);
  }
}
