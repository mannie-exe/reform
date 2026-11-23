package design.inherent.reform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import design.inherent.reform.minecraft.client.*;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Only an explicitly bound control changes its native extracted caption parameters. */
@Mixin(AbstractButton.class)
abstract class ControlCaptionMixin {
  @WrapOperation(
      method = "extractDefaultLabel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/components/AbstractButton;extractScrollingStringOverContents(Lnet/minecraft/client/gui/ActiveTextCollector;Lnet/minecraft/network/chat/Component;I)V"))
  private void reform$caption(
      AbstractButton widget,
      ActiveTextCollector collector,
      Component label,
      int margin,
      Operation<Void> original) {
    var theme = ((NativeControlStyle) widget).reform$theme();
    var role = widget.active ? ClientTheme.TextRole.VALUE : ClientTheme.TextRole.SUPPORT;
    if (theme == null || !theme.explicitColor(role)) {
      original.call(widget, collector, label, margin);
      return;
    }
    int argb = theme.color(role);
    if (argb >>> 24 == 0) return;
    var previous = collector.defaultParameters();
    collector.defaultParameters(previous.withOpacity(previous.opacity() * (argb >>> 24) / 255f));
    try {
      var caption = theme.text(role, label);
      caption =
          caption
              .copy()
              .withStyle(caption.getStyle().applyTo(Style.EMPTY.withColor(argb & 0xffffff)));
      original.call(widget, collector, caption, margin);
    } finally {
      collector.defaultParameters(previous);
    }
  }
}
