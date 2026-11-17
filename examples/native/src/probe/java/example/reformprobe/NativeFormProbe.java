package example.reformprobe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
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
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Real widgets and owner/file roundtrips; no reflection into private library state. */
@Mod(value = "reform_native_example_probe", dist = Dist.CLIENT)
public final class NativeFormProbe {
  private final Minecraft mc = Minecraft.getInstance();
  private final JsonObject report = new JsonObject();
  private final JsonArray controls = new JsonArray();
  private int stage, ticks, stageTick;
  private boolean finished;
  private Screen parent, form;
  private EditorActor<NativeExample.Preferences> actor;
  private EditBox distance, label;
  private String savedBytes, capture;
  private Runnable afterCapture;
  private int cursor;
  private String selected;
  private final String mode;

  public NativeFormProbe() {
    try {
      var modeFile = mc.gameDirectory.toPath().resolve("native-form-mode.txt");
      if (Files.size(modeFile) > 16) throw new IllegalStateException("Invalid form fixture mode");
      mode = Files.readString(modeFile).strip();
      if (!List.of("first", "reopen").contains(mode))
        throw new IllegalStateException("Unknown form fixture mode");
    } catch (java.io.IOException unavailable) {
      throw new IllegalStateException("Missing disposable fixture mode", unavailable);
    }
    report.addProperty("status", "RUNNING");
    report.addProperty("mode", mode);
    report.add("controls", controls);
    NeoForge.EVENT_BUS.addListener(this::tick);
    NeoForge.EVENT_BUS.addListener(this::rendered);
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
      if (ticks > 2400 || ticks - stageTick > 600)
        throw new AssertionError("Native form stage timed out: " + stage);
      if (mc.getOverlay() != null) return;
      if (capture != null) return;
      if (afterCapture != null) {
        var action = afterCapture;
        afterCapture = null;
        action.run();
        return;
      }
      switch (stage) {
        case 0 -> {
          if (!(mc.screen instanceof TitleScreen) || !ClientExample.INSTANCE.initialized()) return;
          var artifact = ModList.get().getModFileById("reform").getFile().getFilePath();
          report.addProperty("libraryPath", artifact.toString());
          report.addProperty(
              "librarySha256",
              HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
          report.addProperty(
              "libraryCodeSource",
              design.inherent.reform.neoforge.client.SettingsScreen.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toString());
          parent = mc.screen;
          open();
          next("packaged library loaded; actual registered config factory opened");
        }
        case 1 -> {
          if (actor.view().snapshot().isEmpty()) return;
          distance = input(form, "Distance");
          label = input(form, "Label");
          if (mode.equals("reopen")) {
            check(
                distance.getValue().equals("128") && label.getValue().equals("Example"),
                "Second JVM did not retain saved preferences");
            check(
                actor.view().validation().valid() && !actor.view().dirty(),
                "Second JVM has an invalid/dirty baseline");
            form.onClose();
            next("actual saved file and effective values reopen in a second JVM");
            finish(null);
            return;
          }
          check(
              distance.getValue().equals("64") && label.getValue().equals("Example"),
              "Initial factory values");
          check(
              !actor.view().dirty() && actor.view().validation().valid(),
              "Initial editor observation");
          savedBytes = Files.readString(path());
          geometry(form);
          capture = "form-initial.png";
          afterCapture = () -> replace(distance, "-");
          next("native defaults/field controls/geometry");
        }
        case 2 -> {
          if (actor.view().validation().valid()) return;
          check(
              distance.getValue().equals("-") && raw("distance").equals("-"),
              "Invalid raw draft retained");
          check(!button(form, "Save and apply").active, "Invalid submission disabled");
          check(Files.readString(path()).equals(savedBytes), "Invalid typing wrote the file");
          var diagnostic = actor.view().validation().issues().getFirst().fallback();
          check(
              form.getNarrationMessage().getString().contains(diagnostic),
              "Invalid diagnostic narration");
          capture = "form-invalid.png";
          afterCapture = () -> replace(distance, "128");
          next("invalid native input retained; complete validation blocks saving");
        }
        case 3 -> {
          if (!actor.view().validation().valid()
              || !raw("distance").equals("128")
              || !button(form, "Save and apply").active) return;
          click(button(form, "Save and apply"));
          next("native save control invoked");
        }
        case 4 -> {
          if (actor.view().lastReceipt().isEmpty()
              || actor.view().pending().isPresent()
              || actor.view().dirty()) return;
          var receipt = actor.view().lastReceipt().orElseThrow();
          check(
              receipt.persistence() == JsonOwner.Persistence.SAVED
                  && receipt.application() == JsonOwner.Application.APPLIED,
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
          check(mc.screen instanceof ConfirmScreen, "Dirty close did not request a decision");
          click(button(mc.screen, "Keep editing"));
          next("dirty close kept draft");
        }
        case 8 -> {
          check(
              mc.screen == form
                  && actor.view().availability() == EditorActor.Availability.READY
                  && label.getValue().equals("Native label selection"),
              "Keep editing closed or replaced the draft");
          click(button(form, "Revert"));
          next("native revert requested");
        }
        case 9 -> {
          if (actor.view().dirty() || !label.getValue().equals("Example")) return;
          check(
              distance.getValue().equals("128") && Files.readString(path()).equals(savedBytes),
              "Revert changed saved values");
          click(button(form, "Defaults"));
          next("revert retains actual saved baseline");
        }
        case 10 -> {
          if (!distance.getValue().equals("64") || !actor.view().dirty()) return;
          check(Files.readString(path()).equals(savedBytes), "Reset wrote factory defaults");
          form.onClose();
          next("defaults stage a dirty draft without writing");
        }
        case 11 -> {
          check(mc.screen instanceof ConfirmScreen, "Default draft closed without review");
          click(button(mc.screen, "Discard draft"));
          check(
              mc.screen == parent && actor.view().availability() == EditorActor.Availability.CLOSED,
              "Discard leaked editor or parent context");
          open();
          next("explicit discard closes actor; real factory reopened");
        }
        case 12 -> {
          if (actor.view().snapshot().isEmpty()) return;
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
          finish(null);
        }
        default -> throw new AssertionError("Unexpected stage " + stage);
      }
    } catch (Throwable failure) {
      finish(failure);
    }
  }

  private void open() {
    var container = ModList.get().getModContainerById(NativeExample.ID).orElseThrow();
    var factory = container.getCustomExtension(IConfigScreenFactory.class).orElseThrow();
    form = factory.createScreen(container, parent);
    mc.setScreen(form);
    actor = ClientExample.INSTANCE.editor();
  }

  private String raw(String name) {
    var node = (DraftNode.ObjectValue) actor.view().snapshot().orElseThrow().draft().root();
    return ((DraftNode.Scalar) node.fields().get(name)).raw();
  }

  private static Path path() {
    return net.neoforged.fml.loading.FMLPaths.CONFIGDIR
        .get()
        .resolve(NativeExample.ID)
        .resolve("preferences.json");
  }

  private void replace(EditBox input, String value) {
    check(focus(form, input), "Input not in the native focus tree");
    input.moveCursorToEnd(false);
    input.setHighlightPos(0);
    for (char next : value.toCharArray())
      check(form.charTyped(next, 0), "Native text event was not consumed");
  }

  private static boolean focus(ContainerEventHandler parent, GuiEventListener target) {
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
    check(
        button.active && button.visible,
        "Inactive native control " + button.getMessage().getString());
    check(
        mc.screen.mouseClicked(
            button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0),
        "Native mouse event not consumed");
    mc.screen.mouseReleased(button.getX() + 1, button.getY() + 1, 0);
  }

  private static EditBox input(ContainerEventHandler parent, String label) {
    return widgets(parent).stream()
        .filter(EditBox.class::isInstance)
        .map(EditBox.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(label))
        .findFirst()
        .orElseThrow();
  }

  private static Button button(ContainerEventHandler parent, String label) {
    return widgets(parent).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(label))
        .findFirst()
        .orElseThrow();
  }

  private static List<AbstractWidget> widgets(ContainerEventHandler parent) {
    var found = new ArrayList<AbstractWidget>();
    for (var child : parent.children()) {
      if (child instanceof AbstractWidget widget) found.add(widget);
      if (child instanceof ContainerEventHandler nested) found.addAll(widgets(nested));
    }
    return found;
  }

  private static void geometry(Screen screen) {
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
    if (capture != null) {
      Screenshot.grab(mc.gameDirectory, capture, mc.getMainRenderTarget(), ignored -> {});
      capture = null;
    }
  }

  private void finish(Throwable failure) {
    finished = true;
    report.addProperty("stage", stage);
    report.addProperty("ticks", ticks);
    report.addProperty("status", failure == null ? "PASS" : "FAIL");
    if (failure != null) {
      report.addProperty("failure", failure.toString());
      var stack = new java.io.StringWriter();
      failure.printStackTrace(new java.io.PrintWriter(stack));
      report.addProperty("stack", stack.toString());
    }
    try {
      Files.writeString(
          mc.gameDirectory.toPath().resolve("native-form-report.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(report));
    } catch (Exception writeFailure) {
      throw new IllegalStateException("Cannot retain native form evidence", writeFailure);
    } finally {
      mc.stop();
    }
  }

  private static void check(boolean passed, String message) {
    if (!passed) throw new AssertionError(message);
  }
}
