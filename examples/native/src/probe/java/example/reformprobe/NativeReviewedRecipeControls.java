package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.OperationScreen;
import design.inherent.reform.minecraft.client.ReviewScreen;
import design.inherent.reform.minecraft.client.WorldRecipe;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.Effect;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.ContextRecipeOwner;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.value.SnapshotPolicy;
import example.reform.CreationExample;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** An ordinary addon receives and persists its complete immutable draft under its own authority. */
final class NativeReviewedRecipeControls {
  private static final String SAVE = "Save future defaults…", EXPORT = "Export complete recipe…";
  private static final String HIDDEN = "independent-ground-profile".repeat(90_000);

  private record Compiled(int depth, String hidden) {
    String encode() {
      var json = new JsonObject();
      json.addProperty("soil_depth", depth);
      json.addProperty("owner_profile", hidden);
      return json.toString();
    }
  }

  private record Receipt(String description, long bytes) {}

  private final Minecraft client = Minecraft.getInstance();
  private final CreateWorldScreen parent;
  private final WorldCreationContext original;
  private final Path output = client.gameDirectory.toPath().resolve("reviewed-future-recipe.json");
  private final Consumer<String> progress;
  private final JsonArray checks = new JsonArray();
  private final CompletableFuture<Receipt> held = new CompletableFuture<>();
  private final DocumentActions<CreationExample.Recipe> actions;
  private final Screen form;
  private boolean permit, defaultsMenuOpened;
  private int stage, saves, exports;
  private UUID saveId;
  private WorldRecipe.ReviewedInput<Compiled> first, saved;

  NativeReviewedRecipeControls(
      CreateWorldScreen parent, WorldCreationContext original, Consumer<String> progress) {
    this.parent = parent;
    this.original = original;
    this.progress = progress;
    var flat = CreationExample.domain();
    var domain =
        new WorldRecipe.Domain<CreationExample.Recipe, Compiled>() {
          @Override
          public CreationExample.Recipe read(WorldCreationContext source) {
            return flat.read(source);
          }

          @Override
          public Compiled compile(WorldCreationContext source, CreationExample.Recipe recipe) {
            return new Compiled(recipe.soilDepth(), "original context");
          }

          @Override
          public WorldDimensions dimensions(
              WorldCreationContext source,
              RegistryAccess.Frozen registries,
              WorldDimensions dimensions,
              Compiled compiled) {
            return flat.dimensions(source, registries, dimensions, compiled.depth());
          }
        };
    var recipe =
        WorldRecipe.worldRecipe(
            parent,
            original,
            CreationExample.DEFINITION,
            domain,
            SnapshotPolicy.immutable(),
            source ->
                new ContextRecipeOwner.Defaults<>(
                    "owned-full-defaults",
                    SourceInfo.UNKNOWN,
                    new CreationExample.Recipe(6),
                    (context, draft) -> new Compiled(draft.soilDepth(), HIDDEN)));
    recipe.withReviewedAction(
        new WorldRecipe.ReviewedAction<>(
            DocumentId.parse("reform_native_example:save_reviewed_recipe"),
            Component.literal(SAVE),
            Effect.PERSIST,
            () ->
                permit
                    ? Optional.empty()
                    : Optional.of(Component.literal("Owner temporarily refused saving")),
            (input, execution) -> {
              input.requireCurrent();
              if (++saves == 1) {
                first = input;
                return held;
              }
              saved = input;
              return CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      Files.writeString(
                          output, input.compiled().encode(), StandardOpenOption.CREATE_NEW);
                      return new Receipt("Verified future defaults", Files.size(output));
                    } catch (IOException failed) {
                      throw new UncheckedIOException(failed);
                    }
                  },
                  Util.ioPool());
            },
            SnapshotPolicy.immutable(),
            NativeReviewedRecipeControls::describe));
    recipe.withReviewedAction(
        new WorldRecipe.ReviewedAction<>(
            DocumentId.parse("reform_native_example:export_reviewed_recipe"),
            Component.literal(EXPORT),
            Effect.EXPORT,
            Optional::empty,
            (input, execution) -> {
              input.requireCurrent();
              exports++;
              return CompletableFuture.supplyAsync(
                  () ->
                      new Receipt("Complete in-memory export", input.compiled().encode().length()),
                  Util.ioPool());
            },
            SnapshotPolicy.immutable(),
            NativeReviewedRecipeControls::describe));
    actions = recipe.actions();
    form = recipe.screen(Component.literal("Reviewed recipe owner controls"));
    client.setScreen(form);
  }

  private static OperationScreen.Outcome describe(Receipt receipt) {
    return new OperationScreen.Outcome(
        Component.literal(receipt.description()),
        List.of(
            new OperationScreen.Detail(
                Component.literal("Owner bytes"),
                Component.literal(Long.toString(receipt.bytes())))));
  }

  private void recorded(String message, boolean fact) {
    if (!fact) throw new AssertionError(message);
    checks.add(message);
  }

  private void press(String label) {
    click(client.screen, ownerAction(client.screen, label));
  }

  private void next(String message) {
    stage++;
    progress.accept(message);
  }

  private OperationSnapshot<?> observation() {
    return ((OperationScreen<?>) client.screen).observedOperation().orElse(null);
  }

  boolean tick() throws IOException {
    switch (stage) {
      case 0 -> {
        if (actions.view().snapshot().isEmpty()
            || actions.hasActiveOperations()
            || !button(form, "Done").active) return false;
        if (!defaultsMenuOpened) {
          press("More…");
          defaultsMenuOpened =
              widgets(form).stream()
                  .anyMatch(widget -> widget.getMessage().getString().equals("Settings"));
          return false;
        }
        if (widgets(form).stream()
            .noneMatch(widget -> widget.getMessage().getString().equals("Settings")))
          throw new AssertionError(
              "The entered actions pane was dismissed: " + form.getNarrationMessage().getString());
        if (!button(form, "Load defaults").active) return false;
        press("Load defaults");
        next("staging full defaults for reviewed owner effects");
      }
      case 1 -> {
        if (!actions.view().dirty() || !button(form, "Review changes…").active) return false;
        recorded(
            "defaults stage leaves the native context intact and performs no writes",
            parent.getUiState().getSettings() == original && !Files.exists(output) && saves == 0);
        press("Settings");
        replace(form, input(form, "Soil depth"), "8");
        next("edited the visible projection");
      }
      case 2 -> {
        if (!button(form, "Review changes…").active) return false;
        press("Review changes…");
        next("opened the shared native recipe review");
      }
      case 3 -> {
        if (!(client.screen instanceof ReviewScreen<?>))
          throw new AssertionError("Opened something other than the shared review");
        if (!button(client.screen, EXPORT).active) return false;
        recorded(
            "declared persistence permission disables only its reviewed action",
            !button(client.screen, SAVE).active && button(client.screen, "Export value…").active);
        permit = true;
        next("owner permits the exact reviewed persistence operation");
      }
      case 4 -> {
        if (!button(client.screen, SAVE).active) return false;
        press(SAVE);
        next("first provider holds before writing");
      }
      case 5 -> {
        if (first == null) return false;
        recorded(
            "provider receives the complete selected basis and edited projection",
            first.compiled().depth() == 8
                && first.compiled().hidden() == HIDDEN
                && !Files.exists(output));
        press("Back");
        press("Back");
        replace(form, input(form, "Soil depth"), "9");
        next("later native typing invalidates the held reviewed persistence");
      }
      case 6 -> {
        if (actions.view().snapshot().filter(first.draft()::matches).isPresent()) return false;
        if (!button(form, "Review changes…").active) return false;
        try {
          first.requireCurrent();
          throw new AssertionError("Stale reviewed recipe authorized a later write");
        } catch (PolicyRefusal refused) {
          held.completeExceptionally(refused);
        }
        recorded("a later draft fails effect preflight before any write", !Files.exists(output));
        press("Review changes…");
        next("reviewed the new draft explicitly");
      }
      case 7 -> {
        if (!button(client.screen, SAVE).active) return false;
        press(SAVE);
        next("saving the complete compiled recipe in the owner’s file store");
      }
      case 8 -> {
        var observation = observation();
        if (observation == null || !observation.terminal()) return false;
        var receipt = (Receipt) observation.result().orElseThrow();
        saveId = observation.id();
        var disk = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        recorded(
            "owner saved more than two MiB with the untouched hidden field and edited value",
            receipt.bytes() == Files.size(output)
                && receipt.bytes() > 2 * 1024 * 1024L
                && disk.get("soil_depth").getAsInt() == 9
                && disk.get("owner_profile").getAsString().equals(HIDDEN));
        recorded(
            "saving a profile neither admits the recipe nor marks the context draft as committed",
            parent.getUiState().getSettings() == original
                && actions.view().dirty()
                && actions.view().lastReceipt().isEmpty());
        try {
          saved.requireCurrent();
          throw new AssertionError("Settled action retained authorization");
        } catch (PolicyRefusal refused) {
          recorded(
              "settled effects no longer authorize callbacks",
              refused.report().issues().getFirst().code().equals("recipe.action_finished"));
        }
        press("Back");
        press(SAVE);
        recorded(
            "reopening the same reviewed receipt does not repeat the write",
            observation().id().equals(saveId) && saves == 2);
        press("Back");
        press(EXPORT);
        next("explicit export uses a distinct reviewed effect");
      }
      case 9 -> {
        var observation = observation();
        if (observation == null || !observation.terminal()) return false;
        var receipt = (Receipt) observation.result().orElseThrow();
        recorded(
            "owner’s full export returns its independent receipt with no admission or extra save",
            !observation.id().equals(saveId)
                && receipt.bytes() == Files.size(output)
                && saves == 2
                && exports == 1
                && parent.getUiState().getSettings() == original);
        press("Back");
        press("Back");
        form.onClose();
        press("Discard draft");
        recorded(
            "discard closes original form after separate owner effects",
            client.screen == parent && !actions.isOpen());
        next("complete owner-effect controls passed");
        return true;
      }
      default -> throw new IllegalStateException("Reviewed recipe controls already finished");
    }
    return false;
  }

  JsonArray report() {
    return checks;
  }
}
