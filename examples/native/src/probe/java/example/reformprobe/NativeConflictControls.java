package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ConflictScreen;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import example.reform.ConflictExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Actual public native manual choices, failed-read recovery and separate conditional saves. */
final class NativeConflictControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final Path path =
      client.gameDirectory.toPath().resolve("config/reform_native_example/conflicts.json");
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private SettingsScreen<?> form;
  private Button retired;
  private UUID review;
  private String saved;
  private int stage;
  private boolean fallbackReload;

  NativeConflictControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    report.addProperty("status", "RUNNING");
    report.add("checks", checks);
  }

  JsonObject report() {
    return report;
  }

  private void check(String name, boolean value) {
    if (!value) throw new AssertionError(name);
    checks.add(name);
  }

  private void step(String name) {
    stage++;
    progress.accept(name);
  }

  private void press(Button button) {
    check("conflict widget uses native focus", focus(client.screen, button));
    click(client.screen, button);
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private String raw(String field) {
    return ((DraftNode.Scalar)
            form.editorView().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private ConflictScreen<?> popup() {
    return (ConflictScreen<?>) client.screen;
  }

  private boolean reviewed() {
    return client.screen instanceof ConflictScreen<?>
        && popup().reviewedInput().isPresent()
        && button(popup(), "Read saved values").active;
  }

  private void edit(String field, String value) {
    replace(form, (EditBox) form.fieldControl(FieldPath.ROOT.field(field)).orElseThrow(), value);
  }

  private void write(ConflictExample.Profile source) throws Exception {
    Files.writeString(
        path,
        "{\"schemaVersion\":1,\"values\":"
            + ConflictExample.DEFINITION.encode(source).getOrThrow()
            + "}");
  }

  private void choose(String field, String label) {
    var path = FieldPath.ROOT.field(field);
    for (int count = 0; count < 4; count++) {
      var widget = (CycleButton<?>) popup().choiceControl(path).orElseThrow();
      if (widget.getMessage().getString().equals(label)) return;
      check("choice control participates in native focus", focus(popup(), widget));
      check(
          "choice receives native pointer input",
          popup()
              .mouseClicked(
                  widget.getX() + widget.getWidth() / 2.0,
                  widget.getY() + widget.getHeight() / 2.0,
                  0));
      popup().mouseReleased(widget.getX() + 1, widget.getY() + 1, 0);
    }
    check(
        "selected actual native choice " + field,
        popup().choiceControl(path).orElseThrow().getMessage().getString().equals(label));
  }

  private void expect(ConflictExample.Profile value) {
    check(
        "actual saved profile published",
        ConflictExample.settings()
            .status()
            .effective()
            .orElseThrow()
            .value()
            .orElseThrow()
            .equals(value));
    check(
        "saved profile is clean",
        !form.editorView().dirty() && form.editorView().validation().valid());
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        form = (SettingsScreen<?>) ConflictExample.screen(parent);
        client.setScreen(form);
        step("native conflict parent opened");
      }
      case 1 -> {
        if (!ready()) return false;
        if (reopen) {
          expect(new ConflictExample.Profile(9, 10, List.of("safe")));
          form.onClose();
          check("second JVM returns cleanly", client.screen == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        edit("minimum", "5");
        step("native local draft authored");
      }
      case 2 -> {
        if (!ready() || !raw("minimum").equals("5")) return false;
        write(new ConflictExample.Profile(2, 12, List.of("remote")));
        saved = Files.readString(path);
        press(button(form, "Save and apply"));
        step("native save encounters actual external source change");
      }
      case 3 -> {
        if (!ready()) return false;
        if (form.editorView().review().isEmpty()) {
          check(
              "save has not overwritten the external source", Files.readString(path).equals(saved));
          if (!fallbackReload) {
            fallbackReload = true;
            press(button(form, "Reload saved"));
          }
          return false;
        }
        press(button(form, "Review conflict…"));
        step("generated conflict entry opens public native view");
      }
      case 4 -> {
        if (!reviewed()) return false;
        var input = popup().reviewedInput().orElseThrow();
        review = input.id();
        check(
            "native comparison identifies all actual fields",
            input.entries().size() == 3
                && !input.baselineRevision().equals(input.observedRevision()));
        check("native choices have no implicit default", !button(popup(), "Resolve draft").active);
        geometry(popup());
        capture.accept("form-conflict-review.png", () -> choose("minimum", "Your draft"));
        step("one native field choice selected");
      }
      case 5 -> {
        check("incomplete choice set cannot resolve", !button(popup(), "Resolve draft").active);
        press(button(popup(), "Back"));
        step("partially selected review left for parent");
      }
      case 6 -> {
        if (!ready()) return false;
        check(
            "Back retains actual draft and source",
            raw("minimum").equals("5") && Files.readString(path).equals(saved));
        press(button(form, "Review conflict…"));
        step("native review reopened");
      }
      case 7 -> {
        if (!reviewed()) return false;
        check(
            "review and explicit choice survive navigation",
            popup().reviewedInput().orElseThrow().id().equals(review)
                && popup()
                    .choiceControl(FieldPath.ROOT.field("minimum"))
                    .orElseThrow()
                    .getMessage()
                    .getString()
                    .equals("Your draft"));
        choose("maximum", "Observed saved value");
        choose("roles", "Earlier saved value");
        check(
            "whole collection choice is explicit",
            popup().reviewedInput().orElseThrow().entries().stream()
                .filter(entry -> entry.path().equals(FieldPath.ROOT.field("roles")))
                .findFirst()
                .orElseThrow()
                .wholeValue());
        check("complete choice set admits resolution", button(popup(), "Resolve draft").active);
        retired = button(popup(), "Resolve draft");
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        step("native conflict view narrowed");
      }
      case 8 -> {
        if (client.screen.width > 340) return false;
        geometry(popup());
        check("choices survive native reflow", button(popup(), "Resolve draft").active);
        report.addProperty("narrowWidth", client.screen.width);
        report.addProperty("narrowHeight", client.screen.height);
        capture.accept("form-conflict-narrow.png", () -> press(button(popup(), "Resolve draft")));
        step("native choices resolved separately from Save");
      }
      case 9 -> {
        if (!ready() || form.editorView().review().isPresent()) return false;
        check(
            "native resolution retains selected values",
            raw("minimum").equals("5") && raw("maximum").equals("12"));
        check(
            "resolution alone changes no file or runtime",
            Files.readString(path).equals(saved)
                && ConflictExample.settings()
                    .status()
                    .effective()
                    .orElseThrow()
                    .value()
                    .orElseThrow()
                    .equals(ConflictExample.DEFINITION.defaults()));
        check(
            "obsolete conflict and Undo history are cleared",
            form.editorView().operationReport().valid() && !button(form, "Undo").active);
        long version = form.editorView().snapshot().orElseThrow().version();
        retired.onPress();
        check(
            "retired resolution cannot replay",
            form.editorView().snapshot().orElseThrow().version() == version);
        press(button(form, "Save and apply"));
        step("native resolved draft explicitly saved");
      }
      case 10 -> {
        if (!ready() || form.editorView().dirty()) return false;
        expect(new ConflictExample.Profile(5, 12, List.of("base")));
        edit("minimum", "6");
        step("next native draft authored");
      }
      case 11 -> {
        if (!ready() || !raw("minimum").equals("6")) return false;
        write(new ConflictExample.Profile(3, 14, List.of("next")));
        press(button(form, "Reload saved"));
        step("fresh saved observation requested");
      }
      case 12 -> {
        if (!ready() || form.editorView().review().isEmpty()) return false;
        press(button(form, "Review conflict…"));
        step("next native comparison opened");
      }
      case 13 -> {
        if (!reviewed()) return false;
        review = popup().reviewedInput().orElseThrow().id();
        choose("minimum", "Your draft");
        choose("maximum", "Observed saved value");
        choose("roles", "Observed saved value");
        Files.writeString(path, "not json");
        press(button(popup(), "Read saved values"));
        step("native review sees failed source read");
      }
      case 14 -> {
        if (!button(popup(), "Read saved values").active) return false;
        check(
            "failed source read disables earlier resolution",
            !button(popup(), "Resolve draft").active
                && !form.editorView().conflictObservationAvailable()
                && raw("minimum").equals("6"));
        check(
            "failed read preserves actual malformed source",
            Files.readString(path).equals("not json"));
        write(new ConflictExample.Profile(4, 16, List.of("again")));
        press(button(popup(), "Read saved values"));
        step("native review recovers from repaired source");
      }
      case 15 -> {
        if (!reviewed() || popup().reviewedInput().orElseThrow().id().equals(review)) return false;
        check(
            "repaired source requires fresh explicit choices",
            !button(popup(), "Resolve draft").active);
        choose("minimum", "Your draft");
        choose("maximum", "Observed saved value");
        choose("roles", "Observed saved value");
        write(new ConflictExample.Profile(7, 18, List.of("latest")));
        saved = Files.readString(path);
        press(button(popup(), "Resolve draft"));
        step("native review resolves before a later concurrent save");
      }
      case 16 -> {
        if (!ready() || form.editorView().review().isPresent()) return false;
        check(
            "later source change is not overwritten by resolution",
            Files.readString(path).equals(saved));
        fallbackReload = false;
        press(button(form, "Save and apply"));
        step("conditional Save checks newer actual source");
      }
      case 17 -> {
        if (!ready()) return false;
        check(
            "later conditional Save preserves current file and draft",
            Files.readString(path).equals(saved) && raw("minimum").equals("6"));
        if (form.editorView().review().isEmpty()) {
          if (!fallbackReload) {
            fallbackReload = true;
            press(button(form, "Reload saved"));
          }
          return false;
        }
        press(button(form, "Review conflict…"));
        step("newer source opens another native conflict");
      }
      case 18 -> {
        if (!reviewed()) return false;
        choose("minimum", "Your draft");
        choose("maximum", "Observed saved value");
        choose("roles", "Observed saved value");
        press(button(popup(), "Resolve draft"));
        step("latest source explicitly reconciled");
      }
      case 19 -> {
        if (!ready() || form.editorView().review().isPresent()) return false;
        press(button(form, "Save and apply"));
        step("latest native choices saved explicitly");
      }
      case 20 -> {
        if (!ready() || form.editorView().dirty()) return false;
        expect(new ConflictExample.Profile(6, 18, List.of("latest")));
        edit("minimum", "19");
        step("cross-field invalid draft retained");
      }
      case 21 -> {
        if (!ready() || !raw("minimum").equals("19")) return false;
        write(new ConflictExample.Profile(7, 10, List.of("safe")));
        saved = Files.readString(path);
        press(button(form, "Reload saved"));
        step("native invalid draft observes saved alternatives");
      }
      case 22 -> {
        if (!ready() || form.editorView().review().isEmpty()) return false;
        press(button(form, "Review conflict…"));
        step("native invalid comparison opened");
      }
      case 23 -> {
        if (!reviewed()) return false;
        choose("minimum", "Your draft");
        choose("maximum", "Observed saved value");
        choose("roles", "Observed saved value");
        press(button(popup(), "Resolve draft"));
        step("explicit mixed choices require complete validation");
      }
      case 24 -> {
        if (!ready() || form.editorView().review().isPresent()) return false;
        check(
            "invalid mixed result is retained and Save blocked",
            raw("minimum").equals("19")
                && raw("maximum").equals("10")
                && !form.editorView().validation().valid()
                && !button(form, "Save and apply").active
                && Files.readString(path).equals(saved));
        geometry(form);
        capture.accept("form-conflict-invalid.png", () -> edit("minimum", "9"));
        step("native invalid resolved draft repaired");
      }
      case 25 -> {
        if (!ready() || !raw("minimum").equals("9") || !form.editorView().validation().valid())
          return false;
        press(button(form, "Save and apply"));
        step("repaired native result explicitly saved");
      }
      case 26 -> {
        if (!ready() || form.editorView().dirty()) return false;
        expect(new ConflictExample.Profile(9, 10, List.of("safe")));
        form.onClose();
        check("native conflict workflow closes cleanly", client.screen == parent);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unknown native conflict stage " + stage);
    }
    return false;
  }
}
