package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.ClientKeyBindings;
import design.inherent.reform.neoforge.client.KeyBindingScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.value.ValueTypes;
import example.reform.ClientExample;
import example.reform.KeyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Ordinary factory, actual native source and failed-view ownership/resource qualification. */
final class NativeKeyFactoryControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final boolean reopen;
  private final ClientKeyBindings unavailable;
  private final AtomicInteger failedSelections;
  private final AtomicReference<byte[]> failedSource;
  private final Consumer<String> progress;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
  private final CommandSourceStack source =
      new ClientCommandSourceStack(
          CommandSource.NULL,
          Vec3.ZERO,
          Vec2.ZERO,
          0,
          "native-key-factory",
          Component.literal("native-key-factory"),
          null);
  private final FieldPath primary = FieldPath.ROOT.field(KeyExample.PRIMARY.getName());
  private SettingsScreen<?> form;
  private CompletableFuture<SettingsCommands.Reply> waiting;
  private CompletableFuture<Void> commands;
  private Path options;
  private byte[] before;
  private int stage;
  private boolean complete;

  NativeKeyFactoryControls(
      boolean reopen,
      ClientKeyBindings unavailable,
      AtomicInteger failedSelections,
      AtomicReference<byte[]> failedSource,
      Consumer<String> progress) {
    this.reopen = reopen;
    this.unavailable = unavailable;
    this.failedSelections = failedSelections;
    this.failedSource = failedSource;
    this.progress = progress;
    report.add("checks", checks);
  }

  JsonObject report() {
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.addProperty(
        "factoryClosed",
        KeyExample.settings().status().lifecycle() == ClientKeyBindings.Lifecycle.CLOSED);
    report.addProperty(
        "unavailableClosed",
        unavailable.status().lifecycle() == ClientKeyBindings.Lifecycle.CLOSED);
    return report;
  }

  private void check(String label, boolean value) {
    if (!value) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private void press(Button button) {
    check("factory control supports native focus", focus(client.screen, button));
    click(client.screen, button);
  }

  private String raw() {
    return ((DraftNode.Scalar)
            form.editorView().snapshot().orElseThrow().draft().at(primary.field("key")))
        .raw();
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private void failedNativeViews() {
    var actual = client.options.keyMappings;
    try {
      client.options.keyMappings =
          Arrays.stream(actual)
              .filter(mapping -> mapping != KeyExample.PRIMARY)
              .toArray(net.minecraft.client.KeyMapping[]::new);
      for (int attempt = 0; attempt < 70; attempt++) {
        try {
          KeyExample.screen(parent);
          throw new AssertionError("A missing actual mapping opened an editor");
        } catch (PolicyRefusal refused) {
          check(
              "failed native view releases resources on attempt " + attempt,
              refused.report().issues().stream()
                  .anyMatch(issue -> issue.code().equals("keys.mapping")));
        }
      }
    } finally {
      client.options.keyMappings = actual;
    }
    var restored = KeyExample.screen(parent);
    restored.removed();
    check(
        "native factory still admits a view after repeated constructor refusals",
        KeyExample.settings().status().lifecycle() == ClientKeyBindings.Lifecycle.READY);
  }

  private void failedJsonViews() {
    var reference =
        ClientExample.settings()
            .references(
                DocumentId.parse("reform_native_example:owned_reference"),
                ValueTypes.text(64),
                ValueTypes.text(64).snapshotPolicy(),
                ReferenceLimits.STANDARD);
    try {
      var mismatched = Map.of(DocumentId.parse("reform_native_example:wrong_reference"), reference);
      for (int attempt = 0; attempt < 70; attempt++) {
        try {
          ClientExample.settings()
              .screen(parent, Component.literal("Rejected registry"), mismatched);
          throw new AssertionError("A mismatched reference registry opened an editor");
        } catch (IllegalArgumentException refused) {
          check(
              "failed JSON view releases resources on attempt " + attempt,
              refused.getMessage().contains("Reference binding"));
        }
      }
    } finally {
      reference.close();
    }
    var restored = ClientExample.settings().screen(parent, Component.literal("Restored JSON"));
    restored.removed();
    check(
        "JSON factory still admits a view after repeated constructor refusals",
        ClientExample.settings().status().lifecycle()
            == design.inherent.reform.neoforge.client.ClientJsonSettings.Lifecycle.READY);
  }

  private CompletionStage<SettingsCommands.Reply> command(String input) {
    waiting = new CompletableFuture<>();
    var result = waiting;
    try {
      check("native factory command accepted " + input, dispatcher.execute(input, source) == 1);
    } catch (Throwable failed) {
      result.completeExceptionally(failed);
    }
    return result.minimalCompletionStage();
  }

  private void reply(SettingsCommands.Reply reply) {
    check("factory command feedback stays on native executor", client.isSameThread());
    if (reply.kind() == SettingsCommands.Kind.QUEUED) return;
    var target = waiting;
    waiting = null;
    if (target == null) throw new AssertionError("Unmatched native factory reply");
    target.complete(reply);
  }

  private Object result(SettingsCommands.Reply reply) {
    var operation = reply.operation().orElseThrow();
    check(
        "factory command identifies actual native document",
        operation.target().orElseThrow().equals(KeyExample.DOCUMENT));
    return operation.result().orElseThrow();
  }

  private CompletableFuture<Void> commands() {
    NeoForge.EVENT_BUS.post(
        new RegisterClientCommandsEvent(
            dispatcher,
            CommandBuildContext.simple(RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS)));
    check(
        "ordinary key factory registers native commands",
        dispatcher.getRoot().getChild("reformkeys") != null);
    dispatcher.register(KeyExample.settings().commandBranch("keyfactoryprobe", this::reply));
    return command("keyfactoryprobe describe")
        .thenCompose(
            reply -> {
              var description = (DocumentActions.Description) result(reply);
              check(
                  "native key definition exposes both existing mappings",
                  description.fields().size() == 4);
              String selector =
                  description.fields().stream()
                      .filter(field -> field.path().equals(primary.field("key")))
                      .findFirst()
                      .orElseThrow()
                      .selector();
              String quoted = StringArgumentType.escapeIfRequired(selector);
              return command("keyfactoryprobe get " + quoted)
                  .thenCompose(
                      observed -> {
                        var inspection = (EditorActor.Inspection<?>) result(observed);
                        check(
                            "cold native commands observe GUI-saved binding",
                            ((DraftNode.Scalar)
                                    inspection.snapshot().draft().at(primary.field("key")))
                                .raw()
                                .equals("key.keyboard.4"));
                        return command("keyfactoryprobe set " + quoted + " key.keyboard.5");
                      });
            })
        .thenCompose(
            reply -> {
              var view = (EditorActor.View<?>) result(reply);
              check(
                  "native key command stages only its own draft",
                  view.dirty()
                      && KeyExample.PRIMARY
                          .getKey()
                          .equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_4)));
              return command("keyfactoryprobe save");
            })
        .thenAccept(
            reply -> {
              var saved = (EditorActor.Commit<?>) result(reply);
              check(
                  "native command Save uses actual Options writer",
                  saved.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED
                      && KeyExample.PRIMARY
                          .getKey()
                          .equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_5)));
            })
        .toCompletableFuture();
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (KeyExample.settings().status().lifecycle() == ClientKeyBindings.Lifecycle.UNAVAILABLE)
          throw new AssertionError(
              "Ordinary native factory unavailable: " + KeyExample.settings().status().report());
        if (KeyExample.settings().status().lifecycle() != ClientKeyBindings.Lifecycle.READY
            || unavailable.status().lifecycle() != ClientKeyBindings.Lifecycle.UNAVAILABLE)
          return false;
        options = client.options.getFile().toPath();
        check(
            "actual mapping provider runs once after native registration",
            KeyExample.selections() == 1);
        check(
            "failed native registration has no substitute definition",
            unavailable.definition().isEmpty());
        check("failed native provider is not polled", failedSelections.get() == 1);
        check(
            "failed binding performs no native write",
            Arrays.equals(failedSource.get(), Files.readAllBytes(options)));
        var failed = unavailable.screen(parent, Component.literal("Unavailable native keys"));
        client.setScreen(failed);
        check(
            "native setup error is accessible",
            failed.getNarrationMessage().getString().contains("could not be bound"));
        press(button(failed, "Back"));
        check(
            "unavailable setup view returns without retrying",
            client.screen == parent && failedSelections.get() == 1);
        if (!reopen) {
          failedNativeViews();
          failedJsonViews();
        }
        before = Files.readAllBytes(options);
        form = (SettingsScreen<?>) KeyExample.screen(parent);
        client.setScreen(form);
        next("ordinary native key factory opens shared form");
      }
      case 1 -> {
        if (!ready()) return false;
        if (reopen) {
          check(
              "ordinary factory reopens native command-saved value",
              raw().equals("key.keyboard.5")
                  && KeyExample.PRIMARY
                      .getKey()
                      .equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_5)));
          press(button(form, "Done"));
          complete = true;
          return true;
        }
        check(
            "factory exposes actual admitted definition",
            KeyExample.settings().definition().orElseThrow().id().equals(KeyExample.DOCUMENT));
        check("generated native control is available", form.focusField(primary));
        press((Button) form.fieldControl(primary).orElseThrow());
        var popup = (KeyBindingScreen) client.screen;
        press((Button) popup.control(KeyBindingScreen.Control.CAPTURE).orElseThrow());
        popup.keyPressed(GLFW.GLFW_KEY_4, 0, 0);
        popup.keyReleased(GLFW.GLFW_KEY_4, 0, 0);
        press((Button) popup.control(KeyBindingScreen.Control.APPLY).orElseThrow());
        next("ordinary native factory stages canonical digit binding");
      }
      case 2 -> {
        if (!ready() || !raw().equals("key.keyboard.4")) return false;
        check(
            "ordinary factory draft leaves file untouched",
            Arrays.equals(before, Files.readAllBytes(options)));
        press(button(form, "Save and apply"));
        next("ordinary native factory saves through Options");
      }
      case 3 -> {
        if (!ready() || form.editorView().dirty() || form.editorView().lastReceipt().isEmpty())
          return false;
        check(
            "ordinary native factory applied its binding",
            KeyExample.PRIMARY
                .getKey()
                .equals(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_4)));
        press(button(form, "Done"));
        commands = commands();
        next("ordinary native factory command bridge entered");
      }
      case 4 -> {
        if (!commands.isDone()) return false;
        commands.join();
        check(
            "native factory creates no preference mirror",
            Files.notExists(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_native_example/native_keys.json")));
        check("menu and commands reuse one native provider", KeyExample.selections() == 1);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown native key factory stage " + stage);
    }
    return false;
  }
}
