package example.reform.platform.mixin;

import example.reform.platform.PlatformNavigationTickChecks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fixture-only observation at an actual mounted view tick; never packaged with the library. */
@Mixin(targets = "design.inherent.reform.minecraft.client.SettingsView", remap = false)
public abstract class NavigationTickMixin {
  @Inject(method = "tick", at = @At("HEAD"), require = 1)
  private void reformPlatform$navigationTick(CallbackInfo ignored) {
    PlatformNavigationTickChecks.fire();
  }
}
