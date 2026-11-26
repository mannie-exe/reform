package design.inherent.reform.minecraft.optional.jei;

import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;

/** The 26.3 API exposes its recipes screen through JEI's GUI runtime. */
final class NativeJeiGui {
  private NativeJeiGui() {}

  static IRecipesGui recipes(IJeiRuntime runtime) {
    return runtime.getGuiRuntime().getRecipesGui();
  }

  static boolean isOpen(IJeiRuntime runtime) {
    return recipes(runtime).isOpen();
  }
}
