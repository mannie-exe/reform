package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.neoforge.client.WorldRecipe;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import example.reform.CreationExample;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Actual native creation UI; no playable world or personal instance is opened. */
final class NativeWorldRecipeControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final JsonObject report = new JsonObject();
  private int stage;
  private CreateWorldScreen parent;
  private WorldCreationContext original, foreign, accepted;
  private WorldRecipe<CreationExample.Recipe, Integer> recipe;
  private DocumentActions<CreationExample.Recipe> actions, retained;
  private Screen form;
  private EditBox soil;
  private CompletableFuture<?> pending;
  private int callbackCalls;

  NativeWorldRecipeControls(Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.progress = progress;
    this.capture = capture;
    report.add("checks", checks);
    CreateWorldScreen.openFresh(client, client.screen);
    progress.accept("native world-data loading requested; no world created");
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private void step(String label) {
    stage++;
    progress.accept(label);
  }

  private int depth(WorldCreationContext context) {
    return ((FlatLevelSource) context.selectedDimensions().overworld())
        .settings()
        .getLayersInfo()
        .get(1)
        .getHeight();
  }

  private boolean ready() {
    return actions.view().availability() == EditorActor.Availability.READY
        && client.screen == form
        && button(form, "Apply recipe").active;
  }

  private void open(WorldRecipe<CreationExample.Recipe, Integer> next) {
    recipe = next;
    actions = next.actions();
    form = next.screen(Component.literal("Native recipe settings"));
    client.setScreen(form);
  }

  private void discard() {
    form.onClose();
    check("dirty recipe close requests explicit review", client.screen instanceof ConfirmScreen);
    click(client.screen, button(client.screen, "Discard draft"));
    check("discard returns actual creation parent", client.screen == parent);
    check(
        "discard closes its owned context",
        actions.view().availability() == EditorActor.Availability.CLOSED);
  }

  boolean tick() {
    switch (stage) {
      case 0 -> {
        if (!(client.screen instanceof CreateWorldScreen created)) return false;
        parent = created;
        var flat =
            parent
                .getUiState()
                .getSettings()
                .worldgenLoadContext()
                .registryOrThrow(Registries.WORLD_PRESET)
                .getHolderOrThrow(WorldPresets.FLAT);
        parent
            .getUiState()
            .updateDimensions((registries, dimensions) -> flat.value().createWorldDimensions());
        original = parent.getUiState().getSettings();
        check("native flat preset baseline", depth(original) == 2);
        open(CreationExample.editor(parent, original));
        step("ordinary public factory opens native recipe editor");
      }
      case 1 -> {
        if (!ready()) return false;
        soil = input(form, "Soil depth");
        check(
            "native original values, not invented defaults",
            soil.getValue().equals("2") && !actions.view().dirty());
        check("native apply is not labeled file save", button(form, "Apply recipe").active);
        check("native context reload is explicit", button(form, "Reload context").active);
        geometry(form);
        capture.accept("form-recipe.png", () -> replace(form, soil, "-"));
        step("actual widgets and creation-only action labels");
      }
      case 2 -> {
        if (actions.view().validation().valid()) return false;
        check(
            "invalid recipe never reaches creation context",
            parent.getUiState().getSettings() == original && depth(original) == 2);
        check("invalid recipe apply disabled", !button(form, "Apply recipe").active);
        replace(form, soil, "8");
        step("invalid draft preserved without admission");
      }
      case 3 -> {
        if (!actions.view().validation().valid()
            || !actions.view().dirty()
            || !button(form, "Apply recipe").active) return false;
        click(form, button(form, "Apply recipe"));
        step("native recipe admission requested");
      }
      case 4 -> {
        if (actions.view().lastReceipt().isEmpty()) return false;
        var receipt = actions.view().lastReceipt().orElseThrow();
        check(
            "native admission reports no persistence",
            receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                && receipt.savedRevision().isEmpty());
        check(
            "actual creation admission confirmed",
            receipt.application() == CommitReceipt.Application.APPLIED
                && receipt.acknowledgesBaseline());
        check(
            "admission acknowledges clean recipe",
            !actions.view().dirty() && actions.view().pending().isEmpty());
        accepted = parent.getUiState().getSettings();
        check(
            "actual context and generator replaced", accepted != original && depth(accepted) == 8);
        check("original generator and layer list unchanged", depth(original) == 2);
        check(
            "other dimensions retained",
            accepted.selectedDimensions().dimensions().get(LevelStem.NETHER)
                    == original.selectedDimensions().dimensions().get(LevelStem.NETHER)
                && accepted.selectedDimensions().dimensions().get(LevelStem.END)
                    == original.selectedDimensions().dimensions().get(LevelStem.END));
        check(
            "non-dimension creation inputs retained",
            accepted.options() == original.options()
                && accepted.datapackDimensions() == original.datapackDimensions()
                && accepted.worldgenRegistries() == original.worldgenRegistries()
                && accepted.dataPackResources() == original.dataPackResources()
                && accepted.dataConfiguration() == original.dataConfiguration());
        check(
            "recipe factory created no JSON source",
            !Files.exists(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_native_example/creation.json")));
        form.onClose();
        check("clean close returns original creation parent", client.screen == parent);
        check(
            "clean recipe closes owned actor",
            actions.view().availability() == EditorActor.Availability.CLOSED);
        open(CreationExample.editor(parent, accepted));
        step("native soil depth2 to8 applied with original and other dimensions preserved");
      }
      case 5 -> {
        if (!ready()) return false;
        soil = input(form, "Soil depth");
        check(
            "reopened recipe reads admitted8 rather than factory default2",
            soil.getValue().equals("8") && !actions.view().dirty());
        replace(form, soil, "9");
        step("second editor retains actual admitted recipe");
      }
      case 6 -> {
        if (!actions.view().dirty()
            || !actions.view().validation().valid()
            || !button(form, "Apply recipe").active) return false;
        parent.getUiState().updateDimensions((registries, dimensions) -> dimensions);
        foreign = parent.getUiState().getSettings();
        check(
            "same-value native replacement differs by identity",
            foreign != accepted && foreign.equals(accepted));
        click(form, button(form, "Apply recipe"));
        step("same-value foreign native context refusal requested");
      }
      case 7 -> {
        if (actions.view().operationReport().valid()) return false;
        check(
            "native stale identity refusal is traced",
            actions.view().operationReport().issues().stream()
                .anyMatch(issue -> issue.code().equals("owner.revision")));
        check(
            "stale native context untouched",
            parent.getUiState().getSettings() == foreign && depth(foreign) == 8);
        check(
            "stale recipe keeps raw draft without pending effect",
            soil.getValue().equals("9")
                && actions.view().dirty()
                && actions.view().pending().isEmpty());
        discard();
        var base = CreationExample.domain();
        var faulty =
            new WorldRecipe.Domain<CreationExample.Recipe, Integer>() {
              @Override
              public CreationExample.Recipe read(WorldCreationContext context) {
                return base.read(context);
              }

              @Override
              public Integer compile(WorldCreationContext context, CreationExample.Recipe value) {
                return base.compile(context, value);
              }

              @Override
              public WorldDimensions dimensions(
                  WorldCreationContext context,
                  RegistryAccess.Frozen registries,
                  WorldDimensions dimensions,
                  Integer value) {
                return base.dimensions(context, registries, dimensions, value);
              }

              @Override
              public void admitted(WorldCreationContext actual) {
                callbackCalls++;
                throw new IllegalStateException("deliberate native post-update callback failure");
              }
            };
        open(
            WorldRecipe.worldRecipe(
                parent,
                foreign,
                CreationExample.DEFINITION,
                faulty,
                design.inherent.reform.value.SnapshotPolicy.immutable()));
        step("native stale refusal preserved; real after-update callback failure armed");
      }
      case 8 -> {
        if (!ready()) return false;
        soil = input(form, "Soil depth");
        replace(form, soil, "12");
        step("native post-update failure draft staged");
      }
      case 9 -> {
        if (!actions.view().dirty()
            || !actions.view().validation().valid()
            || !button(form, "Apply recipe").active) return false;
        click(form, button(form, "Apply recipe"));
        step("native post-update failure requested");
      }
      case 10 -> {
        if (actions.view().lastReceipt().isEmpty()) return false;
        var receipt = actions.view().lastReceipt().orElseThrow();
        check(
            "native callback actually ran after mutation",
            callbackCalls == 1 && depth(parent.getUiState().getSettings()) == 12);
        check(
            "native entered failure is unknown, not rolled back",
            receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                && receipt.application() == CommitReceipt.Application.UNAVAILABLE
                && receipt.baselineOutcomeUnknown());
        check(
            "native unknown keeps blocked dirty draft",
            actions.view().pending().orElseThrow() == EditSession.PendingState.UNKNOWN
                && actions.view().dirty()
                && !button(form, "Apply recipe").active);
        discard();
        check(
            "discard did not undo native update",
            depth(parent.getUiState().getSettings()) == 12 && callbackCalls == 1);
        recipe = CreationExample.editor(parent, parent.getUiState().getSettings());
        retained = recipe.actions();
        pending =
            DocumentActions.result(retained.edit(EntryPoint.INTERNAL, "soil_depth", "14"))
                .toCompletableFuture();
        step("actual post-update uncertainty retained; managed shutdown control opened");
      }
      case 11 -> {
        if (!pending.isDone()) return false;
        pending.join();
        check(
            "managed recipe is live before shutdown",
            retained.view().availability() == EditorActor.Availability.READY
                && retained.view().dirty());
        check(
            "unapplied shutdown draft did not alter generator",
            depth(parent.getUiState().getSettings()) == 12);
        check(
            "no playable world or server was created",
            client.level == null
                && client.getSingleplayerServer() == null
                && client.getConnection() == null);
        parent.onClose();
        report.addProperty("status", "PASS");
        report.addProperty(
            "scope",
            "actual native creation screen, flat generator, widgets and context admission; no playable world created");
        step(
            "native creation controls complete; one explicit live managed context remains for game shutdown");
        return true;
      }
      default -> throw new AssertionError("Unexpected native recipe stage " + stage);
    }
    return false;
  }

  JsonObject report() {
    return report;
  }

  void shutdown() {
    if (retained != null) {
      check(
          "native game shutdown closes managed recipe",
          retained.view().availability() == EditorActor.Availability.CLOSED);
      report.addProperty("managedShutdownClosed", true);
    }
  }
}
