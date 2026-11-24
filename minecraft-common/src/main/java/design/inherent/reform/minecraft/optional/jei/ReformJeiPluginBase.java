package design.inherent.reform.minecraft.optional.jei;

import design.inherent.reform.minecraft.client.ClientRecipeLinks;
import design.inherent.reform.minecraft.client.NativeRecipeItems;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.RecipeLinkPolicy;
import design.inherent.reform.presentation.RecipeLinkRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Loaded by JEI's own optional client plugin admission, never by shared or server entry points. */
public abstract class ReformJeiPluginBase implements IModPlugin {
  private volatile IJeiRuntime runtime;
  private volatile Object generation = new Object();
  private AutoCloseable registration;

  @Override
  public void onRuntimeAvailable(IJeiRuntime runtime) {
    this.runtime = runtime;
    refresh();
  }

  @Override
  public void onRuntimeRefreshed() {
    refresh();
  }

  @Override
  public void onRuntimeUnavailable() {
    runtime = null;
    refresh();
  }

  private void refresh() {
    var token = new Object();
    generation = token;
    Minecraft.getInstance()
        .execute(
            () -> {
              if (generation != token) return;
              if (registration != null) {
                try {
                  registration.close();
                } catch (Exception failed) {
                  throw new IllegalStateException(
                      "Recipe viewer registration could not retire", failed);
                }
                registration = null;
              }
              var current = runtime;
              if (current != null)
                registration =
                    ClientRecipeLinks.register(
                        RecipeLinkPolicy.Provider.JEI, new Adapter(current, token));
            });
  }

  private final class Adapter implements ClientRecipeLinks.Adapter {
    private static final int MAXIMUM_CATEGORIES = 1024;
    private static final int MAXIMUM_RECIPES = 65536;
    private final IJeiRuntime expected;
    private final Object token;

    Adapter(IJeiRuntime expected, Object token) {
      this.expected = expected;
      this.token = token;
    }

    @Override
    public Set<RecipeLinkRequest.Kind> kinds() {
      return Set.of(
          RecipeLinkRequest.Kind.PRODUCING,
          RecipeLinkRequest.Kind.USES,
          RecipeLinkRequest.Kind.RECIPE,
          RecipeLinkRequest.Kind.CATEGORY,
          RecipeLinkRequest.Kind.WORKSTATION);
    }

    @Override
    public Optional<PolicyRefusal> unavailable(Screen origin) {
      var client = Minecraft.getInstance();
      return runtime != expected
              || generation != token
              || client.level == null
              || client.player == null
              || client.getConnection() == null
          ? Optional.of(
              refusal(
                  "recipe.viewer_not_ready", "Join a world and wait for JEI to finish loading."))
          : origin == null || NativeScreens.current(client) != origin
              ? Optional.of(
                  refusal(
                      "recipe.origin_changed",
                      "Return to the settings page before opening recipes."))
              : Optional.empty();
    }

    @Override
    public ClientRecipeLinks.Prepared prepare(RecipeLinkRequest request, Screen origin) {
      unavailable(origin)
          .ifPresent(
              reason -> {
                throw reason;
              });
      var manager = expected.getRecipeManager();
      var categories =
          manager.createRecipeCategoryLookup().get().limit(MAXIMUM_CATEGORIES + 1L).toList();
      if (categories.size() > MAXIMUM_CATEGORIES) throw budget();
      Runnable show;
      if (request.kind() == RecipeLinkRequest.Kind.PRODUCING
          || request.kind() == RecipeLinkRequest.Kind.USES) {
        var stack = NativeRecipeItems.item(request.identity());
        var focus =
            expected
                .getJeiHelpers()
                .getFocusFactory()
                .createFocus(
                    request.kind() == RecipeLinkRequest.Kind.PRODUCING
                        ? RecipeIngredientRole.OUTPUT
                        : RecipeIngredientRole.INPUT,
                    VanillaTypes.ITEM_STACK,
                    stack);
        if (manager
            .createRecipeCategoryLookup()
            .limitFocus(List.of(focus))
            .get()
            .findAny()
            .isEmpty()) throw missing();
        show = () -> expected.getGuiRuntime().getRecipesGui().show(focus);
      } else if (request.kind() == RecipeLinkRequest.Kind.CATEGORY) {
        var category =
            categories.stream()
                .filter(
                    value ->
                        value
                            .getRecipeType()
                            .getUid()
                            .toString()
                            .equals(request.identity().toString()))
                .findFirst()
                .orElseThrow(ReformJeiPluginBase::missing);
        show =
            () ->
                expected
                    .getGuiRuntime()
                    .getRecipesGui()
                    .showTypes(List.of(category.getRecipeType()));
      } else if (request.kind() == RecipeLinkRequest.Kind.WORKSTATION) {
        var item = NativeRecipeItems.item(request.identity()).getItem();
        int[] inspected = {0};
        var types =
            categories.stream()
                .filter(
                    category -> NativeJeiWorkstations.matches(manager, category, item, inspected))
                .map(IRecipeCategory::getRecipeType)
                .toList();
        if (types.isEmpty()) throw missing();
        show = () -> expected.getGuiRuntime().getRecipesGui().showTypes(List.copyOf(types));
      } else {
        int[] inspected = {0};
        show = null;
        for (var category : categories) {
          show = exact(category, request, inspected);
          if (show != null) break;
        }
        if (show == null) throw missing();
      }
      var action = show;
      var client = Minecraft.getInstance();
      var level = client.level;
      var connection = client.getConnection();
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
          if (client.level != level || client.getConnection() != connection)
            throw refusal(
                "recipe.context_changed", "The world changed. Open the recipe link again.");
          action.run();
          if (!expected.getGuiRuntime().getRecipesGui().isOpen()) throw missing();
        }

        public boolean hasParent(Screen parent) {
          return expected.getGuiRuntime().getRecipesGui().isOpen()
              && expected
                  .getGuiRuntime()
                  .getRecipesGui()
                  .getParentScreen()
                  .filter(screen -> screen == parent)
                  .isPresent();
        }
      };
    }

    private <R> Runnable exact(
        IRecipeCategory<R> category, RecipeLinkRequest request, int[] inspected) {
      try (var stream =
          expected.getRecipeManager().createRecipeLookup(category.getRecipeType()).get()) {
        var iterator = stream.iterator();
        while (iterator.hasNext()) {
          if (++inspected[0] > MAXIMUM_RECIPES) throw budget();
          var recipe = iterator.next();
          if (category.getSourceRecipeIds(recipe).stream()
              .anyMatch(id -> id.toString().equals(request.identity().toString())))
            return () ->
                expected
                    .getGuiRuntime()
                    .getRecipesGui()
                    .showRecipes(category, List.of(recipe), List.of());
        }
      }
      return null;
    }
  }

  private static PolicyRefusal refusal(String code, String text) {
    return new PolicyRefusal(code, text);
  }

  private static PolicyRefusal missing() {
    return refusal(
        "recipe.no_match",
        "No visible recipe matches this link. Return to editing or choose another viewer explicitly.");
  }

  private static PolicyRefusal budget() {
    return refusal(
        "recipe.lookup_budget", "This recipe lookup exceeds its bounded search allowance.");
  }
}
