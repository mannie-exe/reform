package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.FieldEditors;
import design.inherent.reform.neoforge.client.KeyBindingOptions;
import design.inherent.reform.neoforge.client.KeyBindingScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import example.reform.KeyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Native public auxiliary controls over actual Options; no private library access. */
final class NativeKeyControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final FieldPath primary = FieldPath.ROOT.field(KeyExample.PRIMARY.getName());
  private final FieldPath secondary = FieldPath.ROOT.field(KeyExample.SECONDARY.getName());
  private OwnerContext context;
  private KeyBindingOptions owner;
  private KeyBindingOptions.Session session;
  private DocumentActions<Map<String, KeyBindingOptions.Binding>> actions;
  private SettingsScreen<Map<String, KeyBindingOptions.Binding>> form;
  private Path path;
  private byte[] original;
  private Button retired;
  private OperationHandle<?> edit;
  private CompletableFuture<KeyBindingOptions.Conflicts> conflictResult;
  private KeyBindingOptions tightOwner;
  private int stage, rawPhase;
  private boolean complete;

  NativeKeyControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    report.add("checks", checks);
    report.addProperty("status", "RUNNING");
  }

  JsonObject report() {
    report.addProperty("contextClosed", context != null && !context.isOpen());
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    if (!report.has("popupNarration") && client.screen instanceof KeyBindingScreen popup) {
      report.addProperty("popupNarration", popup.getNarrationMessage().getString());
      report.addProperty(
          "popupProposed", popup.proposedBinding().map(Object::toString).orElse("absent"));
      report.addProperty(
          "popupRefreshEnabled",
          popup.control(KeyBindingScreen.Control.REFRESH).orElseThrow().active);
    }
    if (actions != null && actions.view().snapshot().isPresent()) {
      report.addProperty("editorAvailability", actions.view().availability().name());
      report.addProperty("primaryKey", raw(primary.field("key")));
      report.addProperty("primaryModifier", raw(primary.field("modifier")));
    }
    return report;
  }

  void shutdown() {
    if (context != null && context.isOpen()) context.close();
  }

  private void check(String name, boolean value) {
    if (!value) throw new AssertionError(name);
    checks.add(name);
  }

  private void step(String label) {
    stage++;
    progress.accept(label);
  }

  private boolean ready() {
    return client.screen == form
        && actions.view().availability() == EditorActor.Availability.READY
        && actions.view().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private boolean edited() {
    if (edit == null || !edit.snapshot().terminal()) return false;
    check("shared operation accepted native draft change", edit.snapshot().result().isPresent());
    edit = null;
    return true;
  }

  private KeyBindingScreen popup() {
    return (KeyBindingScreen) client.screen;
  }

  private Button control(KeyBindingScreen.Control control) {
    return (Button) popup().control(control).orElseThrow();
  }

  private void press(Button button) {
    check("native binding control participates in focus", focus(client.screen, button));
    click(client.screen, button);
  }

  private void open() {
    check("generated custom field can be revealed", form.focusField(primary));
    press((Button) form.fieldControl(primary).orElseThrow());
    check(
        "generated form opens public native key screen", client.screen instanceof KeyBindingScreen);
  }

  private KeyBindingOptions.Binding binding(int code, KeyModifier modifier) {
    return new KeyBindingOptions.Binding(
        InputConstants.Type.KEYSYM.getOrCreate(code).getName(), modifier);
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) actions.view().snapshot().orElseThrow().draft().at(path)).raw();
  }

  private void local(KeyBindingOptions.Binding expected) {
    check(
        "captured local native binding is exact",
        popup().proposedBinding().orElseThrow().equals(expected));
  }

  private void unchanged() throws Exception {
    check(
        "capture and draft edits leave actual mapping untouched",
        KeyExample.PRIMARY.getKey().equals(KeyExample.PRIMARY.getDefaultKey())
            && KeyExample.PRIMARY.getKeyModifier() == KeyModifier.NONE);
    check(
        "capture and draft edits leave native file byte-equal",
        Arrays.equals(original, Files.readAllBytes(path)));
  }

  private void type(int key) {
    check("capture consumes native key press", popup().keyPressed(key, 0, 0));
    check("capture consumes native key release", popup().keyReleased(key, 0, 0));
  }

  private void chooseMouse() {
    press(control(KeyBindingScreen.Control.CAPTURE));
    check("capture consumes mouse input", popup().mouseClicked(2, 2, 3));
    check("mouse selection does not wait for keyboard release", !popup().capturing());
    check(
        "mouse key is native canonical input",
        popup()
            .proposedBinding()
            .orElseThrow()
            .equals(
                new KeyBindingOptions.Binding(
                    InputConstants.Type.MOUSE.getOrCreate(3).getName(), KeyModifier.NONE)));
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!reopen) {
          // An earlier runner mode may have changed its own shared disposable Options.
          KeyExample.PRIMARY.setToDefault();
          KeyExample.SECONDARY.setToDefault();
          KeyMapping.resetMapping();
          client.options.save();
        }
        context =
            new OwnerContext(
                client::execute,
                Util.ioPool(),
                () -> {
                  if (!client.isSameThread())
                    throw new AssertionError("Native key form escaped its owner");
                },
                OwnerContext.Limits.STANDARD);
        var access = context.access(() -> true, () -> true);
        owner =
            new KeyBindingOptions(
                context,
                client.options,
                DocumentId.parse("reform_native_example:native_keys"),
                List.of(KeyExample.PRIMARY, KeyExample.SECONDARY));
        session = owner.session(access);
        var actor =
            new EditorActor<>(
                context,
                session,
                owner.definition(),
                owner.jsonLimits(),
                DraftLimits.CONFIGURATION,
                32);
        actions = new DocumentActions<>(context, access, actor);
        form = KeyExample.screen(parent, session, actions);
        path = client.options.getFile().toPath();
        original = Files.readAllBytes(path);
        client.setScreen(form);
        step("native key form opened through public addon");
      }
      case 1 -> {
        if (!ready()) return false;
        if (reopen) {
          check(
              "native mouse binding survives second JVM",
              KeyExample.PRIMARY.getKey().equals(InputConstants.Type.MOUSE.getOrCreate(3))
                  && KeyExample.PRIMARY.getKeyModifier() == KeyModifier.NONE);
          check("native sibling keeps registered key", KeyExample.SECONDARY.isDefault());
          check(
              "new generated form observes actual saved binding",
              raw(primary.field("key")).equals(KeyExample.PRIMARY.getKey().getName()));
          button(form, "Done").onPress();
          client.setScreen(new KeyBindsScreen(parent, client.options));
          check(
              "saved binding appears in normal native Controls",
              widgets(client.screen).stream()
                  .anyMatch(
                      widget ->
                          widget
                              .getMessage()
                              .getString()
                              .equals(KeyExample.PRIMARY.getTranslatedKeyMessage().getString())));
          client.setScreen(parent);
          complete = true;
          return true;
        }
        if (rawPhase == 0) {
          press(button(form, "Show fields"));
          var input = (EditBox) form.fieldControl(primary.field("key")).orElseThrow();
          check("raw fallback field supports focus", form.focusField(primary.field("key")));
          input.moveCursorToEnd(false);
          input.setHighlightPos(input.getValue().length() - 1);
          check("raw fallback receives native input", form.charTyped('h', 0));
          var hide = button(form, "Hide fields");
          form.focusField(primary);
          form.mouseClicked(
              hide.getX() + hide.getWidth() / 2.0, hide.getY() + hide.getHeight() / 2.0, 0);
          form.mouseReleased(hide.getX() + 1, hide.getY() + 1, 0);
          check(
              "pending raw controls remain visible rather than discarding input",
              form.fieldControl(primary.field("key")).isPresent());
          rawPhase = 1;
          return false;
        }
        if (rawPhase == 1) {
          if (!raw(primary.field("key")).equals("key.keyboard.h")) return false;
          check("raw fallback shares actual draft service", actions.view().dirty());
          press(button(form, "Undo"));
          rawPhase = 2;
          return false;
        }
        if (rawPhase == 2) {
          if (!raw(primary.field("key")).equals("key.keyboard.g")) return false;
          press(button(form, "Hide fields"));
          check(
              "settled raw fields may be hidden without draft loss",
              form.fieldControl(primary.field("key")).isEmpty());
          rawPhase = 3;
        }
        unchanged();
        try {
          FieldEditors.builder(owner.definition())
              .field(FieldPath.ROOT.field("unknown"), request -> parent);
          throw new AssertionError("Undeclared auxiliary field registered");
        } catch (IllegalArgumentException expected) {
          checks.add("undeclared auxiliary path refuses");
        }
        var registry = FieldEditors.builder(owner.definition()).field(primary, request -> parent);
        try {
          registry.field(primary, request -> parent);
          throw new AssertionError("Duplicate auxiliary field registered");
        } catch (IllegalArgumentException expected) {
          checks.add("duplicate auxiliary path refuses");
        }
        open();
        step("native capture opens over actual shared draft");
      }
      case 2 -> {
        popup().tick();
        if (popup().conflictReview().isEmpty()) {
          if (control(KeyBindingScreen.Control.REFRESH).active)
            throw new AssertionError(
                "Native conflict review unavailable: " + popup().getNarrationMessage().getString());
          return false;
        }
        var expected =
            Arrays.stream(client.options.keyMappings)
                .filter(
                    other ->
                        other != KeyExample.PRIMARY
                            && (KeyExample.PRIMARY.same(other)
                                || other.hasKeyModifierConflict(KeyExample.PRIMARY)))
                .map(KeyMapping::getName)
                .toList();
        check(
            "unchanged conflict review uses actual native methods",
            popup().conflictReview().orElseThrow().nativeCurrent()
                && expected.equals(popup().conflictReview().orElseThrow().mappings()));
        press(control(KeyBindingScreen.Control.CAPTURE));
        popup().keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0);
        popup().keyPressed(GLFW.GLFW_KEY_H, 0, 0);
        popup().keyPressed(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0);
        check("capture consumes character before generic shortcuts", popup().charTyped('h', 0));
        popup().keyReleased(GLFW.GLFW_KEY_H, 0, 0);
        check("modifier capture waits for final held release", popup().capturing());
        popup().keyReleased(GLFW.GLFW_KEY_LEFT_SHIFT, 0, 0);
        local(binding(GLFW.GLFW_KEY_H, KeyModifier.SHIFT));
        unchanged();
        press(control(KeyBindingScreen.Control.APPLY));
        step("native compound binding applied to shared draft");
      }
      case 3 -> {
        if (!ready() || !raw(primary.field("key")).equals("key.keyboard.h")) return false;
        check(
            "key and modifier enter shared draft together",
            raw(primary.field("modifier")).equals("SHIFT"));
        unchanged();
        press(button(form, "Undo"));
        step("compound capture undone once");
      }
      case 4 -> {
        if (!ready() || !raw(primary.field("key")).equals("key.keyboard.g")) return false;
        check(
            "one undo restores complete native binding",
            raw(primary.field("modifier")).equals("NONE") && !actions.view().canUndo());
        press(button(form, "Redo"));
        step("compound capture redone once");
      }
      case 5 -> {
        if (!ready() || !raw(primary.field("modifier")).equals("SHIFT")) return false;
        open();
        step("native reset and local cancellation reviewed");
      }
      case 6 -> {
        if (popup().conflictReview().isEmpty()) return false;
        check(
            "draft conflict includes actual sibling and reports projection",
            !popup().conflictReview().orElseThrow().nativeCurrent()
                && popup()
                    .conflictReview()
                    .orElseThrow()
                    .mappings()
                    .contains(KeyExample.SECONDARY.getName()));
        press(control(KeyBindingScreen.Control.CAPTURE));
        type(GLFW.GLFW_KEY_LEFT_SHIFT);
        local(binding(GLFW.GLFW_KEY_LEFT_SHIFT, KeyModifier.NONE));
        press(control(KeyBindingScreen.Control.CAPTURE));
        int modifier = Minecraft.ON_OSX ? GLFW.GLFW_KEY_LEFT_SUPER : GLFW.GLFW_KEY_LEFT_CONTROL;
        popup().keyPressed(modifier, 0, 0);
        popup().keyPressed(GLFW.GLFW_KEY_J, 0, 0);
        popup().keyReleased(GLFW.GLFW_KEY_J, 0, 0);
        popup().keyReleased(modifier, 0, 0);
        local(binding(GLFW.GLFW_KEY_J, KeyModifier.CONTROL));
        press(control(KeyBindingScreen.Control.CAPTURE));
        type(GLFW.GLFW_KEY_ESCAPE);
        check(
            "Escape unbinds without closing capture screen",
            client.screen instanceof KeyBindingScreen
                && popup()
                    .proposedBinding()
                    .orElseThrow()
                    .key()
                    .equals(InputConstants.UNKNOWN.getName()));
        press(control(KeyBindingScreen.Control.RESET));
        local(binding(GLFW.GLFW_KEY_G, KeyModifier.NONE));
        press(control(KeyBindingScreen.Control.CAPTURE));
        if (Minecraft.ON_OSX) {
          popup().keyReleased(GLFW.GLFW_KEY_UNKNOWN, 63, 0);
          check("macOS release-only Fn event retains capture", popup().capturing());
        }
        press(control(KeyBindingScreen.Control.CAPTURE));
        local(binding(GLFW.GLFW_KEY_G, KeyModifier.NONE));
        unchanged();
        press(control(KeyBindingScreen.Control.BACK));
        step("local capture cancellation leaves shared draft untouched");
      }
      case 7 -> {
        if (!ready()) return false;
        check(
            "Back discards local reset and keeps earlier shared capture",
            raw(primary.field("key")).equals("key.keyboard.h")
                && raw(primary.field("modifier")).equals("SHIFT"));
        open();
        edit =
            actions.edit(
                EntryPoint.GUI,
                new DraftEdit(
                    actions.view().snapshot().orElseThrow().target(secondary.field("key")),
                    new DraftEdit.SetRaw("key.keyboard.j")));
        step("native popup exact target made stale by another draft edit");
      }
      case 8 -> {
        if (!edited()) return false;
        popup().tick();
        check(
            "stale popup cannot apply local binding",
            !control(KeyBindingScreen.Control.APPLY).active);
        check(
            "stale context reason is narrated",
            popup().getNarrationMessage().getString().contains("changed"));
        retired = control(KeyBindingScreen.Control.APPLY);
        press(control(KeyBindingScreen.Control.BACK));
        retired.onPress();
        check("retired native editor callback cannot act", client.screen == form);
        edit =
            actions.edit(
                EntryPoint.GUI,
                new DraftEdit(
                    actions.view().snapshot().orElseThrow().target(primary.field("modifier")),
                    new DraftEdit.SetRaw("INVALID")));
        step("invalid native input preserved for explicit repair");
      }
      case 9 -> {
        if (!edited() || !ready()) return false;
        check("invalid shared native draft disables Save", !button(form, "Save and apply").active);
        open();
        check(
            "invalid native input has no invented prospective binding",
            popup().proposedBinding().isEmpty()
                && raw(primary.field("modifier")).equals("INVALID"));
        press(control(KeyBindingScreen.Control.RESET));
        local(binding(GLFW.GLFW_KEY_G, KeyModifier.NONE));
        press(control(KeyBindingScreen.Control.APPLY));
        step("native reset repairs invalid scalar pair through shared edit");
      }
      case 10 -> {
        if (!ready() || !raw(primary.field("modifier")).equals("NONE")) return false;
        check(
            "reset retains independent sibling draft",
            raw(secondary.field("key")).equals("key.keyboard.j"));
        open();
        press(control(KeyBindingScreen.Control.CAPTURE));
        type(GLFW.GLFW_KEY_J);
        step("draft conflicts account for another unsaved binding");
      }
      case 11 -> {
        if (popup().conflictReview().isEmpty()) return false;
        check(
            "unsaved sibling binding participates in conflict review",
            popup()
                .conflictReview()
                .orElseThrow()
                .mappings()
                .contains(KeyExample.SECONDARY.getName()));
        retired = control(KeyBindingScreen.Control.CAPTURE);
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        step("native key controls narrowed");
      }
      case 12 -> {
        if (popup().width > 340) return false;
        geometry(popup());
        report.addProperty("narrowWidth", popup().width);
        report.addProperty("narrowHeight", popup().height);
        local(binding(GLFW.GLFW_KEY_J, KeyModifier.NONE));
        retired.onPress();
        check("retired resized capture control cannot act", !popup().capturing());
        popup().keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        check("key editor supports native keyboard focus", popup().getFocused() != null);
        chooseMouse();
        capture.accept(
            "form-native-key-narrow.png", () -> press(control(KeyBindingScreen.Control.APPLY)));
        step("native mouse selection retained across narrow screen");
      }
      case 13 -> {
        if (!ready()
            || !raw(primary.field("key"))
                .equals(InputConstants.Type.MOUSE.getOrCreate(3).getName())) return false;
        edit =
            actions.edit(
                EntryPoint.GUI,
                new DraftEdit(
                    actions.view().snapshot().orElseThrow().target(secondary),
                    new DraftEdit.SetRecordScalars(
                        Map.of("key", "key.keyboard.h", "modifier", "NONE"))));
        step("independent native sibling restored as draft");
      }
      case 14 -> {
        if (!edited() || !ready()) return false;
        unchanged();
        tightOwner =
            new KeyBindingOptions(
                context,
                client.options,
                DocumentId.parse("reform_native_example:tight_keys"),
                List.of(KeyExample.PRIMARY, KeyExample.SECONDARY),
                new KeyBindingOptions.Limits(262144, 2, 2, 2, 1));
        var tight = tightOwner.session(context.access(() -> true, () -> false));
        conflictResult =
            tight
                .conflicts(
                    KeyExample.PRIMARY.getName(),
                    binding(GLFW.GLFW_KEY_G, KeyModifier.NONE),
                    actions.view().snapshot().orElseThrow().draft())
                .toCompletableFuture();
        step("native conflict budget refusal requested");
      }
      case 15 -> {
        if (!conflictResult.isDone()) return false;
        try {
          conflictResult.join();
          throw new AssertionError("Exhausted native conflict budget returned a result");
        } catch (java.util.concurrent.CompletionException failed) {
          Throwable cause = failed;
          while (cause instanceof java.util.concurrent.CompletionException
              && cause.getCause() != null) cause = cause.getCause();
          check(
              "exhausted native conflict budget is unavailable rather than zero",
              cause instanceof design.inherent.reform.owner.PolicyRefusal refused
                  && refused.report().issues().stream()
                      .anyMatch(issue -> issue.code().equals("keys.conflict_budget")));
        }
        tightOwner.close();
        press(button(form, "Save and apply"));
        step("native form commits through actual Options serializer");
      }
      case 16 -> {
        if (!ready() || actions.view().dirty() || actions.view().lastReceipt().isEmpty())
          return false;
        check(
            "native Save publishes canonical mouse binding",
            KeyExample.PRIMARY.getKey().equals(InputConstants.Type.MOUSE.getOrCreate(3))
                && KeyExample.PRIMARY.getKeyModifier() == KeyModifier.NONE);
        KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(3));
        check("native map dispatch sees saved key", KeyExample.PRIMARY.consumeClick());
        check(
            "native serializer persists binding",
            Files.readString(path)
                .contains("key_" + KeyExample.PRIMARY.getName() + ":key.mouse.4"));
        check(
            "native owner does not create a JSON preference copy",
            Files.notExists(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_native_example/native_keys.json")));
        press(button(form, "Done"));
        check(
            "normal form close releases actor session",
            !session.isOpen() && client.screen == parent);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown key controls stage " + stage);
    }
    return false;
  }
}
