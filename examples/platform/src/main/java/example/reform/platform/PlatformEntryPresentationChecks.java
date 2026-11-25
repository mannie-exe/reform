package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.value.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Measured entry slots over an actual owner and native controls, including failed artwork. */
final class PlatformEntryPresentationChecks implements PlatformFormProbe.Scene {
  record Preferences(String label, int count) {}

  private static final FieldPath LABEL_PATH = FieldPath.ROOT.field("label");
  private static final FieldPath COUNT_PATH = FieldPath.ROOT.field("count");
  private static final FieldKey<String> LABEL = FieldKey.of("label", ValueTypes.text(256));
  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final DocumentDefinition<Preferences> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_platform_example:entry_preferences"),
          1,
          RecordType.<Preferences>builder(
                  values -> new Preferences(values.get(LABEL), values.get(COUNT)))
              .field(LABEL, Preferences::label)
              .field(COUNT, Preferences::count)
              .build(),
          () -> new Preferences("Example", 1));
  private static ClientFileSettings<Preferences> settings;

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(registration, "ENTRY_SLOTS", PlatformEntryPresentationChecks::new);
  }

  private final PlatformFormProbe.Driver driver;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final JsonArray checks = new JsonArray();
  private final RuntimeException paintFailure =
      new IllegalStateException("controlled entry background failure");
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private Path source;
  private byte[] original;
  private int stage, creates, closes, leadingDraws, backgroundDraws;
  private boolean failBackground, complete;
  private FormGeometry.Placement leadingBounds, backgroundBounds;
  private EditBox label, count;
  private long version;
  private final int[] width = {0}, height = {0};
  private int guiWidth, guiHeight;

  private PlatformEntryPresentationChecks(PlatformFormProbe.Driver driver) {
    this.driver = driver;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private ClientEntryGraphic graphic(ClientViewContext context, boolean leading) {
    creates++;
    return new ClientEntryGraphic() {
      public void render(
          FormCanvas canvas,
          net.minecraft.client.gui.Font font,
          FormGeometry.Placement bounds,
          ClientTheme theme) {
        context.requireCurrent();
        if (leading) {
          leadingDraws++;
          leadingBounds = bounds;
          // Deliberate overscan verifies that host clipping owns the slot boundary.
          canvas.fill(
              bounds.x() - 8,
              bounds.y() - 8,
              bounds.x() + bounds.width() + 8,
              bounds.y() + bounds.height() + 8,
              0xff40a0b0);
        } else {
          backgroundDraws++;
          backgroundBounds = bounds;
          if (failBackground) throw paintFailure;
          canvas.fill(
              bounds.x(),
              bounds.y(),
              bounds.x() + bounds.width(),
              bounds.y() + bounds.height(),
              0x40304060);
        }
      }

      public void close() {
        context.checkNativeThread();
        closes++;
      }
    };
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) editor.view().snapshot().orElseThrow().draft().at(path)).raw();
  }

  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (settings.runtime().isEmpty()) return false;
        source =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/entry_preferences.json");
        original = Files.readAllBytes(source);
        var entry =
            ClientEntryPresentation.EMPTY
                .withEyebrow(Component.literal("Display preference"))
                .withLeading(
                    new ClientEntryPresentation.Leading(
                        new FormGeometry.Size(48, 32),
                        Component.literal("A teal illustration marks this preference."),
                        context -> graphic(context, true)))
                .withBackground(context -> graphic(context, false));
        var options =
            new ClientFormOptions<Preferences>(FormPresentation.EMPTY, Map.of())
                .withEntry(LABEL_PATH, entry)
                .withDefaults(Component.literal("Restore preference defaults"), Component.empty())
                .withControl(
                    LABEL_PATH,
                    binding ->
                        new ClientControlAdapter.Control(
                            binding.nativeControl(),
                            new FormGeometry.Size(180, 20),
                            ignored -> {}));
        try {
          options.withEntry(LABEL_PATH, entry);
          throw new AssertionError("Duplicate entry declaration admitted");
        } catch (IllegalArgumentException expected) {
          check("duplicate entry slots refuse before factory entry", creates == 0);
        }
        try {
          new ClientEntryPresentation.Leading(
              new FormGeometry.Size(0, 32),
              Component.literal("Invalid"),
              context -> graphic(context, true));
          throw new AssertionError("Empty graphic offer admitted");
        } catch (IllegalArgumentException expected) {
          check("unusable graphic offer refuses before allocation", creates == 0);
        }
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Entry slot workspace"),
                List.of(settings.form(Component.literal("Entry preferences"), options)));
        NativeScreens.open(client, workflow.screen());
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Document(DEFINITION.id()))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!navigation.isDone() || leadingDraws == 0 || backgroundDraws == 0) return false;
        if (navigation.join().outcome() != NavigationAttempt.Outcome.REVEALED)
          throw new AssertionError("Entry destination refused");
        editor = workflow.editor(DEFINITION.id());
        if (!editor.canSubmit()) return false;
        label = (EditBox) editor.form().fieldControl(LABEL_PATH).orElseThrow();
        count = (EditBox) editor.form().fieldControl(COUNT_PATH).orElseThrow();
        check(
            "optional slots mount once over the same native input and owner",
            creates == 2 && closes == 0 && label.getValue().equals("Example") && !editor.dirty());
        check(
            "entry eyebrow and graphic alternative remain readable beside required text",
            measuredText(workflow.screen()).stream()
                    .anyMatch(text -> text.contains("Display preference") && text.contains("Label"))
                && measuredText(workflow.screen()).stream()
                    .anyMatch(text -> text.contains("teal illustration")));
        check(
            "leading artwork is measured outside the native input",
            leadingBounds.x() + leadingBounds.width() <= label.getX()
                && leadingBounds.width() <= 48
                && leadingBounds.height() <= 32);
        geometry(workflow.screen());
        driver.capture("entry-wide.png", () -> stage = 2);
        stage = 99;
      }
      case 2 -> {
        replace(workflow.screen(), label, "Edited label");
        replace(workflow.screen(), count, "-");
        stage = 3;
      }
      case 3 -> {
        if (editor.busy()
            || !raw(COUNT_PATH).equals("-")
            || !raw(LABEL_PATH).equals("Edited label")) return false;
        version = editor.view().snapshot().orElseThrow().version();
        check(
            "artwork does not replace invalid input or its valid dirty sibling",
            !editor.canSubmit());
        check("native entry focus remains on the actual input", focus(workflow.screen(), label));
        label.setCursorPosition(6);
        label.setHighlightPos(3);
        NativeClientProbe.size(client, width, height);
        guiWidth = workflow.screen().width;
        guiHeight = workflow.screen().height;
        NativeClientProbe.resize(client, 320, 240);
        stage = 4;
      }
      case 4 -> {
        if (workflow.screen().width >= guiWidth || workflow.screen().width > 400) return false;
        geometry(workflow.screen());
        check(
            "narrow field slots preserve native identity, draft, caret, and selection",
            editor.form().fieldControl(LABEL_PATH).orElseThrow() == label
                && label.isFocused()
                && label.getCursorPosition() == 6
                && label.getHighlighted().equals("ted")
                && raw(COUNT_PATH).equals("-")
                && raw(LABEL_PATH).equals("Edited label")
                && editor.view().snapshot().orElseThrow().version() == version
                && creates == 2);
        driver.capture(
            "entry-narrow.png",
            () -> {
              NativeClientProbe.resize(client, width[0], height[0]);
              stage = 5;
            });
        stage = 99;
      }
      case 5 -> {
        if (workflow.screen().width != guiWidth || workflow.screen().height != guiHeight)
          return false;
        check(
            "return reflow keeps the same owner and does not recreate artwork",
            workflow.editor(DEFINITION.id()) == editor && creates == 2);
        failBackground = true;
        stage = 6;
      }
      case 6 -> {
        if (editor.form().controlReport().issues().stream()
            .noneMatch(issue -> issue.code().equals("presentation.entry_background_failed")))
          return false;
        check(
            "failed artwork retains its actual cause while native editing survives",
            editor.form().controlFailure().orElseThrow() == paintFailure
                && closes == 1
                && raw(COUNT_PATH).equals("-")
                && raw(LABEL_PATH).equals("Edited label")
                && editor.view().snapshot().orElseThrow().version() == version
                && editor.form().fieldControl(LABEL_PATH).isPresent());
        check(
            "failed artwork feedback remains beside the field",
            measuredText(workflow.screen()).stream()
                .anyMatch(text -> text.contains("Field artwork failed")));
        driver.capture("entry-failed.png", () -> stage = 7);
        stage = 99;
      }
      case 7 -> {
        check(
            "artwork does not write the source",
            Arrays.equals(original, Files.readAllBytes(source)));
        workflow.close();
        NativeScreens.open(client, parent);
        check(
            "owner retirement attempts every graphic cleanup exactly once",
            closes == creates && creates == 2 && !editor.isOpen());
        complete = true;
        return true;
      }
      case 99 -> {}
      default -> throw new AssertionError("Unknown entry stage " + stage);
    }
    return false;
  }

  public JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.addProperty("nativeStage", stage);
    report.addProperty("factories", creates);
    report.addProperty("closes", closes);
    report.addProperty("leadingDraws", leadingDraws);
    report.addProperty("backgroundDraws", backgroundDraws);
    report.add("checks", checks.deepCopy());
    return report;
  }

  public void close() {
    if (workflow != null && workflow.isOpen()) workflow.close();
  }
}
