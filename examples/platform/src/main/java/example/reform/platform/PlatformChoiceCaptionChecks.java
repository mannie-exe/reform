package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.*;
import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Known labels over one actual file owner, with raw invalid input and external source retained. */
final class PlatformChoiceCaptionChecks implements PlatformFormProbe.Scene {
  enum Mode {
    AUTO,
    ON,
    OFF
  }

  record Preferences(Mode mode, int count) {}

  private static final FieldPath MODE_PATH = FieldPath.ROOT.field("mode");
  private static final FieldPath COUNT_PATH = FieldPath.ROOT.field("count");
  private static final FieldKey<Mode> MODE =
      FieldKey.of(
          "mode",
          ValueTypes.choiceDisplay(
              ValueTypes.enumeration(Mode.class),
              new ChoiceDisplay(
                  List.of(
                      new ChoiceDisplay.Caption(
                          "AUTO", "reform_platform_example.choice.auto", "Auto"),
                      new ChoiceDisplay.Caption("ON", "reform_platform_example.choice.on", "On"),
                      new ChoiceDisplay.Caption(
                          "OFF", "reform_platform_example.choice.off", "Off")))));
  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final DocumentDefinition<Preferences> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_platform_example:choice_preferences"),
          1,
          RecordType.<Preferences>builder(v -> new Preferences(v.get(MODE), v.get(COUNT)))
              .field(MODE, Preferences::mode)
              .field(COUNT, Preferences::count)
              .build(),
          () -> new Preferences(Mode.AUTO, 1));
  private static ClientFileSettings<Preferences> settings;

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(registration, "CHOICE_CAPTIONS", PlatformChoiceCaptionChecks::new);
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final PlatformFormProbe.Driver driver;
  private final JsonArray checks = new JsonArray();
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private Object actions;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private CompletableFuture<?> edit;
  private byte[] original;
  private Path source, imported;
  private int stage;
  private boolean externalWritten, externalObserved;

  private PlatformChoiceCaptionChecks(PlatformFormProbe.Driver driver) {
    this.driver = driver;
  }

  private void check(String title, boolean success) {
    if (!success) throw new AssertionError(title);
    checks.add(title);
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) editor.view().snapshot().orElseThrow().draft().at(path)).raw();
  }

  private void enter(ClientDestination target) {
    navigation = workflow.navigation().open(target).completion().toCompletableFuture();
  }

  private boolean revealed() {
    if (!navigation.isDone()) return false;
    if (navigation.join().outcome() != NavigationAttempt.Outcome.REVEALED)
      throw new AssertionError("Caption destination refused: " + navigation.join());
    return true;
  }

  private String rendered() {
    return String.join("\n", measuredText(workflow.screen()));
  }

  private void capture(String name, int next) {
    driver.capture(name, () -> stage = next);
    stage = 99;
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (settings.runtime().isEmpty()) return false;
        source =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/choice_preferences.json");
        original = Files.readAllBytes(source);
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Choice caption workspace"),
                List.of(settings.form(Component.literal("Choice preferences"))));
        NativeScreens.open(client, workflow.screen());
        enter(new ClientDestination.Document(DEFINITION.id()));
        stage = 1;
      }
      case 1 -> {
        if (!revealed()) return false;
        editor = workflow.editor(DEFINITION.id());
        if (editor.busy() || editor.form().fieldControl(MODE_PATH).isEmpty()) return false;
        actions = editor.form().editingActions();
        var control = editor.form().fieldControl(MODE_PATH).orElseThrow();
        check(
            "native known choice uses its owner label without changing canonical AUTO",
            control.getMessage().getString().equals("Auto") && raw(MODE_PATH).equals("AUTO"));
        check(
            "known choice is reached by actual native focus", editor.form().focusField(MODE_PATH));
        check(
            "native key chooses the next declared value",
            NativeClientProbe.key(workflow.screen(), "key.keyboard.space", true));
        NativeClientProbe.key(workflow.screen(), "key.keyboard.space", false);
        ((EditBox) editor.form().fieldControl(COUNT_PATH).orElseThrow()).setValue("7");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !raw(MODE_PATH).equals("ON") || !raw(COUNT_PATH).equals("7"))
          return false;
        check(
            "actual choice edit keeps canonical ON and valid sibling while displaying On",
            editor
                    .form()
                    .fieldControl(MODE_PATH)
                    .orElseThrow()
                    .getMessage()
                    .getString()
                    .equals("On")
                && editor.form().editingActions() == actions
                && Arrays.equals(original, Files.readAllBytes(source)));
        capture("choice-controls.png", 3);
      }
      case 3 -> {
        enter(new ClientDestination.Inspection(DEFINITION.id()));
        stage = 4;
      }
      case 4 -> {
        if (!revealed() || workflow.inspection().flatMap(ClientInspection::reviewedInput).isEmpty())
          return false;
        var reviewed = workflow.inspection().orElseThrow().reviewedInput().orElseThrow();
        var value =
            reviewed.entries().stream()
                .filter(e -> e.path().equals(MODE_PATH))
                .findFirst()
                .orElseThrow();
        check(
            "actual inspection retains raw baseline and draft with typed owner labels",
            value.baseline().equals("\"AUTO\"")
                && value.draft().equals("\"ON\"")
                && value.choiceValues().baseline().orElseThrow().fallback().equals("Auto")
                && value.choiceValues().draft().orElseThrow().fallback().equals("On")
                && rendered().contains("Auto (\"AUTO\")")
                && rendered().contains("On (\"ON\")")
                && workflow.editor(DEFINITION.id()) == editor);
        capture("choice-review.png", 5);
      }
      case 5 -> {
        enter(new ClientDestination.Document(DEFINITION.id()));
        stage = 43;
      }
      case 43 -> {
        if (!revealed() || editor.busy() || !editor.canSubmit()) return false;
        check(
            "submission review starts from the actual current field",
            editor.form().focusField(MODE_PATH));
        var review = button(workflow.screen(), "Review changes…");
        if (!review.active) return false;
        click(workflow.screen(), review);
        stage = 44;
      }
      case 44 -> {
        if (workflow.inspection().flatMap(ClientInspection::reviewedInput).isEmpty()) return false;
        var text = rendered();
        check(
            "submission review keeps selected typed values ahead of the full change list",
            workflow.inspection().orElseThrow().selectedField().orElseThrow().equals(MODE_PATH)
                && text.contains("Whole document, including settings outside the current section.")
                && text.indexOf("Draft at capture") >= 0
                && text.indexOf("Draft at capture") < text.indexOf(" → ")
                && text.contains("On (\"ON\")")
                && workflow.editor(DEFINITION.id()) == editor
                && Arrays.equals(original, Files.readAllBytes(source)));
        capture("choice-submission.png", 45);
      }
      case 45 -> {
        enter(new ClientDestination.Document(DEFINITION.id()));
        stage = 40;
      }
      case 40 -> {
        if (!revealed() || editor.busy()) return false;
        workflow.reviewAll();
        stage = 41;
      }
      case 41 -> {
        var batch = workflow.batchInspection().flatMap(ClientBatchInspection::reviewedInput);
        if (batch.isEmpty()) return false;
        var value =
            batch.orElseThrow().entries().stream()
                .filter(entry -> entry.document().equals(DEFINITION.id()))
                .findFirst()
                .orElseThrow()
                .review()
                .entries()
                .stream()
                .filter(entry -> entry.path().equals(MODE_PATH))
                .findFirst()
                .orElseThrow();
        check(
            "actual complete Batch view projects typed choice captions without another owner or write",
            value.baseline().equals("\"AUTO\"")
                && value.draft().equals("\"ON\"")
                && value.choiceValues().draft().orElseThrow().fallback().equals("On")
                && rendered().contains("On (\"ON\")")
                && workflow.editor(DEFINITION.id()) == editor
                && editor.form().editingActions() == actions
                && Arrays.equals(original, Files.readAllBytes(source)));
        capture("choice-batch.png", 42);
      }
      case 42 -> {
        enter(new ClientDestination.Document(DEFINITION.id()));
        stage = 6;
      }
      case 6 -> {
        if (!revealed() || editor.busy()) return false;
        var information =
            Objects.requireNonNull(
                informationFor(
                    workflow.screen(), editor.form().fieldControl(MODE_PATH).orElseThrow()));
        click(workflow.screen(), information);
        stage = 7;
      }
      case 7 -> {
        if (!rendered().contains("On (\"ON\")")) return false;
        check(
            "native contextual information uses the same label and exact value",
            rendered().contains("Auto (\"AUTO\")") && editor.form().editingActions() == actions);
        capture("choice-information.png", 8);
      }
      case 8 -> {
        click(workflow.screen(), button(workflow.screen(), "Close information"));
        var target = editor.view().snapshot().orElseThrow().target(MODE_PATH).typing();
        edit =
            DocumentActions.result(
                    editor
                        .actions()
                        .edit(
                            OperationDescriptor.EntryPoint.GUI,
                            new DraftEdit(target, new DraftEdit.SetRaw("unknown mode"))))
                .toCompletableFuture();
        stage = 9;
      }
      case 9 -> {
        if (!edit.isDone()
            || editor.busy()
            || !raw(MODE_PATH).equals("unknown mode")
            || editor.form().fieldControl(MODE_PATH).filter(EditBox.class::isInstance).isEmpty())
          return false;
        edit.join();
        var control = editor.form().fieldControl(MODE_PATH).orElseThrow();
        check(
            "unknown choice stays raw and directly repairable rather than gaining a guessed label",
            control instanceof EditBox input
                && input.getValue().equals("unknown mode")
                && raw(COUNT_PATH).equals("7")
                && !editor.canSubmit());
        capture("choice-invalid.png", 10);
      }
      case 10 -> {
        ((EditBox) editor.form().fieldControl(MODE_PATH).orElseThrow()).setValue("ON");
        stage = 11;
      }
      case 11 -> {
        if (editor.busy()
            || !editor.canSubmit()
            || !raw(MODE_PATH).equals("ON")
            || editor
                .form()
                .fieldControl(MODE_PATH)
                .filter(net.minecraft.client.gui.components.CycleButton.class::isInstance)
                .isEmpty()) return false;
        check(
            "native repair returns to the labeled known choice without changing source",
            editor
                    .form()
                    .fieldControl(MODE_PATH)
                    .orElseThrow()
                    .getMessage()
                    .getString()
                    .equals("On")
                && Arrays.equals(original, Files.readAllBytes(source)));
        imported = client.gameDirectory.toPath().toAbsolutePath().resolve("choice-import.json");
        Files.writeString(imported, "{\"mode\":\"OFF\",\"count\":7}");
        enter(new ClientDestination.Import(DEFINITION.id()));
        stage = 12;
      }
      case 12 -> {
        if (!revealed() || !button(workflow.screen(), "Choose file…").active) return false;
        check(
            "import fixture supplies an actual admitted absolute local file",
            imported.isAbsolute() && Files.isRegularFile(imported));
        workflow.screen().onFilesDrop(List.of(imported));
        stage = 13;
      }
      case 13 -> {
        var preview = button(workflow.screen(), "Preview import");
        if (!preview.active) return false;
        click(workflow.screen(), preview);
        stage = 14;
      }
      case 14 -> {
        if (workflow.importInspection().flatMap(ClientImportInspection::reviewedInput).isEmpty())
          return false;
        var comparison = workflow.importInspection().orElseThrow().reviewedInput().orElseThrow();
        var change =
            comparison.changes().stream()
                .filter(c -> c.path().equals(MODE_PATH))
                .findFirst()
                .orElseThrow();
        check(
            "actual import comparison displays typed captions without staging or replacing raw JSON",
            change.beforeJson().equals("\"ON\"")
                && change.afterJson().equals("\"OFF\"")
                && change.beforeChoiceCaption().orElseThrow().fallback().equals("On")
                && change.afterChoiceCaption().orElseThrow().fallback().equals("Off")
                && rendered().contains("On (\"ON\")")
                && rendered().contains("Off (\"OFF\")")
                && raw(MODE_PATH).equals("ON")
                && Arrays.equals(original, Files.readAllBytes(source)));
        capture("choice-import.png", 15);
      }
      case 15 -> {
        enter(new ClientDestination.Document(DEFINITION.id()));
        stage = 16;
      }
      case 16 -> {
        if (!revealed() || editor.busy()) return false;
        var external =
            JsonParser.parseString(new String(original, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        external.getAsJsonObject("values").addProperty("mode", "OFF");
        Files.writeString(source, external.toString());
        externalWritten = true;
        externalObserved = true;
        click(workflow.screen(), ownerAction(workflow.screen(), "Reload saved"));
        stage = 17;
      }
      case 17 -> {
        if (editor.busy() || editor.view().review().isEmpty()) return false;
        if (workflow.conflictInspection().isEmpty()) {
          var inspect = button(workflow.screen(), "Review conflict…");
          if (!inspect.active) return false;
          click(workflow.screen(), inspect);
        }
        stage = 18;
      }
      case 18 -> {
        if (workflow
            .conflictInspection()
            .flatMap(ClientConflictInspection::reviewedInput)
            .isEmpty()) return false;
        var compared = workflow.conflictInspection().orElseThrow().reviewedInput().orElseThrow();
        var value =
            compared.entries().stream()
                .filter(e -> e.path().equals(MODE_PATH))
                .findFirst()
                .orElseThrow();
        check(
            "actual source conflict retains all three exact values and their independent labels",
            value.baseline().equals("\"AUTO\"")
                && value.observed().equals("\"OFF\"")
                && value.draft().equals("\"ON\"")
                && value.baselineChoiceCaption().orElseThrow().fallback().equals("Auto")
                && value.observedChoiceCaption().orElseThrow().fallback().equals("Off")
                && value.draftChoiceCaption().orElseThrow().fallback().equals("On")
                && rendered().contains("Auto (\"AUTO\")")
                && rendered().contains("Off (\"OFF\")")
                && rendered().contains("On (\"ON\")")
                && editor.form().editingActions() == actions);
        capture("choice-conflict.png", 19);
      }
      case 19 -> {
        Files.write(source, original);
        externalWritten = false;
        workflow.close();
        check(
            "caption qualification restores its intentional external edit and closes the same owner",
            !workflow.isOpen()
                && !editor.isOpen()
                && Arrays.equals(original, Files.readAllBytes(source)));
        NativeScreens.open(client, parent);
        stage = 20;
      }
      case 20 -> {
        return true;
      }
      case 99 -> {}
      default -> throw new AssertionError("Unknown caption stage");
    }
    return false;
  }

  @Override
  public String diagnostic() {
    return "choice captions stage=" + stage;
  }

  @Override
  public JsonObject report() {
    var value = new JsonObject();
    value.addProperty("status", "PASS");
    value.add("checks", checks.deepCopy());
    value.addProperty("intentionalExternalSourceEdit", externalObserved);
    value.addProperty("nativeStage", stage);
    if (imported != null) {
      value.addProperty("importPath", imported.toString());
      value.addProperty("importAbsolute", imported.isAbsolute());
    }
    if (workflow != null && workflow.isOpen()) {
      value.addProperty("renderedText", rendered());
      var nativeControls = new JsonArray();
      for (var widget : widgets(workflow.screen())) {
        var control = new JsonObject();
        control.addProperty("type", widget.getClass().getName());
        control.addProperty("message", widget.getMessage().getString());
        control.addProperty("active", widget.active);
        nativeControls.add(control);
      }
      value.add("nativeControls", nativeControls);
    }
    return value;
  }

  @Override
  public void close() {
    if (workflow != null) workflow.close();
    try {
      if (externalWritten && original != null) Files.write(source, original);
    } catch (java.io.IOException failed) {
      throw new IllegalStateException("Cannot restore caption fixture", failed);
    }
    NativeScreens.open(client, parent);
  }
}
