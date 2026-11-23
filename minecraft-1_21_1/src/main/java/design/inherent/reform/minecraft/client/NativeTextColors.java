package design.inherent.reform.minecraft.client;

import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;

/** Internal explicit-ARGB bridge shared by the native canvas and owned control hooks. */
public final class NativeTextColors {
  public static void draw(GuiGraphics graphics, int argb, IntConsumer draw) {
    int alpha = argb >>> 24;
    if (alpha == 0) return;
    if (alpha >= 4) {
      draw.accept(argb);
      return;
    }
    // Font normalizes alpha0..3. Scope the multiplier around this glyph submission only.
    graphics.flush();
    var before = com.mojang.blaze3d.systems.RenderSystem.getShaderColor().clone();
    try {
      com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
          before[0], before[1], before[2], before[3] * alpha / 255f);
      draw.accept(argb | 0xff000000);
      graphics.flush();
    } finally {
      com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
          before[0], before[1], before[2], before[3]);
    }
  }

  private NativeTextColors() {}
}
