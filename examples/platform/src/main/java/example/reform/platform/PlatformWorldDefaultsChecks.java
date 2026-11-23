package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ImportScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.minecraft.client.WorldRecipe;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;

/** Actual optional defaults and original creation context, with explicit shared import staging. */
final class PlatformWorldDefaultsChecks implements PlatformFormProbe.Scene {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen initial = NativeScreens.current(client);
  private final String mode;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final Path source;
  private final ClientFileSettings<PlatformCreationExample.Recipe> optional;
  private CreateWorldScreen creation;
  private WorldCreationContext original;
  private WorldRecipe<PlatformCreationExample.Recipe, Integer> recipe;
  private SettingsScreen<?> form;
  private ImportScreen<?> importing;
  private byte[] saved;
  private int stage;
  private boolean started, complete;

  PlatformWorldDefaultsChecks(
      String mode, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.mode = mode;
    optional =
        mode.equals("defaults-invalid")
            ? PlatformWorldDefaultsExample.invalid()
            : PlatformWorldDefaultsExample.optional();
    source =
        client
            .gameDirectory
            .toPath()
            .resolve(
                "config/reform_platform_example/"
                    + (mode.equals("defaults-invalid")
                        ? "future_invalid.json"
                        : "future_defaults.json"));
    this.progress = progress;
    this.capture = capture;
  }

  public JsonObject report() {
    var r = new JsonObject();
    r.addProperty("status", complete ? "PASS" : "RUNNING");
    r.add("checks", checks);
    r.addProperty(
        "editorClosed",
        form != null && form.editorView().availability() == EditorActor.Availability.CLOSED);
    r.addProperty(
        "factoryClosed", optional.status().lifecycle() == ClientFileSettings.Lifecycle.CLOSED);
    r.addProperty(
        "eagerClosed",
        PlatformWorldDefaultsExample.eager().status().lifecycle()
            == ClientFileSettings.Lifecycle.CLOSED);
    return r;
  }

  public String diagnostic() {
    return mode + " stage=" + stage;
  }

  public void close() {
    if (recipe != null) recipe.close();
  }

  private void check(String description, boolean value) {
    if (!value) throw new AssertionError(description);
    checks.add(description);
  }

  private void next(String description) {
    stage++;
    progress.accept(description);
  }

  private void press(Screen screen, String label) {
    click(screen, ownerAction(screen, label));
  }

  private boolean ready() {
    return NativeScreens.current(client) == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active
        && !form.editingActions().hasActiveOperations();
  }

  private String depthText() {
    return input(form, "Soil depth").getValue();
  }

  private boolean showSettings() {
    if (actionHost(form) != form) {
      returnToSettings(form);
      return false;
    }
    return ready();
  }

  private int depth(WorldCreationContext context) {
    return ((FlatLevelSource) context.selectedDimensions().overworld())
        .settings()
        .getLayersInfo()
        .get(1)
        .getHeight();
  }

  private void clickVisible(Screen screen, AbstractWidget widget) {
    for (int i = 0; i < 128 && widget.getBottom() > screen.height - 105; i++)
      screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, -1);
    check(
        "registered source consumes native mouse selection",
        NativeClientProbe.click(screen, widget));
  }

  private void openDefaults(Screen parent) {
    form = (SettingsScreen<?>) optional.screen(parent, Component.literal("Future world defaults"));
    NativeScreens.open(client, form);
  }

  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (started && !(NativeScreens.current(client) instanceof CreateWorldScreen)) return false;
        var state = optional.status();
        if (state.lifecycle() == ClientFileSettings.Lifecycle.REGISTERED
            || state.lifecycle() == ClientFileSettings.Lifecycle.INITIALIZING
            || PlatformWorldDefaultsExample.eager().status().lifecycle()
                != ClientFileSettings.Lifecycle.READY) return false;
        check(
            "simple new JSON defaults use real future-use factory",
            PlatformWorldDefaultsExample.eager()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .soilDepth()
                    == 4
                && PlatformWorldDefaultsExample.eager()
                        .status()
                        .initializationReceipt()
                        .orElseThrow()
                        .application()
                    == CommitReceipt.Application.STAGED);
        if (mode.equals("defaults-invalid")) {
          check(
              "malformed saved defaults stay unavailable",
              state.lifecycle() == ClientFileSettings.Lifecycle.UNAVAILABLE);
          check(
              "malformed defaults were never replaced",
              Files.readString(source).equals("{invalid future defaults"));
          check(
              "malformed source publishes no factory fallback",
              optional.runtime().orElseThrow().view().value().isEmpty());
          Files.writeString(source, "{\"soil_depth\":13}\n");
          var status = optional.screen(initial, Component.literal("Repair world defaults"));
          NativeScreens.open(client, status);
          press(status, "Retry");
          next("actual optional defaults source repaired through explicit Retry");
          return false;
        }
        check(
            "optional defaults owner is ready independently of a runtime value",
            state.lifecycle() == ClientFileSettings.Lifecycle.READY);
        if (mode.equals("defaults-reopen")) {
          check(
              "second JVM captures actual saved defaults",
              optional.runtime().orElseThrow().view().value().orElseThrow().soilDepth() == 11);
          saved = Files.readAllBytes(source);
          openDefaults(initial);
          next("fresh JVM opens actual future defaults");
          return false;
        }
        check("deferred setup created no optional source", !Files.exists(source));
        check(
            "deferred setup publishes no invented effective default",
            optional.runtime().orElseThrow().view().value().isEmpty());
        if (!started) {
          started = true;
          NativeRecipeProbe.open();
          progress.accept("native world-data loading requested; no playable world");
          return false;
        }
        if (!(NativeScreens.current(client) instanceof CreateWorldScreen parent)) return false;
        creation = parent;
        original = NativeRecipeProbe.flat(creation);
        check("original selected recipe has native depth two", depth(original) == 2);
        openDefaults(creation);
        next("optional defaults form is distinct from original native recipe");
      }
      case 1 -> {
        if (mode.equals("defaults-invalid")) {
          if (optional.status().lifecycle() != ClientFileSettings.Lifecycle.READY) return false;
          form = (SettingsScreen<?>) NativeScreens.current(client);
          if (!ready()) return false;
          check(
              "explicit Retry reads the repaired saved value",
              depthText().equals("13")
                  && optional.runtime().orElseThrow().view().value().orElseThrow().soilDepth()
                      == 13);
          check(
              "Retry preserves the repaired representation",
              Files.readString(source).equals("{\"soil_depth\":13}\n"));
          form.onClose();
          check(
              "repaired editor closes without closing another source",
              form.editorView().availability() == EditorActor.Availability.CLOSED);
          complete = true;
          return true;
        }
        if (!ready()) return false;
        if (mode.equals("defaults-reopen")) {
          check(
              "fresh generated form uses saved eleven, not default five", depthText().equals("11"));
          form.onClose();
          check(
              "reopening and closing saved defaults preserves bytes",
              Arrays.equals(saved, Files.readAllBytes(source)));
          complete = true;
          return true;
        }
        check(
            "unsaved declared five is an editable draft",
            depthText().equals("5") && !Files.exists(source));
        check(
            "future-defaults action never claims current world Apply",
            button(form, "Save defaults").active);
        replace(form, input(form, "Soil depth"), "9");
        next("editing future defaults creates no file or world change");
      }
      case 2 -> {
        if (!ready() || !depthText().equals("9") || !form.editorView().validation().valid())
          return false;
        check(
            "typing retains optional absence and original recipe",
            !Files.exists(source)
                && creation.getUiState().getSettings() == original
                && depth(original) == 2);
        geometry(form);
        capture.accept("form-defaults-source.png", () -> press(form, "Save defaults"));
        next("explicit Save defaults requests separate source persistence");
      }
      case 3 -> {
        if (!ready() || form.editorView().lastReceipt().isEmpty()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        check(
            "source save reports saved and staged for future worlds",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.STAGED
                && receipt.requirements().getFirst().kind()
                    == CommitReceipt.RequirementKind.NEW_WORLD);
        check(
            "saved defaults are available without changing open recipe",
            optional.runtime().orElseThrow().view().value().orElseThrow().soilDepth() == 9
                && creation.getUiState().getSettings() == original
                && depth(original) == 2);
        Files.writeString(source, "{\"soil_depth\":11}\n");
        saved = Files.readAllBytes(source);
        check(
            "external source change leaves the published-defaults cache unchanged",
            optional.runtime().orElseThrow().view().value().orElseThrow().soilDepth() == 9);
        form.onClose();
        check(
            "defaults editor returns to original creation screen",
            NativeScreens.current(client) == creation);
        recipe = PlatformCreationExample.editor(creation, original);
        var imports =
            recipe.importCatalog(
                List.of(
                    optional.savedValueSource(
                        DocumentId.parse("reform_platform_example:saved_defaults"),
                        "Saved future-world defaults")));
        form =
            (SettingsScreen<?>) recipe.screen(Component.literal("Selected native recipe"), imports);
        NativeScreens.open(client, form);
        next("original recipe opens with explicitly admitted defaults source");
      }
      case 4 -> {
        if (!ready()) return false;
        check(
            "saving defaults never replaces selected recipe on opening",
            depthText().equals("2") && !form.editorView().dirty());
        click(form, ownerAction(form, "Import…"));
        importing = (ImportScreen<?>) NativeScreens.current(client);
        next("shared import review exposes declared source metadata");
      }
      case 5 -> {
        var picker =
            widgets(importing).stream()
                .filter(CycleButton.class::isInstance)
                .map(CycleButton.class::cast)
                .filter(value -> value.getMessage().getString().equals("Choose an admitted source"))
                .findFirst()
                .orElseThrow();
        clickVisible(importing, picker);
        var selected =
            widgets(importing).stream()
                .filter(CycleButton.class::isInstance)
                .map(CycleButton.class::cast)
                .filter(
                    value -> value.getMessage().getString().equals("Saved future-world defaults"))
                .findFirst()
                .orElseThrow();
        check(
            "registered source caption fits its native control",
            client.font.width(selected.getMessage()) <= selected.getWidth() - 20);
        check(
            "registered source keeps full native narration",
            PlatformStructuredChecks.narration(selected).contains("Saved future-world defaults"));
        check(
            "source selection reads no new-world context or source write",
            creation.getUiState().getSettings() == original
                && Arrays.equals(saved, Files.readAllBytes(source)));
        press(importing, "Preview import");
        next("explicit saved-source preview begins");
      }
      case 6 -> {
        if (importing.reviewedInput().isEmpty()) return false;
        var review = importing.reviewedInput().orElseThrow();
        check(
            "saved defaults produce typed comparison before staging",
            review.stageable() && review.comparisonAvailable() && !review.changes().isEmpty());
        check(
            "preview leaves original recipe and defaults file unchanged",
            depth(original) == 2
                && creation.getUiState().getSettings() == original
                && Arrays.equals(saved, Files.readAllBytes(source)));
        geometry(importing);
        capture.accept("form-defaults-import.png", () -> press(importing, "Stage draft"));
        next("reviewed defaults stage as one explicit draft edit");
      }
      case 7 -> {
        if (!showSettings() || !depthText().equals("11")) return false;
        check(
            "staging changes only the selected draft",
            form.editorView().dirty()
                && creation.getUiState().getSettings() == original
                && depth(original) == 2);
        press(form, "Undo");
        next("default staging is undoable without native admission");
      }
      case 8 -> {
        if (!showSettings() || !depthText().equals("2")) return false;
        check("one Undo restores the earlier recipe", !form.editorView().dirty());
        press(form, "Redo");
        next("redo restores captured saved defaults");
      }
      case 9 -> {
        if (!showSettings() || !depthText().equals("11")) return false;
        check(
            "redo still has no native dimension effect",
            creation.getUiState().getSettings() == original);
        press(form, "Apply recipe");
        next("separate native creation admission requested");
      }
      case 10 -> {
        if (!ready() || form.editorView().lastReceipt().isEmpty()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        var admitted = creation.getUiState().getSettings();
        check(
            "only explicit Apply changes native recipe",
            receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                && receipt.application() == CommitReceipt.Application.APPLIED
                && admitted != original
                && depth(admitted) == 11
                && depth(original) == 2);
        check(
            "native admission retains other dimensions",
            admitted.selectedDimensions().dimensions().get(LevelStem.NETHER)
                    == original.selectedDimensions().dimensions().get(LevelStem.NETHER)
                && admitted.selectedDimensions().dimensions().get(LevelStem.END)
                    == original.selectedDimensions().dimensions().get(LevelStem.END));
        check(
            "creation admission never rewrites saved defaults",
            Arrays.equals(saved, Files.readAllBytes(source)));
        form.onClose();
        check(
            "native recipe context closes after clean exit",
            form.editorView().availability() == EditorActor.Availability.CLOSED
                && NativeScreens.current(client) == creation);
        creation.onClose();
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown defaults stage " + stage);
    }
    return false;
  }
}
