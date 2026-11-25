package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import net.minecraft.client.gui.Font;

/** Cached, noninteractive field artwork. Preparation must happen outside drawing. */
public interface ClientEntryGraphic extends AutoCloseable {
  @FunctionalInterface
  interface Factory {
    /** Register partial-resource cleanup before allocating. The form owns the returned content. */
    ClientEntryGraphic create(ClientViewContext context);
  }

  /**
   * The host clips drawing to these measured bounds; no input or document capability is supplied.
   */
  void render(FormCanvas canvas, Font font, FormGeometry.Placement bounds, ClientTheme theme);

  @Override
  default void close() {}
}
