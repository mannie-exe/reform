package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.neoforge.client.ClientRegisteredSettings;
import design.inherent.reform.neoforge.client.OperationScreen;
import design.inherent.reform.neoforge.client.ReviewScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import example.reform.RegisteredExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandSourceStack;

/** Real CLIENT/COMMON native files, shared forms/commands, reload identity and game shutdown. */
final class NativeRegisteredControls {
  private final Minecraft client = Minecraft.getInstance();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final Screen parent;
  private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
  private final CommandSourceStack commandSource =
      new ClientCommandSourceStack(
          CommandSource.NULL,
          Vec3.ZERO,
          Vec2.ZERO,
          0,
          "registered-probe",
          Component.literal("registered-probe"),
          null);
  private SettingsScreen<?> form;
  private ReviewScreen<?> review;
  private OperationScreen<?> operation;
  private CompletableFuture<SettingsCommands.Reply> waiting;
  private Object loaded;
  private byte[] beforeReload;
  private int stage;
  private boolean done;

  NativeRegisteredControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    parent = client.screen;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
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

  private void edit(String field, String value) {
    var path = FieldPath.ROOT.field(field);
    check("native field has its generated editor", form.focusField(path));
    replace(form, (EditBox) form.fieldControl(path).orElseThrow(), value);
  }

  private boolean draft(Map<String, Integer> expected) {
    var view = form.editorView();
    if (view.snapshot().isEmpty() || !view.validation().valid()) return false;
    var inspection = view.snapshot().orElseThrow().inspect();
    if (inspection.candidate().isEmpty()) return false;
    return inspection.candidate().orElseThrow().value().equals(expected);
  }

  private Path clientFile() {
    return RegisteredExample.clientConfig().getFullPath();
  }

  private void command(String input) throws Exception {
    waiting = new CompletableFuture<>();
    check("native Brigadier accepts " + input, dispatcher.execute(input, commandSource) == 1);
  }

  private void reply(SettingsCommands.Reply reply) {
    check("native command feedback stays on client executor", client.isSameThread());
    if (reply.kind() == SettingsCommands.Kind.QUEUED) return;
    if (waiting != null) waiting.complete(reply);
  }

  private boolean commandDone() {
    if (waiting == null || !waiting.isDone()) return false;
    var reply = waiting.join();
    var observed =
        reply.operation().orElseThrow(() -> new AssertionError(reply.message().getString()));
    check(
        "native command completes for its actual document",
        observed.state() == OperationSnapshot.State.COMPLETED
            && observed.target().orElseThrow().equals(RegisteredExample.client().document()));
    waiting = null;
    return true;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        for (var registration :
            java.util.List.of(RegisteredExample.client(), RegisteredExample.common())) {
          if (registration.status().lifecycle() == ClientRegisteredSettings.Lifecycle.UNAVAILABLE)
            throw new AssertionError(
                "Native binding unavailable for "
                    + registration.document()
                    + ": "
                    + registration.status().report());
        }
        if (RegisteredExample.client().status().lifecycle()
                != ClientRegisteredSettings.Lifecycle.READY
            || RegisteredExample.common().status().lifecycle()
                != ClientRegisteredSettings.Lifecycle.READY) return false;
        check(
            "native registration supplies metadata without inventing a definition",
            RegisteredExample.metadataOnly() && RegisteredExample.metadataRefusals() == 4);
        check(
            "actual legacy native filenames remain unchanged",
            clientFile().getFileName().toString().equals(RegisteredExample.CLIENT_FILE)
                && RegisteredExample.commonConfig()
                    .getFullPath()
                    .getFileName()
                    .toString()
                    .equals(RegisteredExample.COMMON_FILE));
        if (reopen) {
          check(
              "a new JVM reads actual saved native values",
              RegisteredExample.live() == 13
                  && RegisteredExample.world() == 6
                  && RegisteredExample.game() == 8
                  && RegisteredExample.defaults() == 10);
          done = true;
          return true;
        }
        check(
            "native sources differ from their declared defaults",
            RegisteredExample.live() == 9 && RegisteredExample.defaults() == 7);
        check(
            "fixture warms actual native restart caches",
            RegisteredExample.world() == 1 && RegisteredExample.game() == 1);
        form =
            (SettingsScreen<?>)
                RegisteredExample.client()
                    .screen(parent, Component.literal("Native registered preferences"));
        client.setScreen(form);
        next("native client form opened");
      }
      case 1 -> {
        if (!ready()) return false;
        check(
            "native saved source loads through the ordinary generated form",
            draft(Map.of("live", 9, "world", 1, "game", 1)));
        check("native submit names persistence", button(form, "Save native settings").active);
        edit("live", "12");
        edit("world", "6");
        edit("game", "8");
        next("native draft changes entered");
      }
      case 2 -> {
        if (!ready() || !draft(Map.of("live", 12, "world", 6, "game", 8))) return false;
        check(
            "typing leaves native values and caches unchanged",
            RegisteredExample.live() == 9
                && RegisteredExample.rawWorld() == 1
                && RegisteredExample.rawGame() == 1);
        click(form, button(form, "Review changes…"));
        review = (ReviewScreen<?>) client.screen;
        next("native change review opened");
      }
      case 3 -> {
        if (review.reviewedInput().isEmpty()) return false;
        var value = review.reviewedInput().orElseThrow();
        check(
            "native review keeps source and unknown domain values distinct",
            value.source().kind().equals("neoforge")
                && value.entries().stream().allMatch(entry -> entry.effective().isEmpty()));
        check(
            "native review retains actual restart declarations",
            value.entries().stream()
                    .anyMatch(
                        entry ->
                            entry.path().equals(FieldPath.ROOT.field("world"))
                                && entry
                                    .application()
                                    .orElseThrow()
                                    .timings()
                                    .contains(ApplicationEffects.Timing.WORLD_RESTART))
                && value.entries().stream()
                    .anyMatch(
                        entry ->
                            entry.path().equals(FieldPath.ROOT.field("game"))
                                && entry
                                    .application()
                                    .orElseThrow()
                                    .timings()
                                    .contains(ApplicationEffects.Timing.GAME_RESTART)));
        geometry(review);
        capture.accept("form-native-review.png", () -> next("native review captured"));
      }
      case 4 -> {
        click(review, button(review, "Save reviewed native settings"));
        operation = (OperationScreen<?>) client.screen;
        next("reviewed native save requested");
      }
      case 5 -> {
        if (operation.observedOperation().isEmpty()
            || !operation.observedOperation().orElseThrow().terminal()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        check(
            "native save retains its real staged receipt",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.STAGED
                && receipt.requirements().stream()
                    .anyMatch(item -> item.kind() == CommitReceipt.RequirementKind.WORLD_RESTART)
                && receipt.requirements().stream()
                    .anyMatch(item -> item.kind() == CommitReceipt.RequirementKind.GAME_RESTART));
        check(
            "native setter and cache semantics remain native",
            RegisteredExample.live() == 12
                && RegisteredExample.rawWorld() == 6
                && RegisteredExample.rawGame() == 8
                && RegisteredExample.savedWorldCache() == 1
                && RegisteredExample.savedGameCache() == 1);
        check(
            "actual native reload callback runs once on client executor",
            RegisteredExample.clientReloads() == 1
                && RegisteredExample.lastClientReload() == Thread.currentThread());
        check(
            "an ordinary native save keeps its loaded binding",
            RegisteredExample.client().status().lifecycle()
                == ClientRegisteredSettings.Lifecycle.READY);
        check(
            "native owner never offers a fake application-only retry",
            !button(operation, "Apply saved values").active);
        click(operation, button(operation, "Back"));
        form.onClose();
        dispatcher.register(RegisteredExample.client().commandBranch("nativecheck", this::reply));
        command("nativecheck describe");
        next("native command inspection requested");
      }
      case 6 -> {
        if (!commandDone()) return false;
        command("nativecheck set live 13");
        next("native command draft requested");
      }
      case 7 -> {
        if (!commandDone()) return false;
        check(
            "command draft does not run native setters",
            RegisteredExample.live() == 12 && RegisteredExample.clientReloads() == 1);
        command("nativecheck save");
        next("native command save requested");
      }
      case 8 -> {
        if (!commandDone()) return false;
        check(
            "GUI and command retain actual native serializer effects",
            RegisteredExample.live() == 13
                && RegisteredExample.clientReloads() == 2
                && Files.readString(clientFile()).contains("live = 13")
                && !Files.readString(clientFile()).contains("schemaVersion"));
        form =
            (SettingsScreen<?>)
                RegisteredExample.common()
                    .screen(parent, Component.literal("Native installation defaults"));
        client.setScreen(form);
        next("COMMON defaults form opened");
      }
      case 9 -> {
        if (!ready()) return false;
        check("local COMMON defaults use their actual native source", draft(Map.of("level", 7)));
        edit("level", "10");
        client.options.guiScale().set(3);
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        next("native defaults narrow reflow requested");
      }
      case 10 -> {
        if (!ready() || form.width > 340 || !draft(Map.of("level", 10))) return false;
        geometry(form);
        capture.accept("form-native-defaults.png", () -> next("native defaults captured"));
      }
      case 11 -> {
        click(form, button(form, "Save native settings"));
        next("native defaults save requested");
      }
      case 12 -> {
        if (form.editorView().lastReceipt().isEmpty() || form.editorView().pending().isPresent())
          return false;
        check(
            "COMMON defaults retain native callbacks and staged outcome",
            RegisteredExample.defaults() == 10
                && RegisteredExample.commonReloads() == 1
                && form.editorView().lastReceipt().orElseThrow().application()
                    == CommitReceipt.Application.STAGED);
        if (client.screen instanceof OperationScreen<?> result) {
          if (result.observedOperation().isEmpty()
              || !result.observedOperation().orElseThrow().terminal()) return false;
          click(result, button(result, "Back"));
        }
        form.onClose();
        beforeReload = Files.readAllBytes(clientFile());
        loaded = RegisteredExample.clientConfig().getLoadedConfig();
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.CLIENT);
        ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.CLIENT, FMLPaths.CONFIGDIR.get());
        next("actual native client contexts reloaded");
      }
      case 13 -> {
        if (RegisteredExample.client().status().lifecycle()
            != ClientRegisteredSettings.Lifecycle.UNAVAILABLE) return false;
        check(
            "native context replacement retires prior editing authority",
            RegisteredExample.clientConfig().getLoadedConfig() != loaded
                && RegisteredExample.client().definition().isEmpty()
                && RegisteredExample.client()
                        .initialization()
                        .toCompletableFuture()
                        .join()
                        .lifecycle()
                    == ClientRegisteredSettings.Lifecycle.READY);
        client.setScreen(
            RegisteredExample.client().screen(parent, Component.literal("Reloaded native source")));
        click(client.screen, button(client.screen, "Retry"));
        next("explicit native context retry requested");
      }
      case 14 -> {
        if (!(client.screen instanceof SettingsScreen<?> current)
            || current.editorView().availability() != EditorActor.Availability.READY) return false;
        form = current;
        check(
            "explicit retry binds current native data without a write",
            draft(Map.of("live", 13, "world", 6, "game", 8))
                && Arrays.equals(beforeReload, Files.readAllBytes(clientFile()))
                && RegisteredExample.client().status().lifecycle()
                    == ClientRegisteredSettings.Lifecycle.READY);
        form.onClose();
        done = true;
        return true;
      }
      default -> throw new AssertionError("Unexpected native registered stage");
    }
    return false;
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", done ? "PASS" : "FAIL");
    result.addProperty("stage", stage);
    result.addProperty(
        "clientClosed",
        RegisteredExample.client().status().lifecycle()
            == ClientRegisteredSettings.Lifecycle.CLOSED);
    result.addProperty(
        "defaultsClosed",
        RegisteredExample.common().status().lifecycle()
            == ClientRegisteredSettings.Lifecycle.CLOSED);
    result.add("checks", checks);
    result.add("events", new com.google.gson.Gson().toJsonTree(RegisteredExample.events()));
    result.addProperty("eventTraceComplete", RegisteredExample.completeEvents());
    return result;
  }
}
