package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import example.reform.StructuredExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarrationThunk;
import net.minecraft.client.gui.screens.Screen;

/** Real recursive widgets, stable paths and public factory; no private library-state access. */
final class NativeStructuredControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private final boolean reopen;
  private final Screen parent;
  private SettingsScreen<StructuredExample.Profile> form;
  private int stage;
  private String original;
  private UUID number;
  private FieldPath numberPath, mapKey, mapValue;
  private EditBox numberControl, retiredOverride, retiredCounter;
  private static final FieldPath NUMBERS = FieldPath.ROOT.field("numbers"),
      ROLES = FieldPath.ROOT.field("roles"),
      TAGS = FieldPath.ROOT.field("tags"),
      NOTE = FieldPath.ROOT.field("note"),
      RETRIES = FieldPath.ROOT.field("retries"),
      MODE = FieldPath.ROOT.field("mode");
  private static final FieldPath NOTE_VALUE = NOTE.append(FieldPath.Part.VALUE),
      RETRY_VALUE = RETRIES.append(FieldPath.Part.VALUE),
      AMOUNT = MODE.variant("counter").field("amount");

  NativeStructuredControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    parent = client.screen;
    report.add("checks", checks);
    report.addProperty("status", "RUNNING");
    open();
  }

  @SuppressWarnings("unchecked") // The typed public registration supplies its own generated form.
  private void open() {
    form =
        (SettingsScreen<StructuredExample.Profile>)
            StructuredExample.settings()
                .screen(
                    parent,
                    net.minecraft.network.chat.Component.literal(
                        "Recursive public settings example"));
    client.setScreen(form);
  }

  private void verify(String name, boolean condition) {
    if (!condition) throw new AssertionError(name);
    checks.add(name);
  }

  private void step(String label) {
    stage++;
    progress.accept(label);
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private DraftNode at(FieldPath path) {
    return form.editorView().snapshot().orElseThrow().draft().at(path);
  }

  private DraftNode.Sequence rows(FieldPath path) {
    return (DraftNode.Sequence) at(path);
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) at(path)).raw();
  }

  private EditBox field(FieldPath path) {
    return (EditBox) form.fieldControl(path).orElseThrow();
  }

  private Path path() {
    return client.gameDirectory.toPath().resolve("config/reform_native_example/structured.json");
  }

  private void press(AbstractWidget widget) {
    verify("native control participates in focus tree", focus(form, widget));
    verify("native control is active and visible", widget.active && widget.visible);
    verify(
        "actual native pointer event consumed",
        form.mouseClicked(
            widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0));
    form.mouseReleased(widget.getX() + 1, widget.getY() + 1, 0);
  }

  private Button action(String context) {
    return widgets(form).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> narration(widget).contains(context))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing narrated action: " + context));
  }

  static String narration(AbstractWidget widget) {
    var parts = new ArrayList<String>();
    widget.updateNarration(
        new NarrationElementOutput() {
          @Override
          public void add(NarratedElementType type, NarrationThunk<?> value) {
            value.getText(parts::add);
          }

          @Override
          public NarrationElementOutput nest() {
            return this;
          }
        });
    return String.join(" ", parts);
  }

  private CycleButton<?> variant(String selected) {
    return widgets(form).stream()
        .filter(CycleButton.class::isInstance)
        .map(widget -> (CycleButton<?>) widget)
        .filter(widget -> widget.getMessage().getString().equals(selected))
        .findFirst()
        .orElseThrow();
  }

  private void expectedSaved() {
    var value = StructuredExample.settings().runtime().orElseThrow().view().value().orElseThrow();
    verify(
        "saved native recursive values match the authored profile",
        value.numbers().equals(List.of(2, 9, 9))
            && value.roles().equals(Map.of("home", 4))
            && value.tags().equals(Set.of("base"))
            && value.note().equals(Optional.of("remember"))
            && value.retries() == 5
            && value.mode().equals(new StructuredExample.Counter(6)));
  }

  boolean tick() throws Exception {
    if (!ready()) return false;
    switch (stage) {
      case 0 -> {
        if (reopen) {
          expectedSaved();
          verify(
              "recursive profile reopens clean in another JVM",
              !form.editorView().dirty() && form.editorView().validation().valid());
          form.onClose();
          verify("clean recursive close returns parent", client.screen == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        if (form.fieldControl(AMOUNT).isEmpty()) return false;
        verify(
            "initial recursive profile is valid",
            !form.editorView().dirty() && form.editorView().validation().valid());
        verify(
            "inactive optional/default controls are not exposed",
            form.fieldControl(NOTE_VALUE).isEmpty() && form.fieldControl(RETRY_VALUE).isEmpty());
        number = rows(NUMBERS).rows().getFirst().id();
        numberPath = NUMBERS.row(number);
        numberControl = field(numberPath);
        original = Files.readString(path());
        replace(form, numberControl, "-");
        step("recursive numeric input staged through native text events");
      }
      case 1 -> {
        if (form.editorView().validation().valid()) return false;
        verify(
            "invalid nested input remains visible and blocks Save",
            raw(numberPath).equals("-")
                && numberControl.getValue().equals("-")
                && !button(form, "Save and apply").active);
        verify("invalid nested typing does not write", Files.readString(path()).equals(original));
        capture.accept("form-structured-invalid.png", () -> replace(form, numberControl, "9"));
        step("nested diagnostics captured without losing row identity");
      }
      case 2 -> {
        if (!form.editorView().validation().valid() || !raw(numberPath).equals("9")) return false;
        press(action("Numbers: Entry 1: Copy"));
        step("native list row copied");
      }
      case 3 -> {
        if (rows(NUMBERS).rows().size() != 3) return false;
        verify(
            "copy receives distinct stable identity",
            !rows(NUMBERS).rows().getLast().id().equals(number));
        press(action("Numbers: Entry 1: Down"));
        step("native list row moved by stable anchor");
      }
      case 4 -> {
        if (!rows(NUMBERS).rows().get(1).id().equals(number)) return false;
        verify(
            "moving preserves actual scalar widget",
            field(numberPath) == numberControl && numberControl.getValue().equals("9"));
        var entry = ((DraftNode.Mapping) at(ROLES)).rows().getFirst();
        mapKey = ROLES.row(entry.id()).append(FieldPath.Part.KEY);
        mapValue = ROLES.row(entry.id()).append(FieldPath.Part.VALUE);
        replace(form, field(mapKey), "home");
        replace(form, field(mapValue), "4");
        step("native map key/value authored separately");
      }
      case 5 -> {
        if (!raw(mapKey).equals("home") || !raw(mapValue).equals("4")) return false;
        press(action("Tags: Entry 1: Copy"));
        step("native set duplicate retained for repair");
      }
      case 6 -> {
        if (rows(TAGS).rows().size() != 2 || form.editorView().validation().valid()) return false;
        verify(
            "duplicate set rows expose related diagnostics",
            form.editorView().validation().issues().stream()
                .anyMatch(issue -> !issue.related().isEmpty()));
        press(action("Tags: Entry 2: Remove"));
        step("duplicate row explicitly removed");
      }
      case 7 -> {
        if (!form.editorView().validation().valid()) return false;
        press(action("Note: Add value"));
        step("optional value activated");
      }
      case 8 -> {
        if (form.fieldControl(NOTE_VALUE).isEmpty()) return false;
        replace(form, field(NOTE_VALUE), "remember");
        step("optional input authored");
      }
      case 9 -> {
        if (!raw(NOTE_VALUE).equals("remember")) return false;
        press(action("Note: Use empty"));
        step("optional membership disabled");
      }
      case 10 -> {
        if (((DraftNode.OptionalValue) at(NOTE)).present()) return false;
        verify(
            "inactive optional field is hidden without data loss",
            form.fieldControl(NOTE_VALUE).isEmpty() && raw(NOTE_VALUE).equals("remember"));
        press(action("Note: Add value"));
        step("optional input restored");
      }
      case 11 -> {
        if (form.fieldControl(NOTE_VALUE).isEmpty()) return false;
        verify(
            "optional reactivation restores exact input",
            field(NOTE_VALUE).getValue().equals("remember"));
        press(action("Retries: Override"));
        step("declared default override enabled");
      }
      case 12 -> {
        if (form.fieldControl(RETRY_VALUE).isEmpty()) return false;
        retiredOverride = field(RETRY_VALUE);
        replace(form, retiredOverride, "-");
        step("invalid override retained");
      }
      case 13 -> {
        if (form.editorView().validation().valid()) return false;
        press(action("Retries: Use default"));
        step("default used without discarding invalid override");
      }
      case 14 -> {
        if (((DraftNode.Defaulted) at(RETRIES)).overridden()) return false;
        verify(
            "default restores validity and retains input",
            form.editorView().validation().valid() && raw(RETRY_VALUE).equals("-"));
        press(action("Retries: Override"));
        step("invalid override reactivated");
      }
      case 15 -> {
        if (form.fieldControl(RETRY_VALUE).isEmpty()) return false;
        verify("reactivation keeps invalid input", field(RETRY_VALUE).getValue().equals("-"));
        retiredOverride.setValue("88");
        verify("retired widget cannot edit replacement control", raw(RETRY_VALUE).equals("-"));
        replace(form, field(RETRY_VALUE), "5");
        step("default override repaired");
      }
      case 16 -> {
        if (!form.editorView().validation().valid() || !raw(RETRY_VALUE).equals("5")) return false;
        retiredCounter = field(AMOUNT);
        replace(form, retiredCounter, "-");
        step("invalid active variant authored");
      }
      case 17 -> {
        if (form.editorView().validation().valid()) return false;
        press(variant("Counter"));
        step("native variant changed");
      }
      case 18 -> {
        var label = MODE.variant("named").field("label");
        if (form.fieldControl(label).isEmpty()) return false;
        verify(
            "inactive invalid branch does not block selected branch",
            form.editorView().validation().valid());
        replace(form, field(label), "alternate");
        step("other variant authored");
      }
      case 19 -> {
        if (!raw(MODE.variant("named").field("label")).equals("alternate")) return false;
        press(variant("Named"));
        step("original variant restored");
      }
      case 20 -> {
        if (form.fieldControl(AMOUNT).isEmpty()) return false;
        verify(
            "original variant restores invalid input",
            raw(AMOUNT).equals("-") && field(AMOUNT).getValue().equals("-"));
        retiredCounter.setValue("88");
        verify("inactive widget cannot target restored branch", raw(AMOUNT).equals("-"));
        replace(form, field(AMOUNT), "6");
        step("selected variant repaired");
      }
      case 21 -> {
        if (!form.editorView().validation().valid() || !raw(AMOUNT).equals("6")) return false;
        verify(
            "all recursive draft edits leave disk unchanged",
            Files.readString(path()).equals(original));
        verify(
            "public field focus reveals nested input",
            form.focusField(AMOUNT) && field(AMOUNT).isFocused());
        geometry(form);
        capture.accept(
            "form-structured-ready.png", () -> click(form, button(form, "Save and apply")));
        step("recursive draft reviewed before explicit Save");
      }
      case 22 -> {
        if (form.editorView().lastReceipt().isEmpty() || form.editorView().dirty()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        verify(
            "native recursive Save confirms persistence/publication",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.APPLIED);
        expectedSaved();
        form.onClose();
        verify(
            "recursive close releases editor and restores parent",
            client.screen == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unexpected recursive stage");
    }
    return false;
  }

  JsonObject report() {
    return report;
  }
}
