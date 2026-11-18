package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.ContextRecipeOwner;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotPolicy;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Ordinary unsaved native world-recipe construction; no file or existing world is initialized. */
@OnlyIn(Dist.CLIENT)
public final class WorldRecipe<T, P> implements AutoCloseable {
  public interface Domain<T, P> {
    /** Native immutable recipe capture; do not substitute a default for the current generator. */
    T read(WorldCreationContext context);

    /**
     * Worker-only bounded pure compilation. Do not read widgets, live worlds or rendering state.
     */
    P compile(WorldCreationContext original, T recipe);

    /** Native dimension update. Preserve unedited dimensions and the owner's generation guards. */
    WorldDimensions dimensions(
        WorldCreationContext original,
        RegistryAccess.Frozen registries,
        WorldDimensions dimensions,
        P compiled);

    /**
     * Native notification after the actual update, for owner admission flags such as recipe
     * acceptance.
     */
    default void admitted(WorldCreationContext actual) {}
  }

  private final OwnerContext context;
  private final DocumentActions<T> actions;
  private final DocumentDefinition<T> definition;
  private CreateWorldScreen parent;
  private boolean screenCreated;

  private WorldRecipe(
      CreateWorldScreen parent,
      WorldCreationContext original,
      DocumentDefinition<T> definition,
      Domain<T, P> domain,
      SnapshotPolicy<P> snapshots) {
    requireClient();
    this.parent = Objects.requireNonNull(parent);
    this.definition = Objects.requireNonNull(definition);
    Objects.requireNonNull(original);
    Objects.requireNonNull(domain);
    Objects.requireNonNull(snapshots);
    if (parent.getUiState().getSettings() != original)
      throw new PolicyRefusal("owner.revision", "Use the original native world-creation context");
    var client = Minecraft.getInstance();
    context =
        new OwnerContext(
            client::execute,
            ClientJsonSettings.workers(),
            WorldRecipe::requireClient,
            OwnerContext.Limits.STANDARD);
    try {
      ClientJsonSettings.manageContext(context);
      var limits = new JsonLimits(262144, 64, 16384, 32768, 128, 4096);
      var owner =
          new ContextRecipeOwner<>(
              context,
              definition,
              original,
              new ContextRecipeOwner.Domain<T, WorldCreationContext, P>() {
                @Override
                public WorldCreationContext current() {
                  return parent.getUiState().getSettings();
                }

                @Override
                public T read(WorldCreationContext source) {
                  return domain.read(source);
                }

                @Override
                public P compile(WorldCreationContext source, T recipe) {
                  return domain.compile(source, recipe);
                }

                @Override
                public WorldCreationContext admit(WorldCreationContext expected, P compiled) {
                  if (parent.getUiState().getSettings() != expected)
                    throw new PolicyRefusal(
                        "owner.revision", "The original creation context changed");
                  parent
                      .getUiState()
                      .updateDimensions(
                          (registries, dimensions) ->
                              Objects.requireNonNull(
                                  domain.dimensions(expected, registries, dimensions, compiled)));
                  var admitted = parent.getUiState().getSettings();
                  domain.admitted(admitted);
                  return admitted;
                }
              },
              snapshots,
              limits,
              DraftLimits.CONFIGURATION,
              16);
      var access = context.access(() -> true, () -> true);
      var actor =
          new EditorActor<>(
              context, owner.open(access), definition, limits, DraftLimits.CONFIGURATION, 64);
      actions =
          DocumentActions.owningContext(
              context,
              access,
              actor,
              new OperationScope.Limits(32, 64, 128, OperationScope.Limits.STANDARD.input()),
              limits.maximumBytes());
      context.onClose(() -> this.parent = null);
    } catch (RuntimeException failed) {
      context.close();
      throw failed;
    }
  }

  public static <T, P> WorldRecipe<T, P> worldRecipe(
      CreateWorldScreen parent,
      WorldCreationContext original,
      DocumentDefinition<T> definition,
      Domain<T, P> domain,
      SnapshotPolicy<P> compiledSnapshots) {
    return new WorldRecipe<>(parent, original, definition, domain, compiledSnapshots);
  }

  public DocumentActions<T> actions() {
    context.requireOwner();
    return actions;
  }

  public Screen screen(Component title) {
    context.requireOwner();
    Objects.requireNonNull(title);
    if (screenCreated)
      throw new IllegalStateException("This recipe editor already has a native screen");
    screenCreated = true;
    try {
      return new SettingsScreen<>(
          parent,
          title,
          Component.translatableWithFallback(
              "reform.settings.creation_context",
              "New world recipe · affects only this unsaved creation context"),
          definition,
          actions);
    } catch (RuntimeException failed) {
      close();
      throw failed;
    }
  }

  private static void requireClient() {
    if (!Minecraft.getInstance().isSameThread())
      throw new IllegalStateException("Use the native client executor");
  }

  @Override
  public void close() {
    actions.close();
  }
}
