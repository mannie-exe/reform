package design.inherent.reform.minecraft.optional.emi;

import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.RecipeLinkPolicy;
import design.inherent.reform.presentation.RecipeLinkRequest;
import dev.emi.emi.api.*;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiStack;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;

/** Admitted only by EMI's own client plugin loader on the declared 1.21.1 targets. */
@EmiEntrypoint
public final class ReformEmiPlugin implements EmiPlugin {
  private volatile Object generation = new Object();
  private AutoCloseable registration;

  @Override
  public void register(EmiRegistry registry) {
    // EMI clears its public manager before plugin registration and publishes a new manager at bake.
    // Compare the public identity; do not call private reload/status APIs or infer empty ==
    // unavailable.
    var unpublished = EmiApi.getRecipeManager();
    var token = new Object();
    generation = token;
    var client = Minecraft.getInstance();
    client.execute(
        () -> {
          if (generation != token) return;
          if (registration != null) {
            try {
              registration.close();
            } catch (Exception failed) {
              throw new IllegalStateException(
                  "Recipe viewer registration could not retire", failed);
            }
          }
          registration =
              ClientRecipeLinks.register(
                  RecipeLinkPolicy.Provider.EMI,
                  new Adapter(token, unpublished, ClientRecipeLinks.context().registry()));
        });
  }

  private final class Adapter implements ClientRecipeLinks.Adapter {
    private final Object token;
    private final EmiRecipeManager unpublished;
    private final UUID world;

    Adapter(Object token, EmiRecipeManager unpublished, UUID world) {
      this.token = token;
      this.unpublished = unpublished;
      this.world = world;
    }

    @Override
    public Set<RecipeLinkRequest.Kind> kinds() {
      // The public uses screen combines ingredient and workstation relations; it cannot expose
      // a workstation-only request without private filtering or silently showing another scope.
      return Set.of(
          RecipeLinkRequest.Kind.PRODUCING,
          RecipeLinkRequest.Kind.USES,
          RecipeLinkRequest.Kind.RECIPE,
          RecipeLinkRequest.Kind.CATEGORY);
    }

    @Override
    public Optional<PolicyRefusal> unavailable(Screen origin) {
      var client = Minecraft.getInstance();
      if (!(origin instanceof AbstractContainerScreen<?> container))
        return Optional.of(
            refusal(
                "recipe.return_unavailable",
                "EMI needs an open native inventory or crafting screen to preserve the return path. Choose another viewer explicitly."));
      if (NativeScreens.current(client) != origin
          || client.player == null
          || client.level == null
          || client.getConnection() == null
          || client.player.containerMenu != container.getMenu())
        return Optional.of(
            refusal(
                "recipe.container_context",
                "Open this recipe link from its current native inventory or crafting screen."));
      var context = ClientRecipeLinks.context();
      if (generation != token
          || !context.registry().equals(world)
          || !context.resourcesReady()
          || EmiApi.getRecipeManager() == unpublished)
        return Optional.of(
            refusal(
                "recipe.viewer_not_ready",
                "Wait for EMI and the current resources to finish loading."));
      return Optional.empty();
    }

    @Override
    public ClientRecipeLinks.Prepared prepare(RecipeLinkRequest request, Screen origin) {
      unavailable(origin)
          .ifPresent(
              reason -> {
                throw reason;
              });
      var manager = EmiApi.getRecipeManager();
      var context = ClientRecipeLinks.context();
      Runnable show;
      switch (request.kind()) {
        case PRODUCING -> {
          var stack = EmiStack.of(NativeRecipeItems.item(request.identity()));
          show = () -> EmiApi.displayRecipes(stack);
        }
        case USES -> {
          var stack = EmiStack.of(NativeRecipeItems.item(request.identity()));
          show = () -> EmiApi.displayUses(stack);
        }
        case RECIPE -> {
          var recipe = manager.getRecipe(ResourceLocation.parse(request.identity().toString()));
          if (recipe == null) throw missing();
          show = () -> EmiApi.displayRecipe(recipe);
        }
        case CATEGORY -> {
          var category =
              manager.getCategories().stream()
                  .filter(value -> value.getId().toString().equals(request.identity().toString()))
                  .findFirst()
                  .orElseThrow(ReformEmiPlugin::missing);
          if (manager.getRecipes(category).isEmpty()) throw missing();
          show = () -> EmiApi.displayRecipeCategory(category);
        }
        default ->
            throw refusal(
                "recipe.request_unsupported",
                "EMI does not expose this recipe link through its public API.");
      }
      return new ClientRecipeLinks.Prepared() {
        private boolean opened;

        public void open() {
          if (opened)
            throw refusal("recipe.already_opened", "Open this recipe link again explicitly.");
          opened = true;
          unavailable(origin)
              .ifPresent(
                  reason -> {
                    throw reason;
                  });
          if (EmiApi.getRecipeManager() != manager || !ClientRecipeLinks.context().equals(context))
            throw refusal(
                "recipe.context_changed",
                "The recipe or resource generation changed. Open the link again.");
          show.run();
          if (NativeScreens.current(Minecraft.getInstance()) == origin) throw missing();
        }

        public boolean hasParent(Screen parent) {
          return EmiApi.getHandledScreen() == parent;
        }
      };
    }
  }

  private static PolicyRefusal refusal(String code, String message) {
    return new PolicyRefusal(code, message);
  }

  private static PolicyRefusal missing() {
    return refusal(
        "recipe.no_match",
        "No recipe matches this link. Return to the native screen or choose another viewer explicitly.");
  }
}
