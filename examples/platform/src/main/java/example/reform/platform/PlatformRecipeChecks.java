package example.reform.platform;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.minecraft.client.WorldRecipe;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueTypes;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;

/** Actual unsaved native creation, bounded editing and owner-retained complete inputs. */
final class PlatformRecipeChecks {
  record Input(int soilDepth) {}

  record Compiled(int soilDepth, String retained) {}

  private static final String RETAINED = "owner-original-input/".repeat(110000);
  private static final DocumentDefinition<Input> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_platform_example:recipe"),
          1,
          RecordTypes.record(MethodHandles.lookup(), Input.class)
              .field("soilDepth", FieldKey.of("soilDepth", ValueTypes.integer(1, 64)))
              .build(),
          () -> new Input(2));

  static CompletionStage<JsonObject> run(ClientRegistration registration, boolean reopen) {
    var check = new Check();
    check.subscription = registration.onTick(check::tick);
    NativeRecipeProbe.open();
    return check
        .result
        .thenCompose(
            basic ->
                PlatformAdvancedRecipeChecks.run(registration, reopen)
                    .thenApply(
                        advanced -> {
                          basic.add("advanced", advanced);
                          return basic;
                        }))
        .minimalCompletionStage();
  }

  static FlatLevelSource flat(WorldCreationContext source) {
    if (!(source.selectedDimensions().overworld() instanceof FlatLevelSource flat))
      throw new AssertionError("Fixture did not select the actual native flat preset");
    return flat;
  }

  private static int depth(WorldCreationContext source) {
    return flat(source).settings().getLayersInfo().get(1).getHeight();
  }

  static WorldDimensions dimensions(
      WorldCreationContext source,
      RegistryAccess.Frozen registries,
      WorldDimensions dimensions,
      int depth) {
    var settings = flat(source).settings();
    var layers = new ArrayList<>(settings.getLayersInfo());
    layers.set(1, new FlatLayerInfo(depth, Blocks.DIRT));
    var replacement =
        settings.withBiomeAndLayers(layers, settings.structureOverrides(), settings.getBiome());
    return dimensions.replaceOverworldGenerator(registries, new FlatLevelSource(replacement));
  }

  private static final class Check {
    private final Minecraft client = Minecraft.getInstance();
    private final Screen initial = NativeScreens.current(client);
    private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    private final List<String> checks = new ArrayList<>();
    private ClientRegistration.Subscription subscription;
    private CreateWorldScreen parent;
    private WorldCreationContext original, admitted, foreign;
    private WorldRecipe<Input, Compiled> recipe;
    private SettingsScreen<?> form;
    private CompletableFuture<?> pending;
    private int stage, ticks, admittedCalls;

    private void check(String label, boolean passed) {
      if (!passed) throw new AssertionError(label);
      checks.add(label);
    }

    private WorldRecipe<Input, Compiled> editor(WorldCreationContext basis) {
      return WorldRecipe.worldRecipe(
          parent,
          basis,
          DEFINITION,
          new WorldRecipe.Domain<Input, Compiled>() {
            @Override
            public Input read(WorldCreationContext source) {
              if (!client.isSameThread())
                throw new AssertionError("Recipe capture left native thread");
              return new Input(depth(source));
            }

            @Override
            public Compiled compile(WorldCreationContext source, Input input) {
              if (client.isSameThread())
                throw new AssertionError("Recipe compiled on native thread");
              return new Compiled(input.soilDepth(), RETAINED);
            }

            @Override
            public WorldDimensions dimensions(
                WorldCreationContext source,
                RegistryAccess.Frozen registries,
                WorldDimensions dimensions,
                Compiled compiled) {
              check("native dimension update uses client executor", client.isSameThread());
              check(
                  "native admission retains complete owner inputs",
                  compiled.retained() == RETAINED);
              return PlatformRecipeChecks.dimensions(
                  source, registries, dimensions, compiled.soilDepth());
            }

            @Override
            public void admitted(WorldCreationContext actual) {
              check("owner admission callback uses client executor", client.isSameThread());
              check(
                  "owner callback sees actual updated context",
                  parent.getUiState().getSettings() == actual);
              admittedCalls++;
            }
          },
          SnapshotPolicy.immutable());
    }

    private void open(WorldCreationContext basis) {
      recipe = editor(basis);
      form = (SettingsScreen<?>) recipe.screen(Component.literal("Unsaved recipe"));
      NativeScreens.open(client, form);
    }

    private boolean ready() {
      return form.editorView().availability() == EditorActor.Availability.READY
          && form.editorView().snapshot().isPresent()
          && form.fieldControl(FieldPath.ROOT.field("soilDepth")).isPresent();
    }

    private void tick() {
      try {
        if (Files.exists(Path.of("platform-cancel")))
          throw new AssertionError("Recipe fixture cancelled");
        if (++ticks > 1800) throw new AssertionError("Native recipe timed out at stage " + stage);
        switch (stage) {
          case 0 -> {
            if (!(NativeScreens.current(client) instanceof CreateWorldScreen created)) return;
            parent = created;
            original = NativeRecipeProbe.flat(parent);
            check("actual unsaved creation starts with native flat layers", depth(original) == 2);
            check(
                "complete owner input exceeds bounded editable JSON",
                RETAINED.length() > 2 * 1024 * 1024);
            open(original);
            stage++;
          }
          case 1 -> {
            if (!ready()) return;
            check(
                "bounded selected view opens a real native control",
                form.width > 0 && form.height > 0);
            pending =
                DocumentActions.result(recipe.actions().edit(EntryPoint.GUI, "soilDepth", "5"))
                    .toCompletableFuture();
            stage++;
          }
          case 2 -> {
            if (!pending.isDone()) return;
            pending.join();
            check(
                "editing leaves native recipe and callback unchanged",
                parent.getUiState().getSettings() == original && admittedCalls == 0);
            pending =
                DocumentActions.result(recipe.actions().commit(EntryPoint.GUI))
                    .toCompletableFuture();
            stage++;
          }
          case 3 -> {
            if (!pending.isDone()) return;
            var receipt = ((EditorActor.Commit<?>) pending.join()).receipt().orElseThrow();
            admitted = parent.getUiState().getSettings();
            check(
                "native admission has an applied non-file receipt",
                receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                    && receipt.application() == CommitReceipt.Application.APPLIED);
            check(
                "native menu changes exactly once and retains original basis",
                admitted != original
                    && depth(admitted) == 5
                    && depth(original) == 2
                    && admittedCalls == 1);
            check(
                "native admission preserves unedited dimensions",
                admitted.selectedDimensions().dimensions().get(LevelStem.NETHER)
                        == original.selectedDimensions().dimensions().get(LevelStem.NETHER)
                    && admitted.selectedDimensions().dimensions().get(LevelStem.END)
                        == original.selectedDimensions().dimensions().get(LevelStem.END));
            var ops = RegistryOps.create(JsonOps.INSTANCE, admitted.worldgenLoadContext());
            var json =
                ChunkGenerator.CODEC
                    .encodeStart(ops, admitted.selectedDimensions().overworld())
                    .getOrThrow();
            var decoded = ChunkGenerator.CODEC.parse(ops, json).getOrThrow();
            check(
                "actual native RegistryOps round trip retains selected generator",
                decoded instanceof FlatLevelSource restored
                    && restored.settings().getLayersInfo().get(1).getHeight() == 5);
            try {
              editor(original);
              throw new AssertionError("Stale native context was admitted at construction");
            } catch (PolicyRefusal refused) {
              check(
                  "stale creation basis retains exact structured refusal",
                  refused.report().issues().stream()
                      .anyMatch(issue -> issue.code().equals("owner.revision")));
            }
            recipe.close();
            open(admitted);
            stage++;
          }
          case 4 -> {
            if (!ready()) return;
            pending =
                DocumentActions.result(recipe.actions().edit(EntryPoint.GUI, "soilDepth", "6"))
                    .toCompletableFuture();
            stage++;
          }
          case 5 -> {
            if (!pending.isDone()) return;
            pending.join();
            parent
                .getUiState()
                .updateDimensions(
                    (registries, current) -> dimensions(admitted, registries, current, 7));
            foreign = parent.getUiState().getSettings();
            pending = recipe.actions().commit(EntryPoint.GUI).completion().toCompletableFuture();
            stage++;
          }
          case 6 -> {
            if (!pending.isDone()) return;
            var outcome = (OperationSnapshot<?>) pending.join();
            boolean revision =
                outcome.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("owner.revision"));
            if (outcome.result().orElse(null) instanceof EditorActor.Commit<?> commit) {
              revision |=
                  commit.editorReport().issues().stream()
                      .anyMatch(issue -> issue.code().equals("owner.revision"));
              if (commit.receipt().isPresent())
                revision |=
                    commit.receipt().orElseThrow().applicationReport().issues().stream()
                        .anyMatch(issue -> issue.code().equals("owner.revision"));
            }
            check("changed native basis has a traceable refusal", revision);
            check(
                "refused admission preserves foreign context and draft",
                parent.getUiState().getSettings() == foreign
                    && depth(foreign) == 7
                    && admittedCalls == 1
                    && recipe.actions().view().dirty());
            recipe.close();
            parent.onClose();
            NativeScreens.open(client, initial);
            check(
                "unsaved recipe never starts a server",
                client.getSingleplayerServer() == null && client.level == null);
            var report = new JsonObject();
            report.addProperty("status", "PASS");
            report.addProperty("retainedInputCharacters", RETAINED.length());
            report.addProperty("ticks", ticks);
            var observations = new com.google.gson.JsonArray();
            checks.forEach(observations::add);
            report.add("checks", observations);
            subscription.close();
            result.complete(report);
          }
          default -> throw new AssertionError("Unknown native recipe stage");
        }
      } catch (Exception | AssertionError failed) {
        if (recipe != null) recipe.close();
        subscription.close();
        result.completeExceptionally(failed);
      }
    }
  }

  private PlatformRecipeChecks() {}
}
