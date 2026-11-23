package design.inherent.reform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import design.inherent.reform.minecraft.client.NativeControlStyle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Only owned input typography changes; all substring/caret decisions remain native. */
@Mixin(EditBox.class)
abstract class ControlInputFontMixin {
  @Unique
  private Style reform$valueStyle() {
    var theme = ((NativeControlStyle) this).reform$theme();
    return theme == null ? Style.EMPTY : theme.inputStyle();
  }

  @WrapOperation(
      method = {
        "findClickedPositionInText",
        "extractWidgetRenderState",
        "updateTextPosition",
        "scrollTo"
      },
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/Font;plainSubstrByWidth(Ljava/lang/String;I)Ljava/lang/String;"),
      require = 4)
  private String reform$head(Font font, String text, int width, Operation<String> original) {
    var style = reform$valueStyle();
    return style.equals(Style.EMPTY)
        ? original.call(font, text, width)
        : font.getSplitter().plainHeadByWidth(text, width, style);
  }

  @WrapOperation(
      method = "scrollTo",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/Font;plainSubstrByWidth(Ljava/lang/String;IZ)Ljava/lang/String;"))
  private String reform$tail(
      Font font, String text, int width, boolean backwards, Operation<String> original) {
    var style = reform$valueStyle();
    if (style.equals(Style.EMPTY)) return original.call(font, text, width, backwards);
    return backwards
        ? font.getSplitter().plainTailByWidth(text, width, style)
        : font.getSplitter().plainHeadByWidth(text, width, style);
  }

  @WrapOperation(
      method = {"extractWidgetRenderState", "updateTextPosition", "getScreenX"},
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/gui/Font;width(Ljava/lang/String;)I"),
      require = 3)
  private int reform$width(Font font, String text, Operation<Integer> original) {
    var style = reform$valueStyle();
    return style.equals(Style.EMPTY)
        ? original.call(font, text)
        : font.width(Component.literal(text).withStyle(style));
  }

  @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "applyFormat", at = @At("RETURN"))
  private net.minecraft.util.FormattedCharSequence reform$formatted(
      net.minecraft.util.FormattedCharSequence result) {
    var theme = ((NativeControlStyle) this).reform$theme();
    return theme == null ? result : theme.inputText(result);
  }
}
