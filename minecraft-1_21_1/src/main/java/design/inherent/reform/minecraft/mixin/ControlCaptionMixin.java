package design.inherent.reform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import design.inherent.reform.minecraft.client.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import org.spongepowered.asm.mixin.Mixin;

/** Retains native cycle/button input, caption scrolling, chrome, and narration. */
@Mixin(AbstractButton.class)
abstract class ControlCaptionMixin {
  @WrapMethod(method = "renderString")
  private void reform$caption(
      GuiGraphics graphics, Font font, int nativeColor, Operation<Void> original) {
    var widget = (AbstractButton) (Object) this;
    var theme = ((NativeControlStyle) this).reform$theme();
    var role = widget.active ? ClientTheme.TextRole.VALUE : ClientTheme.TextRole.SUPPORT;
    if (theme == null || !theme.explicitColor(role)) {
      original.call(graphics, font, nativeColor);
      return;
    }
    int argb = theme.color(role);
    int alpha = (argb >>> 24) * (nativeColor >>> 24) / 255;
    NativeTextColors.draw(
        graphics, argb & 0xffffff | alpha << 24, color -> original.call(graphics, font, color));
  }
}
