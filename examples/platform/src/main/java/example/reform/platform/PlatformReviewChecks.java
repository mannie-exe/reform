package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.ReviewScreen;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.operation.DocumentReview;
import design.inherent.reform.owner.EditorActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native source inspection and related-field return over an ordinary, unsaved public editor. */
final class PlatformReviewChecks implements PlatformFormProbe.Scene {
  private static PlatformReviewChecks active;

  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "REVIEW",
        driver -> {
          active = new PlatformReviewChecks(reopen, driver::progress, driver::capture);
          return active;
        });
  }

  static DraftRevision guiRevision() {
    return active.observed;
  }

  static boolean guiClosed() {
    return active.form.editorView().availability() == EditorActor.Availability.CLOSED;
  }

  static boolean sourceUnchanged() throws java.io.IOException {
    return Files.readString(active.path()).equals(active.original);
  }

  public String diagnostic() {
    return "review stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName();
  }

  private DraftRevision observed;

  private static final FieldPath MIN = PlatformCatalogExample.GLOBAL.field("minimum");
  private static final FieldPath MAX = PlatformCatalogExample.GLOBAL.field("maximum");
  private static final String LONG_NAME = "Alpha review " + "x".repeat(100);
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final SettingsScreen<PlatformCatalogExample.Profiles> form;
  private final JsonArray checks = new JsonArray();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final String original;
  private String originalName;
  private ReviewScreen<?> view, retiredView;
  private FieldPath first;
  private Button retiredDestination;
  private EditBox filter;
  private DocumentReview captured;
  private int stage;
  private int nameCharacters;
  private boolean complete;

  @SuppressWarnings("unchecked") // Ready public factory uses this exact definition.
  PlatformReviewChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture)
      throws Exception {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    var witness = client.gameDirectory.toPath().resolve("native-review-original.json");
    if (!reopen) Files.writeString(witness, Files.readString(path()));
    original = Files.readString(witness);
    form =
        (SettingsScreen<PlatformCatalogExample.Profiles>)
            PlatformCatalogExample.settings()
                .screen(
                    parent,
                    Component.literal("Inspection parent"),
                    PlatformCatalogExample.PRESENTATION);
    NativeScreens.open(client, form);
  }

  private Path path() {
    return client.gameDirectory.toPath().resolve("config/reform_platform_example/catalog.json");
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
    return NativeScreens.current(client) == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && button(form, "Done").active;
  }

  private DraftNode node(FieldPath path) {
    return form.editorView().snapshot().orElseThrow().draft().at(path);
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) node(path)).raw();
  }

  private EditBox field(FieldPath path) {
    return (EditBox) form.fieldControl(path).orElseThrow();
  }

  private void press(Button control) {
    check(
        "native inspection control receives focus", focus(NativeScreens.current(client), control));
    click(NativeScreens.current(client), control);
    check(
        "callback focus remains in the active tree",
        NativeScreens.current(client).getFocused() == null
            || NativeScreens.current(client)
                .children()
                .contains(NativeScreens.current(client).getFocused()));
  }

  private CycleButton<?> fieldChoice() {
    return widgets(view).stream()
        .filter(CycleButton.class::isInstance)
        .map(CycleButton.class::cast)
        .filter(value -> value.getValue() instanceof FieldPath)
        .findFirst()
        .orElseThrow();
  }

  private EditBox search() {
    return input(
        view,
        Component.translatableWithFallback("reform.review.search", "Find a field").getString());
  }

  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!ready()) return false;
        observed = DraftRevision.of(form.editorView().snapshot().orElseThrow());
        first =
            PlatformCatalogExample.PROFILES.row(
                ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES))
                    .rows()
                    .getFirst()
                    .id());
        if (reopen) {
          check(
              "fresh JVM sees no discarded inspection edits",
              Files.readString(path()).equals(original)
                  && raw(MIN).equals("1")
                  && ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows().size() == 2
                  && !form.editorView().dirty());
          press(button(form, "Done"));
          check(
              "reopened source closes without rewriting",
              form.editorView().availability() == EditorActor.Availability.CLOSED
                  && Files.readString(path()).equals(original));
          complete = true;
          return true;
        }
        check(
            "profile can be selected through actual public navigation", form.selectSection(first));
        originalName = raw(first.field("name"));
        var name = field(first.field("name"));
        check("long text input receives native focus", focus(form, name));
        name.moveCursorToEnd(false);
        name.setHighlightPos(0);
        next("inspection retains unsaved profile text");
      }
      case 1 -> {
        if (!ready()) return false;
        if (nameCharacters < LONG_NAME.length()) {
          // Real typing yields to owner completion; a same-tick 113-event burst exhausts its
          // intentionally bounded operation queue. Keep the long-value test without bypassing it.
          int end = Math.min(LONG_NAME.length(), nameCharacters + 4);
          for (; nameCharacters < end; nameCharacters++)
            check(
                "paced native text event is consumed",
                NativeClientProbe.character(form, LONG_NAME.charAt(nameCharacters)));
          return false;
        }
        if (!raw(first.field("name")).equals(LONG_NAME)) return false;
        check("range field can be selected", form.focusField(MIN));
        replace(form, field(MIN), "9");
        next("cross-field invalid input entered");
      }
      case 2 -> {
        if (!ready() || form.editorView().validation().valid()) return false;
        geometry(form);
        check("inspection stays available for invalid draft", button(form, "Inspect…").active);
        press(button(form, "Inspect…"));
        check(
            "status entry opens native inspection",
            NativeScreens.current(client) instanceof ReviewScreen<?>);
        view = (ReviewScreen<?>) NativeScreens.current(client);
        next("native inspection requested");
      }
      case 3 -> {
        if (view.reviewedInput().isEmpty()) return false;
        captured = view.reviewedInput().orElseThrow();
        check(
            "view and parent share one actual draft",
            captured.target().equals(DraftRevision.of(form.editorView().snapshot().orElseThrow())));
        var count =
            captured.entries().stream()
                .filter(entry -> entry.path().equals(MIN))
                .findFirst()
                .orElseThrow();
        check(
            "baseline draft and effective details remain distinct",
            count.baseline().equals("\"1\"")
                && count.draft().equals("\"9\"")
                && count.effective().orElseThrow().equals("\"1\"")
                && !captured.comparison().report().valid()
                && captured.comparison().authoredChanges());
        check(
            "source has actual owned location",
            captured.source().kind().equals("json")
                && captured.source().location().orElseThrow().endsWith("catalog.json"));
        check("inspection does not persist", Files.readString(path()).equals(original));
        check("invalid reviewed draft cannot be exported", !button(view, "Export value…").active);
        check(
            "application declaration is captured separately from a completed save",
            count
                    .application()
                    .orElseThrow()
                    .timings()
                    .equals(
                        java.util.Set.of(
                            design.inherent.reform.owner.ApplicationEffects.Timing
                                .OWNER_PUBLICATION))
                && form.editorView().lastReceipt().isEmpty());
        var choices =
            widgets(view).stream()
                .filter(CycleButton.class::isInstance)
                .map(CycleButton.class::cast)
                .filter(value -> value.getValue() instanceof FieldPath)
                .toList();
        check("related diagnostic uses a bounded location control", choices.size() == 2);
        var related = choices.getLast();
        check("related diagnostic receives visible native focus", focus(view, related));
        NativeClientProbe.press(related);
        check("related control retains actual target", related.getValue().equals(MAX));
        retiredDestination = button(view, "Go to field");
        retiredView = view;
        capture.accept("form-review-diagnostics.png", () -> next("diagnostics captured"));
      }
      case 4 -> {
        press(retiredDestination);
        check(
            "related target returns to same editor and reveals its field",
            NativeScreens.current(client) == form
                && form.fieldControl(MAX).isPresent()
                && raw(MIN).equals("9")
                && raw(first.field("name")).startsWith("Alpha review"));
        check(
            "return closes only the borrowed view",
            retiredView.reviewedInput().isEmpty()
                && form.editorView().availability() == EditorActor.Availability.READY);
        NativeClientProbe.press(retiredDestination);
        check(
            "retired diagnostic callback has no navigation effect",
            NativeScreens.current(client) == form);
        replace(form, field(MIN), "2");
        next("range repair entered");
      }
      case 5 -> {
        if (!ready() || !form.editorView().validation().valid()) return false;
        check("return to profile preserves actor", form.selectSection(first));
        press(button(form, "Copy"));
        next("first explicit profile copy");
      }
      case 6 -> {
        if (!ready()
            || ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows().size() != 3)
          return false;
        press(button(form, "Copy"));
        next("second explicit profile copy");
      }
      case 7 -> {
        if (!ready()
            || ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows().size() != 4)
          return false;
        press(button(form, "Inspect…"));
        view = (ReviewScreen<?>) NativeScreens.current(client);
        next("whole collection inspection requested");
      }
      case 8 -> {
        if (view.reviewedInput().isEmpty()) return false;
        captured = view.reviewedInput().orElseThrow();
        check(
            "dynamic row resolves to declared containing collection",
            view.selectedField().filter(PlatformCatalogExample.PROFILES::equals).isPresent());
        var values =
            captured.entries().stream()
                .filter(entry -> entry.path().equals(PlatformCatalogExample.PROFILES))
                .findFirst()
                .orElseThrow();
        check(
            "large authored collection retains complete captured data",
            values.wholeValue()
                && values.draft().length() > 512
                && !values.baseline().equals(values.draft())
                && values.baseline().equals(values.effective().orElseThrow()));
        press(button(view, "Expand"));
        check(
            "expansion does not repeat source work",
            view.reviewedInput().orElseThrow() == captured);
        press(button(view, "Collapse"));
        replace(view, search(), "no match");
        check(
            "zero field matches retain selected capture",
            view.selectedField().filter(PlatformCatalogExample.PROFILES::equals).isPresent()
                && view.reviewedInput().orElseThrow() == captured);
        replace(view, search(), "minimum");
        var selector = fieldChoice();
        check("field chooser receives native focus", focus(view, selector));
        NativeClientProbe.press(selector);
        check(
            "field selector follows real metadata",
            view.selectedField().filter(MIN::equals).isPresent());
        check(
            "single-result field chooser cannot retain disabled focus",
            !fieldChoice().active && !fieldChoice().isFocused());
        filter = search();
        filter.setCursorPosition(1);
        filter.setHighlightPos(3);
        client.options.guiScale().set(3);
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        next("native inspection narrow reflow requested");
      }
      case 9 -> {
        if (view.width > 340) return false;
        check(
            "native filter survives measured reflow",
            search() == filter
                && filter.getCursorPosition() == 1
                && filter.getHighlighted().equals("in")
                && view.selectedField().filter(MIN::equals).isPresent()
                && view.reviewedInput().orElseThrow() == captured);
        geometry(view);
        check("narrow capture reveals the retained search input", focus(view, filter));
        capture.accept("form-review-narrow.png", () -> next("narrow inspection captured"));
      }
      case 10 -> {
        press(button(view, "Validate"));
        next("explicit actual owner validation requested");
      }
      case 11 -> {
        if (!button(view, "Refresh view").active) return false;
        check("actual validation does not save profile", Files.readString(path()).equals(original));
        press(button(view, "Export value…"));
        next("native value export requested from reviewed draft");
      }
      case 12 -> {
        if (!(NativeScreens.current(client)
            instanceof design.inherent.reform.minecraft.client.OperationScreen<?> output))
          throw new AssertionError("Export did not open actual operation feedback");
        var observed = output.observedOperation().orElse(null);
        if (observed == null || !observed.terminal()) return false;
        var artifact =
            (design.inherent.reform.operation.DocumentActions.ExportArtifact)
                observed.result().orElseThrow();
        var expected =
            form.editorView().snapshot().orElseThrow().inspect().candidate().orElseThrow();
        check(
            "native export retains reviewed draft and the exact authored JSON",
            artifact.draft().equals(captured.target())
                && artifact.text().equals(expected.authoredJson())
                && artifact.size() == artifact.bytes().length);
        check(
            "native export offers explicit copy and leaves disk and effective value alone",
            button(output, "Copy JSON").active
                && Files.readString(path()).equals(original)
                && form.editorView().lastReceipt().isEmpty());
        geometry(output);
        capture.accept(
            "form-review-export.png",
            () -> {
              press(button(output, "Back"));
              next("native export result captured and returned to original review");
            });
      }
      case 13 -> {
        check(
            "closing export retains the original review",
            NativeScreens.current(client) == view
                && view.reviewedInput().orElseThrow() == captured);
        check(
            "GUI review remains the same unsaved capture before native command navigation",
            view.reviewedInput().orElseThrow() == captured
                && Files.readString(path()).equals(original));
        press(button(view, "Back"));
        press(button(form, "Revert"));
        next("explicit draft revert requested");
      }
      case 14 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "revert retains source and effective values",
            raw(first.field("name")).equals(originalName)
                && raw(MIN).equals("1")
                && ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows().size() == 2
                && Files.readString(path()).equals(original));
        // Owner publication and its native presentation callback are separate boundaries.
        // Yield a native tick before the next human input; revert may also change the selected
        // catalog section, so no particular field is assumed to remain visible.
        next("reverted owner ready; awaiting native presentation tick");
      }
      case 15 -> {
        if (!ready()) return false;
        press(button(form, "Done"));
        check(
            "native inspection parent closes actual editing lifetime: " + diagnostic(),
            NativeScreens.current(client) == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown inspection stage");
    }
    return false;
  }

  public JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "PENDING");
    result.addProperty("stage", stage);
    result.addProperty(
        "editorClosed", form.editorView().availability() == EditorActor.Availability.CLOSED);
    result.add("checks", checks.deepCopy());
    return result;
  }
}
