package design.inherent.reform.minecraft.mixin;

import design.inherent.reform.minecraft.client.LoadedGraphicResources;
import design.inherent.reform.reference.ReferenceStamp;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
abstract class LoadedGraphicResourcesMixin {
  @Shadow @Final private PackType type;
  @Unique private ReferenceStamp reform$reload;

  @Inject(method = "createReload", at = @At("HEAD"))
  private void reform$begin(CallbackInfoReturnable<ReloadInstance> callback) {
    if (type == PackType.CLIENT_RESOURCES
        && Minecraft.getInstance() != null
        && Minecraft.getInstance().getResourceManager() == (Object) this)
      reform$reload = LoadedGraphicResources.begin((ResourceManager) this);
  }

  @Inject(method = "createReload", at = @At("RETURN"))
  private void reform$complete(CallbackInfoReturnable<ReloadInstance> callback) {
    if (type == PackType.CLIENT_RESOURCES
        && Minecraft.getInstance() != null
        && Minecraft.getInstance().getResourceManager() == (Object) this) {
      var stamp = reform$reload;
      callback
          .getReturnValue()
          .done()
          .whenComplete((ignored, failed) -> LoadedGraphicResources.complete(stamp, failed));
    }
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void reform$closed(CallbackInfo callback) {
    if (type == PackType.CLIENT_RESOURCES
        && Minecraft.getInstance() != null
        && Minecraft.getInstance().getResourceManager() == (Object) this)
      LoadedGraphicResources.closed((ResourceManager) this);
  }
}
