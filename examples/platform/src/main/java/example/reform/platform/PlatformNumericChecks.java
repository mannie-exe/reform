package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.ReviewScreen;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;

/** Native input, display-only changes and actual canonical file/runtime roundtrips. */
final class PlatformNumericChecks implements PlatformFormProbe.Scene {
  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "NUMERIC",
        driver -> new PlatformNumericChecks(reopen, driver::progress, driver::capture));
  }

  public String diagnostic() {
    return "numeric stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName()
        + ", owner="
        + PlatformNumericExample.settings().status().lifecycle();
  }

  private final Minecraft client = Minecraft.getInstance();
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final JsonArray inputHandlerNanos = new JsonArray();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final Screen parent;
  private SettingsScreen<PlatformNumericExample.Profile> form;
  private final Screen opening;
  private boolean recoveryRequested;
  private Button retiredBack, retiredRetry;
  private int stage, cursor;
  private long version;
  private String original, saved, selected;
  private EditBox duration;
  private EditBox rangeMinimum, rangeMaximum;
  private CycleButton<?> retiredUnits;
  private Button retiredStep;
  private AbstractSliderButton retiredSlider;
  private boolean domainsChecked;
  private ReviewScreen<?> numericReview;
  private int reviewChoices;

  @SuppressWarnings("unchecked") // Actual typed public registration supplies this generated screen.
  PlatformNumericChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    parent = NativeScreens.current(client);
    opening =
        PlatformNumericExample.settings()
            .screen(
                parent,
                net.minecraft.network.chat.Component.literal("Numeric public settings example"),
                PlatformNumericExample.PRESENTATION);
    if (opening instanceof SettingsScreen<?> ready)
      form = (SettingsScreen<PlatformNumericExample.Profile>) ready;
    report.add("checks", checks);
    report.add("inputHandlerNanos", inputHandlerNanos);
    report.addProperty(
        "inputHandlerScope",
        "Native pointer dispatch only; excludes queued owner work, rendering, capture, and file IO");
    report.addProperty("status", "RUNNING");
    NativeScreens.open(client, opening);
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
    return client.gameDirectory.toPath().resolve("config/reform_platform_example/numeric.json");
  }

  private CycleButton<?> units(String label) {
    return widgets(form).stream()
        .filter(CycleButton.class::isInstance)
        .map(w -> (CycleButton<?>) w)
        .filter(w -> NativeNarrationProbe.read(w).contains(label + ": Display unit"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing units for " + label));
  }

  private Button step(String label, boolean more) {
    return widgets(form).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(
            w ->
                NativeNarrationProbe.read(w)
                    .contains(label + ": " + (more ? "Increase" : "Decrease")))
        .findFirst()
        .orElseThrow();
  }

  private AbstractSliderButton slider(String label) {
    return widgets(form).stream()
        .filter(AbstractSliderButton.class::isInstance)
        .map(AbstractSliderButton.class::cast)
        .filter(widget -> NativeNarrationProbe.read(widget).contains(label + ": "))
        .findFirst()
        .orElseThrow();
  }

  private void selectSlider(String label, double fraction) {
    var slider = slider(label);
    verify(
        "endpoint slider is active and focusable: " + label, slider.active && focus(form, slider));
    double x = slider.getX() + 4 + (slider.getWidth() - 8) * fraction;
    double y = slider.getY() + slider.getHeight() / 2.0;
    long started = System.nanoTime();
    boolean accepted = NativeClientProbe.pointerDown(form, x, y);
    NativeClientProbe.pointerUp(form, x, y);
    inputHandlerNanos.add(System.nanoTime() - started);
    verify("endpoint pointer admitted: " + label, accepted);
  }

  private void information(String id) {
    verify("numeric information field can be focused", form.focusField(path(id)));
    var name = field(id).getMessage().getString();
    var info =
        widgets(form).stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(
                button ->
                    button.getMessage().getString().equals("i")
                        && NativeNarrationProbe.read(button).contains(name))
            .findFirst()
            .orElseThrow();
    press(info);
  }

  private void dragProbability() {
    var slider = slider("Probability");
    verify("declared native probability slider is active", slider.active && focus(form, slider));
    double x = slider.getX() + slider.getWidth() / 2.0;
    double y = slider.getY() + slider.getHeight() / 2.0;
    report.add("dragBefore", dragState(slider, x, y));
    verify("native slider pointer down consumed", NativeClientProbe.pointerDown(form, x, y));
    report.add("dragCaptured", dragState(slider, x, y));
    for (int i = 0; i < 20; i++) {
      double next = slider.getX() + 4 + (slider.getWidth() - 8) * (0.51 + i * 0.01);
      boolean consumed = NativeClientProbe.drag(form, next, y, next - x, 0);
      if (!consumed) report.add("dragRefused", dragState(slider, next, y));
      verify("native slider drag consumed", consumed);
      x = next;
    }
    NativeClientProbe.pointerUp(form, x, y);
  }

  private JsonObject dragState(AbstractWidget slider, double x, double y) {
    var state = new JsonObject();
    state.addProperty("pointerX", x);
    state.addProperty("pointerY", y);
    state.addProperty("sliderX", slider.getX());
    state.addProperty("sliderY", slider.getY());
    state.addProperty("sliderWidth", slider.getWidth());
    state.addProperty("sliderHeight", slider.getHeight());
    state.addProperty("active", slider.active);
    state.addProperty("visible", slider.visible);
    var chain = new JsonArray();
    ContainerEventHandler current = form;
    for (int depth = 0; depth < 5; depth++) {
      var item = new JsonObject();
      item.addProperty("class", current.getClass().getName());
      item.addProperty("dragging", current.isDragging());
      var focused = current.getFocused();
      item.addProperty("focused", focused == null ? "none" : focused.getClass().getName());
      item.addProperty("expectedSlider", focused == slider);
      chain.add(item);
      if (!(focused instanceof ContainerEventHandler child)) break;
      current = child;
    }
    state.add("chain", chain);
    var under = new JsonArray();
    widgets(form).stream()
        .filter(widget -> widget.isMouseOver(x, y))
        .forEach(
            widget ->
                under.add(
                    widget.getClass().getSimpleName() + ":" + widget.getMessage().getString()));
    state.add("underPointer", under);
    return state;
  }

  private void press(AbstractWidget widget) {
    verify("native quantity widget is in focus tree", focus(form, widget));
    verify("native quantity widget active and visible", widget.active && widget.visible);
    long started = System.nanoTime();
    boolean accepted = NativeClientProbe.click(form, widget);
    inputHandlerNanos.add(System.nanoTime() - started);
    verify("native quantity pointer event consumed", accepted);
  }

  private boolean ready() {
    return NativeScreens.current(client) == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private void expectedSaved() {
    var actual =
        PlatformNumericExample.settings().runtime().orElseThrow().view().value().orElseThrow();
    verify(
        "canonical native numeric profile saved and published",
        actual.equals(new PlatformNumericExample.Profile(4, 80, 0.3, 40, 3, 0.1, 50, 60, 0)));
  }

  public boolean tick() throws Exception {
    if (form == null) {
      recover();
      return false;
    }
    if (!ready() && stage != 35 && stage != 36) return false;
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
          verify(
              "saved native zero readout and narration retain the owner's meaning",
              slider(field("initialZoom").getMessage().getString())
                      .getMessage()
                      .getString()
                      .contains("Native initial view")
                  && NativeNarrationProbe.read(
                          slider(field("initialZoom").getMessage().getString()))
                      .contains("Native initial view")
                  && field("initialZoom").getValue().equals("0"));
          form.onClose();
          verify("clean numeric close returns parent", NativeScreens.current(client) == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        if (!domainsChecked) {
          original = Files.readString(source());
          replace(form, field("probability"), "0");
          stage = 21;
          progress.accept("exclusive-zero native input staged");
          return false;
        }
        original = Files.readString(source());
        duration = field("duration");
        press(duration);
        verify("native mouse click retains text-input focus", duration.isFocused());
        verify(
            "native keyboard navigation follows the clicked field",
            NativeClientProbe.key(form, "key.keyboard.left", true));
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
        version = form.editorView().snapshot().orElseThrow().version();
        dragProbability();
        next("native probability slider dragged with latest-value coalescing");
      }
      case 7 -> {
        if (number("probability").compareTo(new BigDecimal("0.7")) != 0) return false;
        long admitted = form.editorView().snapshot().orElseThrow().version() - version;
        verify(
            "twenty native drag events admit at most two draft changes",
            admitted > 0 && admitted <= 2);
        verify(
            "slider retains final display value without file effects",
            field("probability").getValue().equals("70")
                && Files.readString(source()).equals(saved));
        replace(form, field("probability"), "30");
        next("probability exactly edited as percent after slider");
      }
      case 8 -> {
        if (number("probability").compareTo(new BigDecimal("0.3")) != 0) return false;
        press(units("Percent"));
        verify(
            "already stored percent displays a distinct probability",
            new BigDecimal(field("percent").getValue()).compareTo(new BigDecimal("0.35")) == 0);
        replace(form, field("percent"), "0.4");
        next("already stored percent edited as probability");
      }
      case 9 -> {
        if (number("percent").intValueExact() != 40) return false;
        press(units("Thirds"));
        verify(
            "nonterminating increment remains disabled",
            field("thirds").getValue().equals("1") && !step("Thirds", true).active);
        press(units("Thirds"));
        replace(form, field("thirds"), "1");
        next("canonical value without finite alternate representation authored");
      }
      case 10 -> {
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
      case 11 -> {
        if (number("thirds").intValueExact() != 3) return false;
        press(units("Tiny"));
        replace(form, field("tiny"), "0.1");
        next("scale-limited conversion with canonical-looking text authored");
      }
      case 12 -> {
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
      case 13 -> {
        if (!form.editorView().validation().valid()) return false;
        press(units("Tiny"));
        replace(form, field("tiny"), "0.1");
        next("tiny value explicitly repaired in canonical storage unit");
      }
      case 14 -> {
        if (!form.editorView().validation().valid()
            || number("tiny").compareTo(new BigDecimal("0.1")) != 0) return false;
        rangeMinimum = field("minimum");
        rangeMaximum = field("maximum");
        verify(
            "paired range exposes two actual native field identities",
            rangeMinimum != rangeMaximum);
        if (form.width >= 720)
          verify(
              "wide range fields share measured columns",
              rangeMinimum.getY() == rangeMaximum.getY()
                  && rangeMinimum.getX() < rangeMaximum.getX());
        replace(form, rangeMinimum, "50");
        next("inverted range staged without moving maximum");
      }
      case 15 -> {
        if (form.editorView().validation().valid()) return false;
        verify(
            "owner range validator reports both identities without changing the other input",
            number("minimum").intValueExact() == 50
                && number("maximum").intValueExact() == 40
                && form.editorView().validation().issues().stream()
                    .anyMatch(
                        issue ->
                            issue.path().equals(path("minimum"))
                                && issue.related().contains(path("maximum")))
                && !button(form, "Save and apply").active);
        verify(
            "cross-field error does not disable repair of maximum", step("Maximum", true).active);
        press(step("Maximum", true));
        next("maximum explicitly incremented while range remains invalid");
      }
      case 16 -> {
        if (number("maximum").intValueExact() != 41) return false;
        verify(
            "range increment never clamps or swaps minimum",
            number("minimum").intValueExact() == 50
                && !form.editorView().validation().valid()
                && Files.readString(source()).equals(saved));
        replace(form, rangeMaximum, "60");
        next("range repaired through its second native field");
      }
      case 17 -> {
        if (!form.editorView().validation().valid()) return false;
        version = form.editorView().snapshot().orElseThrow().version();
        press(units("Minimum"));
        verify(
            "range endpoint display units remain independent and do not edit",
            rangeMinimum.getValue().equals("2.5")
                && rangeMaximum.getValue().equals("60")
                && version == form.editorView().snapshot().orElseThrow().version());
        capture.accept("form-numeric-range.png", () -> {});
        next("paired range ready before narrow reflow");
      }
      case 18 -> {
        retiredUnits = units("Duration");
        retiredStep = step("Duration", true);
        retiredSlider = slider("Probability");
        form.focusField(path("minimum"));
        duration.setCursorPosition(2);
        duration.setHighlightPos(0);
        cursor = duration.getCursorPosition();
        selected = duration.getHighlighted();
        client.options.guiScale().set(3);
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        next("narrow numeric reflow requested");
      }
      case 19 -> {
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
        verify(
            "narrow range retains both fields and uses complete columns or a complete stack: minimum="
                + rangeMinimum.getValue()
                + "@"
                + rangeMinimum.getX()
                + ","
                + rangeMinimum.getY()
                + "; maximum="
                + rangeMaximum.getValue()
                + "@"
                + rangeMaximum.getX()
                + ","
                + rangeMaximum.getY(),
            field("minimum") == rangeMinimum
                && field("maximum") == rangeMaximum
                && rangeMinimum.getValue().equals("2.5")
                && rangeMaximum.getValue().equals("60")
                && ((rangeMinimum.getY() == rangeMaximum.getY()
                        && rangeMinimum.getX() < rangeMaximum.getX())
                    || (rangeMinimum.getX() == rangeMaximum.getX()
                        && rangeMinimum.getY() < rangeMaximum.getY())));
        geometry(form);
        verify(
            "compact numeric caption can be reached without changing its value",
            form.focusField(path("initialZoom"))
                && field("initialZoom").getValue().equals("0")
                && slider(field("initialZoom").getMessage().getString())
                    .getMessage()
                    .getString()
                    .contains("Native initial view"));
        capture.accept(
            "form-numeric-narrow.png", () -> click(form, button(form, "Save and apply")));
        next("narrow canonical save requested");
      }
      case 20 -> {
        if (form.editorView().dirty() || form.editorView().pending().isPresent()) return false;
        expectedSaved();
        saved = Files.readString(source());
        verify(
            "display units remain private and source contains canonical fields only",
            !saved.contains("seconds")
                && !saved.contains("milliseconds")
                && !saved.contains("unitInput"));
        form.onClose();
        NativeClientProbe.press(retiredUnits);
        NativeClientProbe.press(retiredStep);
        NativeClientProbe.clickSlider(retiredSlider);
        verify(
            "retired unit and step callbacks cannot reopen or write",
            NativeScreens.current(client) == parent && Files.readString(source()).equals(saved));
        report.addProperty("status", "PASS");
        return true;
      }
      case 21 -> {
        if (form.editorView().validation().valid()) return false;
        verify(
            "native zero is retained and refused by the positive domain",
            node("probability").raw().equals("0")
                && field("probability").getValue().equals("0")
                && !button(form, "Save and apply").active
                && Files.readString(source()).equals(original));
        replace(form, field("probability"), "1e-300");
        next("tiny positive native value staged below its slider grid");
      }
      case 22 -> {
        if (!form.editorView().validation().valid()) return false;
        verify(
            "native exact input below the step remains valid and unquantized",
            node("probability").raw().equals("1e-300")
                && field("probability").getValue().equals("1e-300")
                && !step("Probability", false).active);
        selectSlider("Probability", 0);
        next("open lower endpoint selected through native pointer input");
      }
      case 23 -> {
        if (number("probability").compareTo(new BigDecimal("0.01")) != 0) return false;
        verify("native lower slider excludes zero", !step("Probability", false).active);
        replace(form, field("percent"), "100");
        next("open upper endpoint authored through native text");
      }
      case 24 -> {
        if (form.editorView().validation().valid()) return false;
        verify(
            "native open upper endpoint remains repairable without saving",
            field("percent").getValue().equals("100")
                && !button(form, "Save and apply").active
                && Files.readString(source()).equals(original));
        replace(form, field("percent"), "99");
        next("open upper endpoint repaired exactly");
      }
      case 25 -> {
        if (!form.editorView().validation().valid()) return false;
        verify(
            "native increment does not offer an excluded upper endpoint",
            !step("Percent", true).active);
        selectSlider("Percent", 1);
        next("upper native pointer uses its last valid grid point");
      }
      case 26 -> {
        if (number("percent").compareTo(BigDecimal.valueOf(99)) != 0) return false;
        verify(
            "upper grid selection never stages forbidden one hundred",
            field("percent").getValue().equals("99"));
        replace(form, field("percent"), "35");
        next("upper-bound fixture restored to original value");
      }
      case 27 -> {
        if (!form.editorView().validation().valid() || number("percent").intValueExact() != 35)
          return false;
        replace(form, field("probability"), "0.35");
        next("positive-domain fixture restored exactly");
      }
      case 28 -> {
        if (!form.editorView().validation().valid()
            || number("probability").compareTo(new BigDecimal("0.35")) != 0) return false;
        replace(form, field("duration"), "0");
        next("owner-declared zero timer caption staged");
      }
      case 29 -> {
        if (!form.editorView().validation().valid() || number("duration").signum() != 0)
          return false;
        verify(
            "zero numeric readout and narration use the owner's native-view caption",
            slider(field("initialZoom").getMessage().getString())
                    .getMessage()
                    .getString()
                    .contains("Native initial view")
                && NativeNarrationProbe.read(slider(field("initialZoom").getMessage().getString()))
                    .contains("Native initial view")
                && field("initialZoom").getValue().equals("0"));
        information("duration");
        next("numeric draft information requested through the real i control");
      }
      case 30 -> {
        if (form.inspectedField().filter(value -> value.path().equals(path("duration"))).isEmpty())
          return false;
        var info = form.inspectedField().orElseThrow();
        var diagnostic = new JsonObject();
        diagnostic.addProperty("draft", info.draft().text().orElse("absent"));
        diagnostic.addProperty("baseline", info.baseline().text().orElse("absent"));
        diagnostic.addProperty("effective", info.effective().text().orElse("unavailable"));
        diagnostic.addProperty("default", info.declaredDefault().text().orElse("unavailable"));
        diagnostic.addProperty(
            "draftCaption",
            info.draft().numericCaption().map(value -> value.fallback()).orElse("none"));
        diagnostic.addProperty("baselineCaption", info.baseline().numericCaption().isPresent());
        diagnostic.addProperty("effectiveCaption", info.effective().numericCaption().isPresent());
        diagnostic.addProperty(
            "defaultCaption", info.declaredDefault().numericCaption().isPresent());
        boolean unchanged = Files.readString(source()).equals(original);
        diagnostic.addProperty("sourceUnchanged", unchanged);
        org.slf4j.LoggerFactory.getLogger("ReformNumericProbe")
            .info("Numeric information receipt {}", diagnostic);
        report.add("zeroDraftInformation", diagnostic);
        verify(
            "native information annotates only the actual zero draft",
            info.draft().numericCaption().orElseThrow().fallback().equals("Off")
                && info.draft()
                    .text()
                    .orElseThrow()
                    .equals("\"0\" (unit input \"ticks\": \"0\"; exact)")
                && info.baseline().numericCaption().isEmpty()
                && info.effective().numericCaption().isEmpty()
                && info.declaredDefault().numericCaption().isEmpty()
                && unchanged);
        capture.accept(
            "form-numeric-open-captions.png", () -> click(form, button(form, "Close information")));
        next("native draft caption captured without changing saved observations");
      }
      case 31 -> {
        if (form.inspectedField().isPresent()) return false;
        information("initialZoom");
        next("native saved/effective/default zero information requested");
      }
      case 32 -> {
        if (form.inspectedField()
            .filter(value -> value.path().equals(path("initialZoom")))
            .isEmpty()) return false;
        var info = form.inspectedField().orElseThrow();
        for (var value :
            java.util.List.of(
                info.baseline(), info.draft(), info.effective(), info.declaredDefault()))
          verify(
              "actual observed zero retains native initial-view meaning",
              value.numericCaption().orElseThrow().fallback().equals("Native initial view"));
        click(form, button(form, "Close information"));
        next("all four numeric observation states inspected");
      }
      case 33 -> {
        if (form.inspectedField().isPresent()) return false;
        click(form, ownerAction(form, "Inspect…"));
        numericReview = (ReviewScreen<?>) NativeScreens.current(client);
        stage = 35;
        progress.accept("actual native review of numeric captions requested");
      }
      case 35 -> {
        if (numericReview.reviewedInput().isEmpty()) return false;
        var entry =
            numericReview.reviewedInput().orElseThrow().entries().stream()
                .filter(value -> value.path().equals(path("duration")))
                .findFirst()
                .orElseThrow();
        verify(
            "native review retains raw values with independent caption annotations",
            entry.numericValues().draft().orElseThrow().fallback().equals("Off")
                && entry.numericValues().baseline().isEmpty()
                && entry.draft().equals("\"0\" (unit input \"ticks\": \"0\"; exact)"));
        verify(
            "actual native review narration includes the Off meaning",
            numericReview.selectedField().orElseThrow().equals(path("duration"))
                && numericReview.getNarrationMessage().getString().contains("Off"));
        verify(
            "actual native review narrates the shared declared bounds and stored unit",
            numericReview.getNarrationMessage().getString().contains("Numeric bounds")
                && numericReview
                    .getNarrationMessage()
                    .getString()
                    .contains("value >= 0 and value <= 12000")
                && numericReview.getNarrationMessage().getString().contains("stored unit: ticks"));
        stage = 36;
        progress.accept("native review caption semantics verified");
      }
      case 36 -> {
        if (!numericReview.selectedField().orElseThrow().equals(path("initialZoom"))) {
          if (++reviewChoices > 9)
            throw new AssertionError("Numeric review did not reach native zoom");
          var choice =
              widgets(numericReview).stream()
                  .filter(CycleButton.class::isInstance)
                  .map(CycleButton.class::cast)
                  .filter(button -> NativeNarrationProbe.read(button).contains("Field"))
                  .findFirst()
                  .orElseThrow();
          click(numericReview, choice);
          return false;
        }
        verify(
            "review renders and narrates the same native initial-view caption",
            numericReview.getNarrationMessage().getString().contains("Native initial view")
                && Files.readString(source()).equals(original));
        capture.accept("form-numeric-caption-review.png", numericReview::onClose);
        stage = 37;
        progress.accept("native numeric caption review captured");
      }
      case 37 -> {
        numericReview = null;
        replace(form, field("duration"), "20");
        next("caption trial restored before existing unit regressions");
      }
      case 38 -> {
        if (!form.editorView().validation().valid()
            || number("duration").intValueExact() != 20
            || number("percent").compareTo(BigDecimal.valueOf(35)) != 0
            || number("probability").compareTo(new BigDecimal("0.35")) != 0) return false;
        verify(
            "domain/caption inspection and restoration do not write source bytes",
            Files.readString(source()).equals(original));
        verify(
            "authored numeric spelling remains distinct from the saved baseline",
            node("percent").raw().equals("35")
                && com.google.gson.JsonParser.parseString(original)
                    .getAsJsonObject()
                    .getAsJsonObject("values")
                    .get("percent")
                    .getAsString()
                    .equals("35.0")
                && form.editorView().dirty());
        click(form, ownerAction(form, "Revert"));
        next("actual Revert requested after restoring typed numeric values");
      }
      case 39 -> {
        if (!form.editorView().validation().valid() || form.editorView().dirty()) return false;
        verify(
            "actual Revert restores exact saved spelling without writing",
            node("percent").raw().equals("35.0") && Files.readString(source()).equals(original));
        domainsChecked = true;
        stage = 0;
        progress.accept(
            "open-domain and caption controls passed; existing unit regressions follow");
      }
      default -> throw new AssertionError("Unknown numeric fixture stage");
    }
    return false;
  }

  @SuppressWarnings(
      "unchecked") // A ready screen from this registration has its actual profile type.
  private void recover() throws Exception {
    if (!recoveryRequested) {
      if (PlatformNumericExample.settings().status().lifecycle()
          != ClientFileSettings.Lifecycle.UNAVAILABLE) return;
      verify(
          "malformed numeric source retained without an editor",
          Files.readString(source()).equals("{invalid numeric fixture")
              && widgets(opening).stream().noneMatch(EditBox.class::isInstance));
      retiredBack = button(opening, "Back");
      retiredRetry = button(opening, "Retry");
      var replacement = new JsonObject();
      replacement.addProperty("schemaVersion", 1);
      replacement.add(
          "values",
          PlatformNumericExample.DEFINITION
              .type()
              .codec()
              .encodeStart(
                  com.mojang.serialization.JsonOps.INSTANCE,
                  PlatformNumericExample.DEFINITION.defaults())
              .getOrThrow());
      Files.writeString(source(), replacement.toString());
      click(opening, retiredRetry);
      recoveryRequested = true;
      progress.accept("native numeric initialization retry requested with exact presentation");
      return;
    }
    if (PlatformNumericExample.settings().status().lifecycle() != ClientFileSettings.Lifecycle.READY
        || !(NativeScreens.current(client) instanceof SettingsScreen<?> ready)) return;
    if (ready.editorView().snapshot().isEmpty()
        || ready.editorView().availability() != EditorActor.Availability.READY) return;
    form = (SettingsScreen<PlatformNumericExample.Profile>) ready;
    verify(
        "native recovery opens the actual ready owner", form.editorView().snapshot().isPresent());
    NativeClientProbe.press(retiredBack);
    NativeClientProbe.press(retiredRetry);
    opening.tick();
    verify(
        "retired initialization screen cannot replace or close its successor",
        NativeScreens.current(client) == form);
    if (form.width < 720) {
      client.options.guiScale().set(2);
      NativeClientProbe.resize(client, 1000, 720);
      NativeClientProbe.reflow(client);
    }
    progress.accept("recovered numeric form retains owner-declared range presentation");
  }

  public JsonObject report() {
    return report;
  }
}
