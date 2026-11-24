package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicCamera;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;

/** Immediate Minecraft 1.21.1 submission inside a scoped native pose and measured clip. */
public record NativeGraphicDrawContext(
    GuiGraphics graphics, int x, int y, int width, int height, GraphicCamera camera) {
  public NativeGraphicDrawContext {
    Objects.requireNonNull(graphics);
    Objects.requireNonNull(camera);
    if (width < 1 || height < 1) throw new IllegalArgumentException("Empty native viewport");
  }
}
