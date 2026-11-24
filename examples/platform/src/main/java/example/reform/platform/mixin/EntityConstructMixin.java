package example.reform.platform.mixin;

import example.reform.platform.PlatformEntityChecks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Passive identity observation; no entity, world, or cleanup mutation. */
@Mixin(Entity.class)
abstract class EntityConstructMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void constructed(CallbackInfo callback) {
    PlatformEntityChecks.constructed(this);
  }
}
