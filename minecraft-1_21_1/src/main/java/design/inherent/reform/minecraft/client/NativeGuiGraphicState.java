package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import design.inherent.reform.minecraft.mixin.GraphicShaderStateAccessor;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Preserve tracked GUI shader state as well as actual GL state around native game renderers. */
final class NativeGuiGraphicState implements AutoCloseable {
  private final NativeGraphicGlState nativeState = new NativeGraphicGlState();
  private final org.joml.Matrix4f projection =
      new org.joml.Matrix4f(RenderSystem.getProjectionMatrix());
  private final com.mojang.blaze3d.vertex.VertexSorting sorting = RenderSystem.getVertexSorting();
  private final org.joml.Matrix4f texture = new org.joml.Matrix4f(RenderSystem.getTextureMatrix());
  private final ShaderInstance shader = RenderSystem.getShader();
  private final float[] color = RenderSystem.getShaderColor().clone();
  private final float[] fog = RenderSystem.getShaderFogColor().clone();
  private final float fogStart = RenderSystem.getShaderFogStart(),
      fogEnd = RenderSystem.getShaderFogEnd();
  private final com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
  private final Vector3f[] lights = GraphicShaderStateAccessor.reform$graphicLights().clone();
  private final Vector3f[] lightValues = new Vector3f[2];
  private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
  private final int[] textures = new int[12], bindings = new int[12];

  NativeGuiGraphicState() {
    for (int i = 0; i < 2; i++) if (lights[i] != null) lightValues[i] = new Vector3f(lights[i]);
    for (int i = 0; i < textures.length; i++) {
      textures[i] = RenderSystem.getShaderTexture(i);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
      bindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    }
    GL13.glActiveTexture(active);
  }

  @Override
  public void close() {
    try {
      RenderSystem.setShader(() -> shader);
      RenderSystem.setProjectionMatrix(projection, sorting);
      RenderSystem.setTextureMatrix(texture);
      RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
      RenderSystem.setShaderFogColor(fog[0], fog[1], fog[2], fog[3]);
      RenderSystem.setShaderFogStart(fogStart);
      RenderSystem.setShaderFogEnd(fogEnd);
      RenderSystem.setShaderFogShape(fogShape);
      for (int i = 0; i < 2; i++) if (lights[i] != null) lights[i].set(lightValues[i]);
      RenderSystem.setShaderLights(lights[0], lights[1]);
      for (int i = 0; i < textures.length; i++) {
        RenderSystem.setShaderTexture(i, textures[i]);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
        RenderSystem.bindTexture(bindings[i]);
      }
      RenderSystem.activeTexture(active);
    } finally {
      nativeState.closeTracked();
    }
  }
}
