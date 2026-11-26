package design.inherent.reform.minecraft.optional.jei;

import design.inherent.reform.minecraft.client.NativeScreens;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The public recipes surface predates JEI's split GUI runtime and remains bridged by newer JEI. */
final class NativeJeiGui {
  private NativeJeiGui() {}

  static IRecipesGui recipes(IJeiRuntime runtime) {
    return runtime.getRecipesGui();
  }

  static boolean isOpen(IJeiRuntime runtime) {
    // A remembered parent alone is insufficient: older JEI keeps it after the viewer closes.
    return recipes(runtime) instanceof Screen screen
        && NativeScreens.current(Minecraft.getInstance()) == screen;
  }
}
