package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormDependencies;
import design.inherent.reform.presentation.FormPresentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Actual local gates, retained input, keyboard explanations and canonical save/reopen. */
final class PlatformDependencyChecks implements PlatformFormProbe.Scene {
  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "DEPENDENCY",
        driver -> new PlatformDependencyChecks(reopen, driver::progress, driver::capture));
  }

  public String diagnostic() {
    return "dependency stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName();
  }

  private final Minecraft client = Minecraft.getInstance();
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final Screen parent;
  private SettingsScreen<PlatformDependencyExample.Profile> form;
  private final FormDependencies.Bound dependencies =
      PlatformDependencyExample.PRESENTATION.bind(PlatformDependencyExample.DEFINITION.type());
  private int stage;
  private String original;
  private long version;
  private boolean availabilityChecked;

  PlatformDependencyChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    parent = NativeScreens.current(client);
    var presentation = PlatformDependencyExample.PRESENTATION;
    availabilityChecked = reopen;
    if (!reopen)
      presentation =
          presentation.withUnavailableFields(
              List.of(
                  new FormPresentation.UnavailableField(
                      path("mode"), "fixture.missing_mode", "Requires the absent terrain provider"),
                  new FormPresentation.UnavailableField(
                      path("layers"),
                      "fixture.layers_version",
                      "The installed layer provider version is unsupported")));
    form = screen(presentation);
    report.add("checks", checks);
    report.addProperty("status", "RUNNING");
    NativeScreens.open(client, form);
  }

  @SuppressWarnings("unchecked") // The actual typed public registration supplies its own form.
  private SettingsScreen<PlatformDependencyExample.Profile> screen(FormPresentation presentation) {
    return (SettingsScreen<PlatformDependencyExample.Profile>)
        PlatformDependencyExample.settings()
            .screen(
                parent,
                net.minecraft.network.chat.Component.literal("Dependent public settings example"),
                presentation);
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

  private DraftNode node(FieldPath path) {
    return form.editorView().snapshot().orElseThrow().draft().at(path);
  }

  private String raw(String id) {
    return ((DraftNode.Scalar) node(path(id))).raw();
  }

  private EditBox field(String id) {
    return (EditBox) form.fieldControl(path(id)).orElseThrow();
  }

  private AbstractWidget control(String id) {
    return form.fieldControl(path(id)).orElseThrow();
  }

  private FormDependencies.State state(String id) {
    var view = form.editorView();
    return dependencies
        .evaluate(view.snapshot().orElseThrow().draft(), view.inputs())
        .at(path(id))
        .state();
  }

  private Button action(String context) {
    return widgets(form).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> NativeNarrationProbe.read(widget).startsWith(context))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing action: " + context));
  }

  private Button explanation(String label) {
    return widgets(form).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> widget.getMessage().getString().equals("Availability"))
        .filter(widget -> NativeNarrationProbe.read(widget).startsWith(label + ": "))
        .findFirst()
        .orElseThrow();
  }

  private void press(AbstractWidget widget) {
    verify("native dependent control has actual focus", focus(form, widget));
    verify("native dependent control is available", widget.active && widget.visible);
    verify(
        "native pointer dispatch reaches dependent control", NativeClientProbe.click(form, widget));
  }

  private Path source() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/dependencies.json");
  }

  private void expectedSaved() {
    var value =
        PlatformDependencyExample.settings().runtime().orElseThrow().view().value().orElseThrow();
    verify(
        "saved fields preserve disabled authored data",
        value.equals(
            new PlatformDependencyExample.Profile(
                true,
                PlatformDependencyExample.Mode.WATER,
                32,
                List.of(4, 8),
                Optional.empty(),
                7)));
  }

  public boolean tick() throws Exception {
    if (NativeScreens.current(client) != form
        || form.editorView().availability() != EditorActor.Availability.READY
        || form.editorView().snapshot().isEmpty()
        || !button(form, "Done").active) return false;
    if (!availabilityChecked) {
      original = Files.readString(source());
      verify(
          "absent provider retains its visible choice with its own explanation",
          raw("mode").equals("LAND")
              && !control("mode").active
              && NativeNarrationProbe.read(explanation("Mode"))
                  .contains("absent terrain provider"));
      verify(
          "saved value from an unavailable controller cannot unlock a dependent field",
          form.focusField(path("height"))
              && !NativeClientProbe.character(form, '9')
              && NativeNarrationProbe.read(explanation("Height"))
                  .contains("required input is unavailable"));
      verify(
          "structural controls share their unavailable parent reason",
          !action("Layers: Add entry").active
              && !action("Layers: Entry 1: Remove").active
              && NativeNarrationProbe.read(explanation("Layers"))
                  .contains("version is unsupported"));
      var reason = explanation("Mode");
      verify(
          "owner availability reason is keyboard reachable",
          focus(form, reason) && NativeClientProbe.key(form, "key.keyboard.space", true));
      form.onClose();
      verify(
          "unavailable inspection leaves saved data unchanged",
          NativeScreens.current(client) == parent && Files.readString(source()).equals(original));
      form = screen(PlatformDependencyExample.PRESENTATION);
      NativeScreens.open(client, form);
      availabilityChecked = true;
      return false;
    }
    switch (stage) {
      case 0 -> {
        if (reopen) {
          expectedSaved();
          verify(
              "second JVM retains disabled and unknown states",
              state("height") == FormDependencies.State.DISABLED
                  && state("detail") == FormDependencies.State.UNKNOWN);
          verify(
              "reopened owner is clean and still fully valid",
              !form.editorView().dirty() && form.editorView().validation().valid());
          form.onClose();
          verify(
              "clean dependent close returns to parent", NativeScreens.current(client) == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        original = Files.readString(source());
        verify(
            "absent optional controller produces unknown",
            state("detail") == FormDependencies.State.UNKNOWN);
        verify(
            "unknown explanation is explicit and names its input",
            NativeNarrationProbe.read(explanation("Detail")).contains("Availability unknown")
                && NativeNarrationProbe.read(explanation("Detail")).contains("inactive or absent")
                && NativeNarrationProbe.read(explanation("Detail")).contains("Advanced"));
        var detail = field("detail");
        verify("unknown value stays focusable", form.focusField(path("detail")));
        verify(
            "unknown input refuses actual typing without replacing its value",
            !NativeClientProbe.character(form, '9') && detail.getValue().equals("4"));
        replace(form, field("height"), "90");
        next("aggregate-invalid height staged without disabling its controller");
      }
      case 1 -> {
        if (!raw("height").equals("90") || form.editorView().validation().valid()) return false;
        verify(
            "unrelated owner error leaves local availability known",
            state("height") == FormDependencies.State.AVAILABLE && control("mode").active);
        verify("owner validation blocks Save", !button(form, "Save and apply").active);
        press(control("enabled"));
        next("terrain controller switched off through native input");
      }
      case 2 -> {
        if (!raw("enabled").equals("false")) return false;
        verify(
            "disabled controller retains own available control",
            control("enabled").active && !control("mode").active);
        verify(
            "dependent upstream input is unknown rather than guessed false",
            state("height") == FormDependencies.State.UNKNOWN);
        verify(
            "disabled numeric steps and slider cannot change input",
            !action("Height: Increase").active
                && widgets(form).stream()
                    .filter(AbstractSliderButton.class::isInstance)
                    .noneMatch(widget -> widget.active));
        verify(
            "container edit controls inherit the same gate",
            !action("Layers: Add entry").active && !action("Layers: Entry 1: Remove").active);
        var height = field("height");
        verify("inactive height stays focusable", form.focusField(path("height")));
        verify(
            "inactive height refuses native text input",
            !NativeClientProbe.character(form, '5') && height.getValue().equals("90"));
        verify(
            "inactive invalid data is retained and remains fully validated",
            raw("height").equals("90")
                && !form.editorView().validation().valid()
                && !button(form, "Save and apply").active);
        version = form.editorView().snapshot().orElseThrow().version();
        NativeClientProbe.press(action("Layers: Add entry"));
        NativeClientProbe.press(action("Height: Increase"));
        verify(
            "disabled action callbacks do not dispatch edits",
            version == form.editorView().snapshot().orElseThrow().version());
        var reason = explanation("Height");
        verify("inactive control explanation is keyboard reachable", focus(form, reason));
        verify(
            "explanation handles native keyboard activation",
            NativeClientProbe.key(form, "key.keyboard.space", true));
        verify(
            "explanation narrates immediate required input",
            NativeNarrationProbe.read(reason).contains("Mode"));
        verify(
            "availability inspection performs no save",
            Files.readString(source()).equals(original));
        capture.accept("form-dependencies-inactive.png", () -> press(control("enabled")));
        next("inactive retained draft and keyboard explanation captured");
      }
      case 3 -> {
        if (!raw("enabled").equals("true") || !control("mode").active) return false;
        verify(
            "reenabling restores the exact invalid input", field("height").getValue().equals("90"));
        replace(form, field("height"), "32");
        next("retained invalid value explicitly repaired");
      }
      case 4 -> {
        if (!raw("height").equals("32") || !form.editorView().validation().valid()) return false;
        verify("complete repair enables Save", button(form, "Save and apply").active);
        press(control("mode"));
        next("choice controller switched to WATER");
      }
      case 5 -> {
        if (!raw("mode").equals("WATER")) return false;
        verify(
            "known nonmatch has a distinct disabled state",
            state("height") == FormDependencies.State.DISABLED);
        verify(
            "owner reason is narrated instead of an unknown reason",
            NativeNarrationProbe.read(explanation("Height")).contains("Choose LAND mode"));
        verify("read-only field retains repaired value", field("height").getValue().equals("32"));
        press(action("Advanced: Add value"));
        next("optional dependency input explicitly created");
      }
      case 6 -> {
        var child = path("advanced").append(FieldPath.Part.VALUE);
        var widget = form.fieldControl(child);
        if (widget.isEmpty()) return false;
        if (widget.orElseThrow() instanceof EditBox input) replace(form, input, "true");
        else if (node(child) instanceof DraftNode.Scalar scalar && scalar.raw().equals("false"))
          press(widget.orElseThrow());
        next("optional controller receives a valid true input");
      }
      case 7 -> {
        if (state("detail") != FormDependencies.State.AVAILABLE
            || !form.editorView().validation().valid()) return false;
        replace(form, field("detail"), "7");
        next("formerly unknown dependent detail edited");
      }
      case 8 -> {
        if (!raw("detail").equals("7")) return false;
        press(action("Advanced: Use empty"));
        next("optional input retired without discarding its dependent value");
      }
      case 9 -> {
        if (state("detail") != FormDependencies.State.UNKNOWN) return false;
        verify(
            "inactive optional retains its authored dependent value",
            raw("detail").equals("7") && field("detail").getValue().equals("7"));
        verify(
            "all inactive values still pass complete validation",
            form.editorView().validation().valid());
        geometry(form);
        capture.accept(
            "form-dependencies-final.png", () -> click(form, button(form, "Save and apply")));
        next("canonical dependent profile save requested");
      }
      case 10 -> {
        if (form.editorView().dirty() || form.editorView().pending().isPresent()) return false;
        expectedSaved();
        String saved = Files.readString(source());
        verify(
            "presentation rules are not copied into the owner file",
            !saved.contains("dependency")
                && !saved.contains("translationKey")
                && !saved.contains("accepted"));
        verify("actual save changed only the ordinary owner source", !saved.equals(original));
        form.onClose();
        verify("saved dependent close returns parent", NativeScreens.current(client) == parent);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unknown dependent fixture stage");
    }
    return false;
  }

  public JsonObject report() {
    return report;
  }
}
