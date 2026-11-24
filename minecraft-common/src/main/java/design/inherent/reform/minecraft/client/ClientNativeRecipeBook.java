package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.minecraft.mixin.RecipeBookButtonSpritesAccessor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;

/** Explicit existing native book only: no new screen, player, menu, world, recipe, or unlock. */
public final class ClientNativeRecipeBook {
  private ClientNativeRecipeBook() {}

  public record Result(
      boolean visible, boolean changed, ValidationReport report, Optional<Throwable> cause) {
    public Result {
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
    }
  }

  private record Controls(BooleanSupplier visible, Button reveal) {}

  /** Cached current native context/widgets only; never opens a screen or enumerates recipes. */
  public static DestinationAvailability inspect(Screen owner) {
    ClientSettingsRuntime.requireClientThread();
    try {
      controls(owner);
      return new DestinationAvailability(
          DestinationAvailability.Kind.READY, ValidationReport.VALID, Set.of());
    } catch (PolicyRefusal refused) {
      return new DestinationAvailability(
          DestinationAvailability.Kind.UNAVAILABLE,
          refused.report(),
          Set.of(DestinationAvailability.Recovery.BACK, DestinationAvailability.Recovery.RETRY));
    }
  }

  /** Reveal the owner's real mounted book using its native button, preserving native layout. */
  public static Result open(Screen owner) {
    ClientSettingsRuntime.requireClientThread();
    Controls actual = null;
    boolean before = false;
    try {
      actual = controls(owner);
      before = actual.visible().getAsBoolean();
      if (!before) NativeRecipeBooks.reveal(actual.reveal());
      if (NativeScreens.current(Minecraft.getInstance()) != owner
          || !actual.visible().getAsBoolean())
        throw refusal(
            "recipe.book_changed",
            "The native recipe book could not open in this screen. Check the current screen before trying again.");
      return new Result(true, !before, ValidationReport.VALID, Optional.empty());
    } catch (RuntimeException failed) {
      boolean visible =
          actual != null
              && NativeScreens.current(Minecraft.getInstance()) == owner
              && actual.visible().getAsBoolean();
      var report =
          failed instanceof PolicyRefusal refused
              ? refused.report()
              : refusal(
                      "recipe.book_open_failed",
                      "The native recipe-book action failed. Check the current screen before trying again.")
                  .report();
      return new Result(visible, actual != null && before != visible, report, Optional.of(failed));
    }
  }

  private static Controls controls(Screen owner) {
    var client = Minecraft.getInstance();
    if (owner == null
        || NativeScreens.current(client) != owner
        || !(owner instanceof InventoryScreen || owner instanceof CraftingScreen)
        || !(owner instanceof AbstractContainerScreen<?> container)
        || client.level == null
        || client.player == null
        || client.player.isSpectator()
        || client.getConnection() == null
        || client.player.containerMenu != container.getMenu())
      throw refusal(
          "recipe.book_context",
          "Use an open native survival inventory or crafting screen to view its unlocked recipes.");
    var books =
        owner.children().stream()
            .filter(RecipeBookComponent.class::isInstance)
            .map(RecipeBookComponent.class::cast)
            .toList();
    if (books.size() != 1)
      throw refusal(
          "recipe.book_unavailable", "This screen does not expose one mounted native recipe book.");
    var book = books.getFirst();
    var buttons =
        owner.children().stream()
            .filter(ImageButton.class::isInstance)
            .map(ImageButton.class::cast)
            .filter(
                button ->
                    ((RecipeBookButtonSpritesAccessor) button)
                        .reform$recipeBookSprites()
                        .equals(RecipeBookComponent.RECIPE_BUTTON_SPRITES))
            .toList();
    if (buttons.size() != 1 || !buttons.getFirst().active || !buttons.getFirst().visible)
      throw refusal(
          "recipe.book_unavailable",
          "The native recipe-book action is unavailable or ambiguous in this screen.");
    return new Controls(book::isVisible, buttons.getFirst());
  }

  private static PolicyRefusal refusal(String code, String message) {
    return new PolicyRefusal(code, message);
  }
}
