package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import example.reform.NumericExample;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Native input, display-only changes and actual canonical file/runtime roundtrips. */
final class NativeNumericControls {
  private final Minecraft client = Minecraft.getInstance();
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final Screen parent;
  private final SettingsScreen<NumericExample.Profile> form;
  private int stage, cursor;
  private long version;
  private String original, saved, selected;
  private EditBox duration;
  private CycleButton<?> retiredUnits;
  private Button retiredStep;

  @SuppressWarnings("unchecked") // Actual typed public registration supplies this generated screen.
  NativeNumericControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    parent = client.screen;
    form =
        (SettingsScreen<NumericExample.Profile>)
            NumericExample.settings()
                .screen(
                    parent,
                    net.minecraft.network.chat.Component.literal(
                        "Numeric public settings example"));
    report.add("checks", checks);
    report.addProperty("status", "RUNNING");
    client.setScreen(form);
  }

  private void verify(String name, boolean condition) {
    if (!condition) throw new AssertionError(name);
    checks.add(name);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private FieldPath path(String id) {
    return FieldPath.ROOT.field(id);
  }

  private EditBox field(String id) {
    return (EditBox) form.fieldControl(path(id)).orElseThrow();
  }

  private DraftNode.Scalar node(String id) {
    return (DraftNode.Scalar) form.editorView().snapshot().orElseThrow().draft().at(path(id));
  }

  private BigDecimal number(String id) {
    return new BigDecimal(node(id).raw());
  }

  private Path source() {
    return client.gameDirectory.toPath().resolve("config/reform_native_example/numeric.json");
  }

  private CycleButton<?> units(String label) {
    return widgets(form).stream()
        .filter(CycleButton.class::isInstance)
        .map(w -> (CycleButton<?>) w)
        .filter(w -> NativeStructuredControls.narration(w).contains(label + ": Display unit"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing units for " + label));
  }

  private Button step(String label, boolean more) {
    return widgets(form).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(
            w ->
                NativeStructuredControls.narration(w)
                    .contains(label + ": " + (more ? "Increase" : "Decrease")))
        .findFirst()
        .orElseThrow();
  }

  private void press(AbstractWidget widget) {
    verify("native quantity widget is in focus tree", focus(form, widget));
    verify("native quantity widget active and visible", widget.active && widget.visible);
    verify(
        "native quantity pointer event consumed",
        form.mouseClicked(
            widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0));
    form.mouseReleased(widget.getX() + 1, widget.getY() + 1, 0);
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private void expectedSaved() {
    var actual = NumericExample.settings().runtime().orElseThrow().view().value().orElseThrow();
    verify(
        "canonical native numeric profile saved and published",
        actual.equals(new NumericExample.Profile(4, 80, 0.3, 40, 3, 0.1)));
  }

  boolean tick() throws Exception {
    if (!ready()) return false;
    switch (stage) {
      case 0 -> {
        if (reopen) {
          expectedSaved();
          verify(
              "numeric profile reopens clean in second JVM",
              !form.editorView().dirty() && form.editorView().validation().valid());
          verify(
              "fresh frontend defaults to actual canonical units",
              units("Duration").getMessage().getString().equals("ticks")
                  && field("duration").getValue().equals("4"));
          form.onClose();
          verify("clean numeric close returns parent", client.screen == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        original = Files.readString(source());
        duration = field("duration");
        version = form.editorView().snapshot().orElseThrow().version();
        capture.accept("form-numeric-initial.png", () -> press(units("Duration")));
        next("duration display switched through native unit control");
      }
      case 1 -> {
        verify(
            "display-only switch does not edit or write",
            version == form.editorView().snapshot().orElseThrow().version()
                && !form.editorView().dirty()
                && Files.readString(source()).equals(original));
        verify(
            "ticks display exact seconds",
            units("Duration").getMessage().getString().equals("seconds")
                && duration.getValue().equals("1"));
        replace(form, duration, "0.125");
        next("fractional tick input staged");
      }
      case 2 -> {
        if (form.editorView().validation().valid()) return false;
        verify(
            "fractional ticks retained and refused",
            number("duration").compareTo(new BigDecimal("2.5")) == 0
                && node("duration").unitInput().orElseThrow().text().equals("0.125")
                && !button(form, "Save and apply").active);
        verify(
            "fractional tick typing leaves canonical file unchanged",
            Files.readString(source()).equals(original));
        version = form.editorView().snapshot().orElseThrow().version();
        press(units("Duration"));
        verify(
            "unit switch preserves invalid integer draft",
            duration.getValue().equals("125")
                && version == form.editorView().snapshot().orElseThrow().version()
                && node("duration").unitInput().orElseThrow().unit().equals("seconds"));
        press(units("Duration"));
        press(units("Duration"));
        replace(form, duration, "0.150");
        next("duration repaired in seconds without rounding");
      }
      case 3 -> {
        if (!form.editorView().validation().valid()) return false;
        verify(
            "exact entered seconds map to three integer ticks",
            number("duration").intValueExact() == 3);
        duration.setCursorPosition(4);
        duration.setHighlightPos(2);
        cursor = duration.getCursorPosition();
        selected = duration.getHighlighted();
        click(form, button(form, "Save and apply"));
        next("native mapped-input save requested");
      }
      case 4 -> {
        if (form.editorView().dirty() || form.editorView().pending().isPresent()) return false;
        saved = Files.readString(source());
        verify(
            "own save retains quantity widget spelling caret and selection",
            field("duration") == duration
                && duration.getValue().equals("0.150")
                && duration.getCursorPosition() == cursor
                && duration.getHighlighted().equals(selected));
        press(step("Duration", true));
        next("canonical one-tick increment requested while displaying seconds");
      }
      case 5 -> {
        if (number("duration").intValueExact() != 4) return false;
        verify(
            "one canonical tick becomes 0.05 display seconds", duration.getValue().equals("0.2"));
        verify("step stays draft-only", Files.readString(source()).equals(saved));
        version = form.editorView().snapshot().orElseThrow().version();
        press(units("Distance"));
        verify(
            "blocks display chunks without editing",
            new BigDecimal(field("distance").getValue()).intValueExact() == 4
                && version == form.editorView().snapshot().orElseThrow().version());
        replace(form, field("distance"), "5");
        next("distance edited in chunks");
      }
      case 6 -> {
        if (number("distance").intValueExact() != 80) return false;
        press(units("Probability"));
        verify(
            "stored probability displays percent",
            new BigDecimal(field("probability").getValue()).intValueExact() == 35);
        replace(form, field("probability"), "30");
        next("probability edited as percent");
      }
      case 7 -> {
        if (number("probability").compareTo(new BigDecimal("0.3")) != 0) return false;
        press(units("Percent"));
        verify(
            "already stored percent displays a distinct probability",
            new BigDecimal(field("percent").getValue()).compareTo(new BigDecimal("0.35")) == 0);
        replace(form, field("percent"), "0.4");
        next("already stored percent edited as probability");
      }
      case 8 -> {
        if (number("percent").intValueExact() != 40) return false;
        press(units("Thirds"));
        verify(
            "nonterminating increment remains disabled",
            field("thirds").getValue().equals("1") && !step("Thirds", true).active);
        press(units("Thirds"));
        replace(form, field("thirds"), "1");
        next("canonical value without finite alternate representation authored");
      }
      case 9 -> {
        if (number("thirds").intValueExact() != 1) return false;
        version = form.editorView().snapshot().orElseThrow().version();
        press(units("Thirds"));
        verify(
            "refused unit conversion keeps prior unit and exact draft",
            units("Thirds").getMessage().getString().equals("base")
                && field("thirds").getValue().equals("1")
                && version == form.editorView().snapshot().orElseThrow().version());
        replace(form, field("thirds"), "3");
        next("alternate-unit refusal repaired in canonical unit");
      }
      case 10 -> {
        if (number("thirds").intValueExact() != 3) return false;
        press(units("Tiny"));
        replace(form, field("tiny"), "0.1");
        next("scale-limited conversion with canonical-looking text authored");
      }
      case 11 -> {
        if (form.editorView().validation().valid()) return false;
        verify(
            "native failed mapping cannot save a differently meaningful canonical number",
            node("tiny").raw().equals("0.1")
                && node("tiny").unitInput().orElseThrow().state()
                    == design.inherent.reform.presentation.NumericDisplay.State.LIMIT
                && !button(form, "Save and apply").active
                && Files.readString(source()).equals(saved));
        version = form.editorView().snapshot().orElseThrow().version();
        press(units("Tiny"));
        verify(
            "failed conversion retains original display unit",
            units("Tiny").getMessage().getString().equals("tiny")
                && field("tiny").getValue().equals("0.1")
                && version == form.editorView().snapshot().orElseThrow().version());
        capture.accept("form-numeric-invalid.png", () -> replace(form, field("tiny"), "0"));
        next("invalid mapped input retained for review before explicit repair");
      }
      case 12 -> {
        if (!form.editorView().validation().valid()) return false;
        press(units("Tiny"));
        replace(form, field("tiny"), "0.1");
        next("tiny value explicitly repaired in canonical storage unit");
      }
      case 13 -> {
        if (!form.editorView().validation().valid()
            || number("tiny").compareTo(new BigDecimal("0.1")) != 0) return false;
        retiredUnits = units("Duration");
        retiredStep = step("Duration", true);
        form.focusField(path("duration"));
        duration.setCursorPosition(2);
        duration.setHighlightPos(0);
        cursor = duration.getCursorPosition();
        selected = duration.getHighlighted();
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        next("narrow numeric reflow requested");
      }
      case 14 -> {
        if (form.width > 340) return false;
        report.addProperty("narrowWidth", form.width);
        report.addProperty("narrowHeight", form.height);
        verify(
            "numeric reflow preserves unit widget input caret and selection",
            field("duration") == duration
                && units("Duration") == retiredUnits
                && duration.getValue().equals("0.2")
                && duration.getCursorPosition() == cursor
                && duration.getHighlighted().equals(selected));
        geometry(form);
        capture.accept(
            "form-numeric-narrow.png", () -> click(form, button(form, "Save and apply")));
        next("narrow canonical save requested");
      }
      case 15 -> {
        if (form.editorView().dirty() || form.editorView().pending().isPresent()) return false;
        expectedSaved();
        saved = Files.readString(source());
        verify(
            "display units remain private and source contains canonical fields only",
            !saved.contains("seconds")
                && !saved.contains("milliseconds")
                && !saved.contains("unitInput"));
        form.onClose();
        retiredUnits.onPress();
        retiredStep.onPress();
        verify(
            "retired unit and step callbacks cannot reopen or write",
            client.screen == parent && Files.readString(source()).equals(saved));
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unknown numeric fixture stage");
    }
    return false;
  }

  JsonObject report() {
    return report;
  }
}
