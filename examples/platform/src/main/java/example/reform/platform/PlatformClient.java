package example.reform.platform;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/** Actual native form, focus, Unicode dispatch, shared commit and screenshot on each target. */
public final class PlatformClient {
  private static final JsonObject report = new JsonObject();
  private static final List<String> passed = new ArrayList<>();
  private static final FieldPath LABEL = FieldPath.ROOT.field("label");
  private static ClientFileSettings<PlatformData.Values> settings;
  private static SettingsScreen<?> form;
  private static CompletableFuture<?> pending;
  private static int ticks, stage, visibleTicks;
  private static boolean reopen,
      finished,
      worldScenario,
      recipeScenario,
      recipeLinksScenario,
      nativeRecipeScenario,
      commandScenario,
      authoringScenario,
      workspaceScenario,
      workflowScenario,
      chooserScenario,
      initializationFailure;
  private static ClientRegistration clientRegistration;
  private static java.util.function.Function<Screen, Screen> nativeMenu;
  private static String nativeMenuVersion;
  private static PlatformNativeItemShutdownChecks nativeItemShutdown;

  private static java.util.Map<String, String> recipeViewers = java.util.Map.of();

  public static void nativeRecipeViewers(java.util.Map<String, String> actual) {
    recipeViewers = java.util.Map.copyOf(actual);
  }

  static java.util.Map<String, String> recipeViewers() {
    return recipeViewers;
  }

  public static void nativeMenu(
      String version, java.util.function.Function<Screen, Screen> factory) {
    nativeMenuVersion = java.util.Objects.requireNonNull(version);
    nativeMenu = java.util.Objects.requireNonNull(factory);
  }

  private static java.util.function.Supplier<java.util.concurrent.CompletionStage<JsonObject>>
      extra = () -> CompletableFuture.completedStage(new JsonObject());

  public static void extraChecks(
      java.util.function.Supplier<java.util.concurrent.CompletionStage<JsonObject>> checks) {
    extra = java.util.Objects.requireNonNull(checks);
  }

  public static void register(ClientRegistration registration, String game, String loader) {
    try {
      clientRegistration = registration;
      var input =
          JsonParser.parseString(Files.readString(Path.of("platform-input.json")))
              .getAsJsonObject();
      reopen = input.get("reopen").getAsBoolean();
      worldScenario = input.has("worldClient") && input.get("worldClient").getAsBoolean();
      nativeRecipeScenario =
          input.has("nativeRecipeClient") && input.get("nativeRecipeClient").getAsBoolean();
      recipeLinksScenario =
          input.has("recipeLinksClient") && input.get("recipeLinksClient").getAsBoolean();
      recipeScenario = input.has("recipeClient") && input.get("recipeClient").getAsBoolean();
      commandScenario = input.has("commandClient") && input.get("commandClient").getAsBoolean();
      authoringScenario =
          input.has("authoringClient") && input.get("authoringClient").getAsBoolean();
      workspaceScenario =
          input.has("workspaceClient") && input.get("workspaceClient").getAsBoolean();
      workflowScenario = input.has("workflowClient") && input.get("workflowClient").getAsBoolean();
      chooserScenario = input.has("chooserClient") && input.get("chooserClient").getAsBoolean();
      initializationFailure =
          input.has("initializationFailure") && input.get("initializationFailure").getAsBoolean();
      boolean expectedMenu = input.has("modMenu") && input.get("modMenu").getAsBoolean();
      check(
          "optional native Mod Menu has the selected presence",
          expectedMenu == (nativeMenu != null));
      var viewerFacts = new JsonObject();
      recipeViewers.forEach(viewerFacts::addProperty);
      report.add("recipeViewers", viewerFacts);
      report.addProperty("modMenuPresent", nativeMenu != null);
      if (nativeMenu != null) report.addProperty("modMenuVersion", nativeMenuVersion);
      report.addProperty("status", "RUNNING");
      report.addProperty("java", Runtime.version().toString());
      report.addProperty("javaVendor", System.getProperty("java.vendor"));
      report.addProperty("minecraft", game);
      report.addProperty("loader", loader);
      report.addProperty("librarySha256", input.get("librarySha256").getAsString());
      report.addProperty(
          "loadedLibrary",
          SettingsScreen.class.getProtectionDomain().getCodeSource().getLocation().toString());
      check(
          "native client loads a packaged library",
          report.get("loadedLibrary").getAsString().contains(".jar"));
      if (Minecraft.getInstance() == null) {
        boolean refused = false;
        try {
          registration.onTick(() -> {});
        } catch (IllegalStateException expected) {
          refused = true;
        }
        check("pre-client runtime callback gives a deliberate lifecycle refusal", refused);
      }
      settings = ClientFileSettings.clientJson(registration, PlatformData.definition("client"));
      if (initializationFailure) PlatformInitializationChecks.register(registration);
      if (authoringScenario) {
        PlatformStructuredChecks.register(registration);
        PlatformNumericExample.register(registration);
        PlatformReferenceExample.register(registration);
        PlatformCollectionExample.register(registration);
        PlatformDependencyExample.register(registration);
        PlatformImportExample.register(registration);
        PlatformConflictExample.register(registration);
        PlatformCatalogExample.register(registration);
        PlatformWorkflowExample.register(registration);
        PlatformReviewCommands.register(registration);
      }
      if (workspaceScenario || workflowScenario || recipeLinksScenario || nativeRecipeScenario)
        PlatformWorkflowExample.register(registration);
      if (workspaceScenario) {
        PlatformWorkspaceChecks.register(registration);
        PlatformTaskChecks.register(registration);
        PlatformReferenceExample.register(registration);
      }
      if (chooserScenario) PlatformImportExample.register(registration);
      if (recipeScenario) PlatformWorldDefaultsExample.register(registration);
      if (commandScenario) {
        PlatformClientCommandChecks.register(registration);
        PlatformHeldClientChecks.register(registration);
      }
      PlatformWorldChecks.register(registration, settings);
      settings.registerMenu(Component.literal("Platform settings"));
      registration.onSetup(() -> registration.onTick(PlatformClient::tick));
    } catch (Exception | AssertionError failure) {
      fail(failure);
    }
  }

  private static void tick() {
    var client = Minecraft.getInstance();
    if (finished) return;
    try {
      if (Files.exists(Path.of("platform-cancel"))) {
        throw new AssertionError("Desktop fixture cancelled");
      }
      if (++ticks > (worldScenario ? 6000 : chooserScenario ? 7200 : 1800))
        throw new AssertionError("Client fixture exceeded its tick budget");
      if (settings.status().lifecycle() == ClientFileSettings.Lifecycle.UNAVAILABLE)
        throw new AssertionError(settings.status().report().toString());
      var current = NativeScreens.current(client);
      switch (stage) {
        case 0 -> {
          if (!(current instanceof TitleScreen)
              || !NativeClientProbe.ready(client)
              || settings.status().lifecycle() != ClientFileSettings.Lifecycle.READY) return;
          check(
              "native client factory initialization is complete",
              settings.status().initializationReceipt().isPresent());
          check(
              "actual saved/default client value is retained",
              settings
                  .runtime()
                  .orElseThrow()
                  .view()
                  .value()
                  .orElseThrow()
                  .label()
                  .equals(reopen ? "platformé" : "platform"));
          var opened =
              nativeMenu == null
                  ? settings.screen(null, Component.literal("Platform settings"))
                  : nativeMenu.apply(current);
          check(
              "native menu resolves the actual consumer form", opened instanceof SettingsScreen<?>);
          NativeScreens.open(client, opened);
          stage++;
        }
        case 1 -> {
          if (!(current instanceof SettingsScreen<?> screen)
              || screen.editorView().availability() != EditorActor.Availability.READY
              || screen.editorView().snapshot().isEmpty()) return;
          form = screen;
          if (++visibleTicks < 16) return;
          try {
            design.inherent.reform.minecraft.client.ClientEditor.retain(form);
            throw new AssertionError("An active form transferred its editor ownership");
          } catch (IllegalStateException expected) {
            check("active native form refuses editor ownership transfer", true);
          }
          var control = form.fieldControl(LABEL).orElseThrow();
          check("rendered label uses the actual native EditBox", control instanceof EditBox);
          check("native form can focus and reveal its label", form.focusField(LABEL));
          if (!reopen) {
            var box = (EditBox) control;
            box.moveCursorToEnd(false);
            check(
                "actual native Unicode event is consumed", NativeClientProbe.character(form, 'é'));
          }
          stage++;
        }
        case 2 -> {
          if (form.editorView().snapshot().isEmpty()) return;
          var value =
              (DraftNode.Scalar) form.editorView().snapshot().orElseThrow().draft().at(LABEL);
          if (!value.raw().equals("platformé")) return;
          check("native input reaches the typed shared draft", value.raw().equals("platformé"));
          check(
              "native edit does not implicitly publish/save",
              settings
                  .runtime()
                  .orElseThrow()
                  .view()
                  .value()
                  .orElseThrow()
                  .label()
                  .equals(reopen ? "platformé" : "platform"));
          pending =
              DocumentActions.result(form.editingActions().commit(EntryPoint.GUI))
                  .toCompletableFuture();
          stage++;
        }
        case 3 -> {
          if (!pending.isDone()) return;
          var result = (EditorActor.Commit<?>) pending.join();
          check("native form commit has a concrete receipt", result.receipt().isPresent());
          check(
              "native form commit has the exact persistence outcome",
              result.receipt().orElseThrow().persistence()
                  == (reopen
                      ? CommitReceipt.Persistence.UNCHANGED
                      : CommitReceipt.Persistence.SAVED));
          check(
              "native form persistence and runtime agree",
              settings
                  .runtime()
                  .orElseThrow()
                  .view()
                  .value()
                  .orElseThrow()
                  .label()
                  .equals("platformé"));
          visibleTicks = 0;
          stage++;
        }
        case 4 -> {
          if (++visibleTicks < 16) return;
          check("native form retains real pixel geometry", form.width > 0 && form.height > 0);
          report.addProperty("formWidth", form.width);
          report.addProperty("formHeight", form.height);
          var widget = form.fieldControl(LABEL).orElseThrow();
          var rectangle = new JsonObject();
          rectangle.addProperty("x", widget.getX());
          rectangle.addProperty("y", widget.getY());
          rectangle.addProperty("width", widget.getWidth());
          rectangle.addProperty("height", widget.getHeight());
          report.add("labelBounds", rectangle);
          pending = NativeClientProbe.capture(client, Path.of("platform-form.png"));
          stage++;
        }
        case 5 -> {
          if (!pending.isDone()) return;
          pending.join();
          check(
              "native framebuffer screenshot completed",
              Files.size(Path.of("platform-form.png")) > 0);
          int pixels = labelPixels(Path.of("platform-form.png"));
          report.addProperty("visibleLabelPixels", pixels);
          check("native label glyphs are visibly drawn inside the field", pixels >= 20);
          pending = extra.get().toCompletableFuture();
          stage++;
        }
        case 6 -> {
          if (!pending.isDone()) return;
          report.add("nativeOwner", (JsonObject) pending.join());
          if (initializationFailure)
            report.add("initializationFailure", PlatformInitializationChecks.report());
          form.onClose();
          check(
              "baseline form closes before another independent editor opens",
              NativeScreens.current(client) != form
                  && form.editorView().availability() == EditorActor.Availability.CLOSED);
          pending = PlatformKeyChecks.run(reopen).toCompletableFuture();
          stage++;
        }
        case 7 -> {
          if (!pending.isDone()) return;
          report.add("nativeKeys", (JsonObject) pending.join());
          if (chooserScenario) {
            pending = PlatformChooserChecks.run(clientRegistration).toCompletableFuture();
            stage = 24;
            return;
          }
          if (nativeRecipeScenario) {
            pending = PlatformNativeRecipeChecks.run(clientRegistration).toCompletableFuture();
            stage = 30;
            return;
          }
          if (recipeLinksScenario) {
            pending = PlatformRecipeLinkChecks.run(clientRegistration).toCompletableFuture();
            stage = 28;
            return;
          }
          if (workspaceScenario) {
            pending = PlatformWorkspaceChecks.run(clientRegistration, reopen).toCompletableFuture();
            stage = 26;
            return;
          }
          if (workflowScenario) {
            pending = PlatformWorkflowChecks.run(clientRegistration, reopen).toCompletableFuture();
            stage = 27;
            return;
          }
          if (authoringScenario) {
            if (!PlatformStructuredChecks.readyToOpen()) return;
            pending =
                PlatformStructuredChecks.run(clientRegistration, reopen).toCompletableFuture();
            stage = 10;
            return;
          }
          if (recipeScenario) {
            pending = PlatformRecipeChecks.run(clientRegistration, reopen).toCompletableFuture();
            stage = 9;
            return;
          }
          if (commandScenario) {
            pending =
                PlatformClientCommandChecks.runWorld(clientRegistration).toCompletableFuture();
            stage = 25;
            return;
          }
          if (worldScenario) {
            pending =
                PlatformWorldChecks.run(clientRegistration, settings, reopen).toCompletableFuture();
            stage++;
            return;
          }
          beginShutdown();
        }
        case 8 -> {
          if (!pending.isDone()) return;
          report.add("world", (JsonObject) pending.join());
          beginShutdown();
        }
        case 9 -> {
          if (!pending.isDone()) return;
          report.add("recipe", (JsonObject) pending.join());
          beginShutdown();
        }
        case 25 -> {
          if (!pending.isDone()) return;
          report.add("clientCommands", (JsonObject) pending.join());
          beginShutdown();
        }
        case 10 -> {
          if (!pending.isDone()) return;
          report.add("authoring", (JsonObject) pending.join());
          pending = PlatformNumericChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 11;
        }
        case 11 -> {
          if (!pending.isDone()) return;
          authoringResult("numeric");
          pending = PlatformReferenceChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 12;
        }
        case 12 -> {
          if (!pending.isDone()) return;
          authoringResult("references");
          pending = PlatformCollectionChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 13;
        }
        case 13 -> {
          if (!pending.isDone()) return;
          authoringResult("collections");
          pending = PlatformDependencyChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 14;
        }
        case 14 -> {
          if (!pending.isDone()) return;
          authoringResult("dependencies");
          pending = PlatformImportChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 15;
        }
        case 15 -> {
          if (!pending.isDone()) return;
          authoringResult("imports");
          pending = PlatformConflictChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 16;
        }
        case 16 -> {
          if (!pending.isDone()) return;
          authoringResult("conflicts");
          client.options.guiScale().set(2);
          NativeClientProbe.resize(client, 960, 640);
          NativeClientProbe.reflow(client);
          stage = 17;
        }
        case 17 -> {
          if (NativeScreens.current(client).width < 400) return;
          check(
              "catalog starts from the native title screen",
              NativeScreens.current(client) instanceof TitleScreen);
          pending = PlatformCatalogChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 18;
        }
        case 18 -> {
          if (!pending.isDone()) return;
          authoringResult("catalog");
          pending = PlatformReviewChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 21;
        }
        case 21 -> {
          if (!pending.isDone()) return;
          authoringResult("review");
          pending = PlatformReviewCommands.run(clientRegistration).toCompletableFuture();
          stage = 22;
        }
        case 22 -> {
          if (!pending.isDone()) return;
          authoringResult("reviewCommand");
          client.options.guiScale().set(2);
          NativeClientProbe.resize(client, 960, 640);
          NativeClientProbe.reflow(client);
          stage = 23;
        }
        case 23 -> {
          if (!(NativeScreens.current(client) instanceof TitleScreen)
              || NativeScreens.current(client).width < 400) return;
          pending = PlatformWorkflowChecks.run(clientRegistration, reopen).toCompletableFuture();
          stage = 20;
        }
        case 20 -> {
          if (!pending.isDone()) return;
          authoringResult("workflow");
          beginShutdown();
        }
        case 26 -> {
          if (!pending.isDone()) return;
          var result = (JsonObject) pending.join();
          check(
              "native single-workspace scenario completed",
              result.get("status").getAsString().equals("PASS"));
          report.add("workspace", result);
          beginShutdown();
        }
        case 27 -> {
          if (!pending.isDone()) return;
          var result = (JsonObject) pending.join();
          check(
              "native retained workflow regression completed",
              result.get("status").getAsString().equals("PASS"));
          report.add("workflow", result);
          beginShutdown();
        }
        case 24 -> {
          if (!pending.isDone()) return;
          var result = (JsonObject) pending.join();
          check(
              "actual native file chooser completes",
              result.get("status").getAsString().equals("PASS"));
          report.add("chooser", result);
          beginShutdown();
        }
        case 19 -> {
          if (form.editorView().availability() != EditorActor.Availability.READY
              || form.editorView().snapshot().isEmpty()) return;
          check(
              "a fresh native editor is ready before shutdown",
              NativeScreens.current(client) == form);
          report.addProperty("shutdownEditorReady", true);
          if (workspaceScenario) {
            nativeItemShutdown = new PlatformNativeItemShutdownChecks(report);
            stage = 29;
            return;
          }
          report.addProperty("status", "PASS");
          finished = true;
          client.stop();
        }
        case 28 -> {
          if (!pending.isDone()) return;
          report.add("recipeLinks", (JsonObject) pending.join());
          beginShutdown();
        }
        case 30 -> {
          if (!pending.isDone()) return;
          report.add("nativeRecipes", (JsonObject) pending.join());
          beginShutdown();
        }
        case 29 -> nativeItemShutdown.tick();
        default -> throw new AssertionError("Unknown native client stage");
      }
    } catch (Exception | AssertionError failure) {
      fail(failure);
    }
  }

  static void nativeItemStop(JsonObject facts) {
    finished = true;
    report.add("nativeItemShutdown", facts);
    report.addProperty(
        "status", "RUNNING"); // Only actual renderer RETURN + JVM epilogue can qualify.
    Minecraft.getInstance().stop();
  }

  private static void beginShutdown() {
    form = (SettingsScreen<?>) settings.screen(null, Component.literal("Shutdown editor"));
    NativeScreens.open(Minecraft.getInstance(), form);
    stage = 19;
  }

  private static void authoringResult(String name) {
    var result = (JsonObject) pending.join();
    check(
        "native " + name + " scenario completed",
        result.get("status").getAsString().equals("PASS"));
    var authoring = report.getAsJsonObject("authoring");
    authoring.add(name, result);
    authoring.getAsJsonArray("captures").addAll(result.getAsJsonArray("captures"));
  }

  private static void check(String name, boolean valid) {
    if (!valid) throw new AssertionError(name);
    passed.add(name);
  }

  private static int labelPixels(Path screenshot) throws java.io.IOException {
    var image = javax.imageio.ImageIO.read(screenshot.toFile());
    if (image == null) throw new java.io.IOException("No native PNG image");
    var bounds = report.getAsJsonObject("labelBounds");
    double sx = image.getWidth() / (double) form.width,
        sy = image.getHeight() / (double) form.height;
    int left = (int) Math.ceil((bounds.get("x").getAsInt() + 4) * sx);
    int right =
        (int) Math.floor((bounds.get("x").getAsInt() + bounds.get("width").getAsInt() - 4) * sx);
    int top = (int) Math.ceil((bounds.get("y").getAsInt() + 3) * sy);
    int bottom =
        (int) Math.floor((bounds.get("y").getAsInt() + bounds.get("height").getAsInt() - 3) * sy);
    int visible = 0;
    for (int y = top; y < bottom; y++)
      for (int x = left; x < right; x++) {
        int rgb = image.getRGB(x, y), r = rgb >>> 16 & 255, g = rgb >>> 8 & 255, b = rgb & 255;
        if (Math.min(r, Math.min(g, b)) >= 220
            && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 20) visible++;
      }
    return visible;
  }

  private static void fail(Throwable failure) {
    finished = true;
    report.addProperty("status", "FAIL");
    var trace = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(trace));
    report.addProperty("failure", trace.toString());
    failure.printStackTrace(System.err);
    var client = Minecraft.getInstance();
    if (client == null) {
      try {
        Files.writeString(
            Path.of("platform-report.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(report));
      } catch (java.io.IOException failed) {
        failure.addSuppressed(failed);
      }
      throw new IllegalStateException(
          "Platform client setup failed before Minecraft started", failure);
    }
    client.stop();
  }

  public static void stopped() {
    try {
      if (recipeScenario && report.has("recipe")) PlatformAdvancedRecipeChecks.stopped();
      if (commandScenario && report.has("clientCommands"))
        report
            .getAsJsonObject("clientCommands")
            .addProperty("shutdownOwnerClosed", PlatformClientCommandChecks.stopped());
      if (report.has("authoring") && report.getAsJsonObject("authoring").has("workflow")) {
        var owner = report.getAsJsonObject("authoring");
        var outcome = PlatformWorkflowChecks.stopped(owner.getAsJsonObject("workflow"));
        owner.add("workflow", outcome);
        if (!outcome.get("shutdownClosed").getAsBoolean()) {
          report.addProperty("status", "FAIL");
          report.addProperty("failure", "Native shutdown retained the managed workflow");
        }
      }
      if (report.has("workflow")) {
        var outcome = PlatformWorkflowChecks.stopped(report.getAsJsonObject("workflow"));
        report.add("workflow", outcome);
        if (!outcome.get("shutdownClosed").getAsBoolean()) {
          report.addProperty("status", "FAIL");
          report.addProperty("failure", "Native shutdown retained the regression workflow");
        }
      }
      if (report.has("workspace")) {
        var outcome = PlatformWorkspaceChecks.stopped(report.getAsJsonObject("workspace"));
        report.add("workspace", outcome);
        if (!outcome.get("shutdownClosed").getAsBoolean()) {
          report.addProperty("status", "FAIL");
          report.addProperty("failure", "Native shutdown retained the mixed-owner workspace");
        }
      }
      report.addProperty(
          "shutdownClosed",
          settings != null && settings.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED);
      report.addProperty(
          "shutdownEditorClosed",
          form != null && form.editorView().availability() == EditorActor.Availability.CLOSED);
      report.add("controls", new com.google.gson.Gson().toJsonTree(passed));
      report.addProperty("ticks", ticks);
      Files.writeString(
          Path.of("platform-report.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(report));
    } catch (Exception failure) {
      throw new IllegalStateException("Could not retain native client result", failure);
    }
  }

  private PlatformClient() {}
}
