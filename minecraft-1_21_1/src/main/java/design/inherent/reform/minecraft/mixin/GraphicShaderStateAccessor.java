package design.inherent.reform.minecraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read only the actual light pair so a native preview restores the caller's lighting. */
@Mixin(RenderSystem.class)
public interface GraphicShaderStateAccessor {
  @Accessor("shaderLightDirections")
  static Vector3f[] reform$graphicLights() {
    throw new AssertionError("Client light accessor was not installed");
  }
}
