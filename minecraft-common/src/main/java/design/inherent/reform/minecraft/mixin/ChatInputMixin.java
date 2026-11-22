package design.inherent.reform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.internal.ClientCommandInvocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;

/** Both games submit typed chat here; component RUN_COMMAND follows another native route. */
@Mixin(ChatScreen.class)
abstract class ChatInputMixin {
  @WrapMethod(method = "handleChatInput")
  private void reform$typedInput(String input, boolean recent, Operation<Void> original) {
    var client = Minecraft.getInstance();
    if (recent && client.isSameThread() && NativeScreens.current(client) == (Object) this)
      ClientCommandInvocation.fromNativeChat(() -> original.call(input, recent));
    else original.call(input, recent);
  }
}
