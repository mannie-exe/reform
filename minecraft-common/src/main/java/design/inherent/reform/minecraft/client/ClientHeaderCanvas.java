package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.ThemeValues;
import net.minecraft.client.gui.Font;

/**
 * Cached header content only. No discovery, decoding, compilation, or entity creation in render.
 */
public interface ClientHeaderCanvas extends AutoCloseable {
  @FunctionalInterface
  interface Factory {
    /** The returned canvas is owned by the shell; register partial-resource cleanup on context. */
    ClientHeaderCanvas create(ClientViewContext context);
  }

  void render(FormCanvas canvas, Font font, FormGeometry.Placement bounds, ThemeValues theme);

  @Override
  default void close() {}
}
