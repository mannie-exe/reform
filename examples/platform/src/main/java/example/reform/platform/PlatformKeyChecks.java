package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientKeyBindings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.KeyBindingOptions;
import design.inherent.reform.minecraft.client.KeyBindingScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.FieldIndex;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real registered Controls entries, native input dispatch and owner-backed options.txt saving. */
final class PlatformKeyChecks {
  private static final KeyMapping primary =
      NativeClientProbe.mapping("key.reform_platform.primary");
  private static final KeyMapping secondary =
      NativeClientProbe.mapping("key.reform_platform.secondary");
  private static final FieldPath FIELD = FieldPath.ROOT.field(primary.getName());
  private static final String MOUSE = InputConstants.getKey("key.mouse.4").getName();
  private static ClientRegistration registration;
  private static ClientKeyBindings settings;

  static List<KeyMapping> register(ClientRegistration client) {
    registration = client;
    settings =
        ClientKeyBindings.clientKeys(
            client,
            DocumentId.parse(PlatformServer.ID + ":keys"),
            () -> List.of(primary, secondary));
    if (settings.status().lifecycle() != ClientKeyBindings.Lifecycle.REGISTERED
        || settings.definition().isPresent())
      throw new AssertionError("Native keys bound before registration/setup");
    return List.of(primary, secondary);
  }

  static CompletionStage<JsonObject> run(boolean reopen) {
    var check = new Check(reopen);
    check.subscription = registration.onTick(check::tick);
    return check.result.minimalCompletionStage();
  }

  private static final class Check {
    private final Minecraft client = Minecraft.getInstance();
    private final Screen parent = NativeScreens.current(client);
    private final boolean reopen;
    private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    private final JsonArray checks = new JsonArray();
    private ClientRegistration.Subscription subscription;
    private SettingsScreen<?> form;
    private CompletableFuture<?> pending;
    private String before, other;
    private int stage, ticks;

    private Check(boolean reopen) {
      this.reopen = reopen;
    }

    private void check(String name, boolean value) {
      if (!value) throw new AssertionError(name);
      checks.add(name);
    }

    private void press(Screen screen, AbstractWidget control) {
      check("native control is enabled", control.active);
      check("native pointer event reaches control", NativeClientProbe.click(screen, control));
    }

    private void press(KeyBindingScreen popup, KeyBindingScreen.Control control) {
      press(popup, popup.control(control).orElseThrow());
    }

    private void tick() {
      try {
        if (Files.exists(Path.of("platform-cancel")))
          throw new AssertionError("Native key fixture cancelled");
        if (++ticks > 600)
          throw new AssertionError("Native key fixture exceeded tick budget at stage " + stage);
        var current = NativeScreens.current(client);
        switch (stage) {
          case 0 -> {
            check(
                "registered native key factory is ready",
                settings.status().lifecycle() == ClientKeyBindings.Lifecycle.READY);
            before = primary.saveString();
            other = secondary.saveString();
            check("native options retains saved reopen", !reopen || before.equals(MOUSE));
            NativeScreens.open(client, settings.screen(parent, Component.literal("Native keys")));
            stage++;
          }
          case 1 -> {
            if (!(current instanceof SettingsScreen<?> screen)
                || screen.editorView().availability() != EditorActor.Availability.READY
                || screen.editorView().snapshot().isEmpty()) return;
            form = screen;
            // An actor view can precede the screen's queued widget rebuild.
            if (!form.focusField(FIELD)) return;
            check(
                "generated native key field is focusable",
                form.fieldControl(FIELD).orElseThrow().active);
            press(form, form.fieldControl(FIELD).orElseThrow());
            check(
                "field opens actual key capture screen",
                NativeScreens.current(client) instanceof KeyBindingScreen);
            stage++;
          }
          case 2 -> {
            var popup = (KeyBindingScreen) current;
            press(popup, KeyBindingScreen.Control.RESET);
            check(
                "reset proposes actual native default",
                popup
                    .proposedBinding()
                    .orElseThrow()
                    .key()
                    .equals(primary.getDefaultKey().getName()));
            press(popup, KeyBindingScreen.Control.UNBIND);
            check(
                "unbind proposes native unknown without saving",
                popup.proposedBinding().orElseThrow().key().equals(InputConstants.UNKNOWN.getName())
                    && primary.saveString().equals(before));
            press(popup, KeyBindingScreen.Control.CAPTURE);
            check("keyboard capture enters explicit state", popup.capturing());
            NativeClientProbe.key(popup, "key.keyboard.k", true);
            NativeClientProbe.key(popup, "key.keyboard.k", false);
            check(
                "native keyboard press/release proposes exact key",
                !popup.capturing()
                    && popup
                        .proposedBinding()
                        .orElseThrow()
                        .key()
                        .equals(InputConstants.getKey("key.keyboard.k").getName())
                    && popup.proposedBinding().orElseThrow().modifier()
                        == KeyBindingOptions.Modifier.NONE);
            press(popup, KeyBindingScreen.Control.CAPTURE);
            NativeClientProbe.key(popup, "key.keyboard.left.shift", true);
            NativeClientProbe.key(popup, "key.keyboard.k", true);
            NativeClientProbe.key(popup, "key.keyboard.k", false);
            NativeClientProbe.key(popup, "key.keyboard.left.shift", false);
            check(
                "native chord capture respects actual loader capability",
                !popup.capturing()
                    && popup.proposedBinding().orElseThrow().modifier()
                        == (KeyBindingOptions.supportedModifiers()
                                .contains(KeyBindingOptions.Modifier.SHIFT)
                            ? KeyBindingOptions.Modifier.SHIFT
                            : KeyBindingOptions.Modifier.NONE));
            press(popup, KeyBindingScreen.Control.CAPTURE);
            NativeClientProbe.mouse(popup, InputConstants.getKey(MOUSE).getValue());
            check(
                "native mouse capture preserves native button identity",
                !popup.capturing() && popup.proposedBinding().orElseThrow().key().equals(MOUSE));
            check("capture does not mutate actual mapping", primary.saveString().equals(before));
            press(popup, KeyBindingScreen.Control.APPLY);
            stage++;
          }
          case 3 -> {
            if (current != form) return;
            var value =
                (DraftNode.Scalar)
                    form.editorView().snapshot().orElseThrow().draft().at(FIELD.field("key"));
            if (!value.raw().equals(MOUSE)) return;
            check("applied capture changes draft only", primary.saveString().equals(before));
            pending =
                DocumentActions.result(form.editingActions().commit(EntryPoint.GUI))
                    .toCompletableFuture();
            stage++;
          }
          case 4 -> {
            if (!pending.isDone()) return;
            var receipt = ((EditorActor.Commit<?>) pending.join()).receipt().orElseThrow();
            check(
                "actual options commit has exact persistence outcome",
                receipt.persistence()
                    == (reopen
                        ? CommitReceipt.Persistence.UNCHANGED
                        : CommitReceipt.Persistence.SAVED));
            check("native options mapping changed", primary.saveString().equals(MOUSE));
            check("unselected native mapping is preserved", secondary.saveString().equals(other));
            pending =
                DocumentActions.result(
                        form.editingActions()
                            .edit(
                                EntryPoint.GUI,
                                new FieldIndex(form.editingActions().definition().type(), 256, 8)
                                    .field(FIELD.field("modifier"))
                                    .orElseThrow()
                                    .selector(),
                                "SHIFT"))
                    .toCompletableFuture();
            stage++;
          }
          case 5 -> {
            if (!pending.isDone()) return;
            pending.join();
            pending =
                DocumentActions.result(form.editingActions().preflight(EntryPoint.GUI))
                    .toCompletableFuture();
            stage++;
          }
          case 6 -> {
            if (!pending.isDone()) return;
            var preflight = (EditorActor.Preflight<?>) pending.join();
            boolean supported =
                KeyBindingOptions.supportedModifiers().contains(KeyBindingOptions.Modifier.SHIFT);
            check(
                "actual loader declares modifier support", preflight.report().valid() == supported);
            if (!supported)
              check(
                  "unsupported native modifier has field diagnostic",
                  preflight.report().issues().stream()
                      .anyMatch(
                          issue ->
                              issue.code().equals("keys.unsupported_modifier")
                                  && issue.path().equals(FIELD.field("modifier"))));
            check("backend preflight does not mutate mapping", primary.saveString().equals(MOUSE));
            pending =
                DocumentActions.result(form.editingActions().revert(EntryPoint.GUI))
                    .toCompletableFuture();
            stage++;
          }
          case 7 -> {
            if (!pending.isDone()) return;
            pending.join();
            check("explicit revert discards unsaved modifier", !form.editorView().dirty());
            form.onClose();
            check(
                "closing native editor returns to parent", NativeScreens.current(client) == parent);
            var report = new JsonObject();
            report.addProperty("status", "PASS");
            report.add("checks", checks);
            report.addProperty("savedKey", primary.saveString());
            report.addProperty(
                "modifierSupport",
                KeyBindingOptions.supportedModifiers().contains(KeyBindingOptions.Modifier.SHIFT));
            report.addProperty("ticks", ticks);
            subscription.close();
            result.complete(report);
          }
          default -> throw new AssertionError("Unknown native key stage");
        }
      } catch (Exception | AssertionError failed) {
        subscription.close();
        result.completeExceptionally(failed);
      }
    }
  }

  private PlatformKeyChecks() {}
}
