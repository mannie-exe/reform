package design.inherent.reform.minecraft.mixin;

import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read the real mounted state; constructor-populated client fields are not lifecycle evidence. */
@Mixin(Screen.class)
public interface ScreenMountStateAccessor {
  @Accessor("initialized")
  boolean reform$initialized();
}
