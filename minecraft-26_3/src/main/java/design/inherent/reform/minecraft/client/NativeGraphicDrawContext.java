package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicCamera;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Minecraft 26.3 extraction; ownership lasts through actual consumption and GPU retirement. */
public record NativeGraphicDrawContext(
    GuiGraphicsExtractor graphics, int x, int y, int width, int height, GraphicCamera camera) {
  public NativeGraphicDrawContext {
    Objects.requireNonNull(graphics);
    Objects.requireNonNull(camera);
    if (width < 1 || height < 1) throw new IllegalArgumentException("Empty native viewport");
  }
}
