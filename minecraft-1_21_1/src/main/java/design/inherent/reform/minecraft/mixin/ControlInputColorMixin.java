package design.inherent.reform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import design.inherent.reform.minecraft.client.NativeControlStyle;
import design.inherent.reform.minecraft.client.NativeTextColors;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;

/** Native text submission only; chrome, caret, selection, and editing stay in EditBox. */
@Mixin(EditBox.class)
abstract class ControlInputColorMixin {
  private int reform$draw(
      GuiGraphics graphics,
      Font font,
      FormattedCharSequence text,
      int x,
      int y,
      int argb,
      boolean shadow,
      java.util.function.IntUnaryOperator original) {
    if (!((NativeControlStyle) this).reform$inputArgb()) return original.applyAsInt(argb);
    int[] end = {(int) (x + font.getSplitter().stringWidth(text)) + (shadow ? 1 : 0)};
    NativeTextColors.draw(graphics, argb, color -> end[0] = original.applyAsInt(color));
    return end[0];
  }

  @Group(name = "formatted_color", min = 2)
  @WrapOperation(
      method = "renderWidget",
      require = 0,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
  private int reform$formatted(
      GuiGraphics graphics,
      Font font,
      FormattedCharSequence text,
      int x,
      int y,
      int color,
      Operation<Integer> original) {
    return reform$draw(
        graphics,
        font,
        text,
        x,
        y,
        color,
        true,
        admitted -> original.call(graphics, font, text, x, y, admitted));
  }

  @Group(name = "component_color", min = 1)
  @WrapOperation(
      method = "renderWidget",
      require = 0,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
  private int reform$component(
      GuiGraphics graphics,
      Font font,
      Component text,
      int x,
      int y,
      int color,
      Operation<Integer> original) {
    return reform$draw(
        graphics,
        font,
        text.getVisualOrderText(),
        x,
        y,
        color,
        true,
        admitted -> original.call(graphics, font, text, x, y, admitted));
  }

  @Group(name = "plain_color", min = 2)
  @WrapOperation(
      method = "renderWidget",
      require = 0,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
  private int reform$plain(
      GuiGraphics graphics,
      Font font,
      String text,
      int x,
      int y,
      int color,
      Operation<Integer> original) {
    return reform$draw(
        graphics,
        font,
        Component.literal(text).getVisualOrderText(),
        x,
        y,
        color,
        true,
        admitted -> original.call(graphics, font, text, x, y, admitted));
  }

  @Group(name = "formatted_color", min = 2)
  @WrapOperation(
      method = "renderWidget",
      require = 0,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"))
  private int reform$formattedShadow(
      GuiGraphics graphics,
      Font font,
      FormattedCharSequence text,
      int x,
      int y,
      int color,
      boolean shadow,
      Operation<Integer> original) {
    return reform$draw(
        graphics,
        font,
        text,
        x,
        y,
        color,
        shadow,
        admitted -> original.call(graphics, font, text, x, y, admitted, shadow));
  }

  @Group(name = "component_color", min = 1)
  @WrapOperation(
      method = "renderWidget",
      require = 0,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
  private int reform$componentShadow(
      GuiGraphics graphics,
      Font font,
      Component text,
      int x,
      int y,
      int color,
      boolean shadow,
      Operation<Integer> original) {
    return reform$draw(
        graphics,
        font,
        text.getVisualOrderText(),
        x,
        y,
        color,
        shadow,
        admitted -> original.call(graphics, font, text, x, y, admitted, shadow));
  }

  @Group(name = "plain_color", min = 2)
  @WrapOperation(
      method = "renderWidget",
      require = 0,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"))
  private int reform$plainShadow(
      GuiGraphics graphics,
      Font font,
      String text,
      int x,
      int y,
      int color,
      boolean shadow,
      Operation<Integer> original) {
    return reform$draw(
        graphics,
        font,
        Component.literal(text).getVisualOrderText(),
        x,
        y,
        color,
        shadow,
        admitted -> original.call(graphics, font, text, x, y, admitted, shadow));
  }
}
