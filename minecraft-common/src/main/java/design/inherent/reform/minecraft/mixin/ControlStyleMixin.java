package design.inherent.reform.minecraft.mixin;

import design.inherent.reform.minecraft.client.ClientTheme;
import design.inherent.reform.minecraft.client.NativeControlStyle;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** No ambient theme: ordinary native/foreign controls keep a null, inert binding. */
@Mixin(AbstractWidget.class)
abstract class ControlStyleMixin implements NativeControlStyle {
  @Unique private ClientTheme reform$controlTheme;
  @Unique private boolean reform$inputArgb;

  @Override
  public final ClientTheme reform$theme() {
    return reform$controlTheme;
  }

  @Override
  public final void reform$theme(ClientTheme theme) {
    reform$controlTheme = theme;
  }

  @Override
  public final boolean reform$inputArgb() {
    return reform$inputArgb;
  }

  @Override
  public final void reform$inputArgb(boolean explicit) {
    reform$inputArgb = explicit;
  }

  @ModifyVariable(method = "setMessage", at = @At("HEAD"), argsOnly = true)
  private Component reform$styleCaption(Component message) {
    return reform$controlTheme == null
        ? message
        : reform$controlTheme.text(ClientTheme.TextRole.VALUE, message);
  }
}
