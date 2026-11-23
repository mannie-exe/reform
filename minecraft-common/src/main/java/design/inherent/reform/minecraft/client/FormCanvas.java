package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * The text and geometry used by forms; each game owns its real native drawing/extraction context.
 */
public sealed interface FormCanvas permits NativeCanvas {
  void enableScissor(int left, int top, int right, int bottom);

  void disableScissor();

  void widget(net.minecraft.client.gui.components.AbstractWidget widget, int x, int y, float delta);

  void fill(int left, int top, int right, int bottom, int color);

  void hLine(int left, int right, int y, int color);

  void vLine(int x, int top, int bottom, int color);

  void drawString(Font font, Component text, int x, int y, int color);

  void drawString(Font font, String text, int x, int y, int color);

  void drawString(Font font, FormattedCharSequence text, int x, int y, int color);

  void drawString(Font font, Component text, int x, int y, int color, boolean shadow);

  void drawString(Font font, String text, int x, int y, int color, boolean shadow);

  void drawString(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow);

  void drawCenteredString(Font font, Component text, int x, int y, int color);

  void drawWordWrap(Font font, FormattedText text, int x, int y, int width, int color);

  /** Explicit ARGB, including transparent text; legacy RGB normalization does not apply. */
  void drawArgb(Font font, FormattedCharSequence text, int x, int y, int argb, boolean shadow);

  /** Preserve the existing native form palette, whose text colors may omit an alpha byte. */
  static int textColor(int color) {
    return (color & 0xfc000000) == 0 ? color | 0xff000000 : color;
  }
}
