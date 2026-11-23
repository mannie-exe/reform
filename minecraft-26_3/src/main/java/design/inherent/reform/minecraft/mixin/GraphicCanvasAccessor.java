package design.inherent.reform.minecraft.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow typed bridge to submit private views with the native premultiplied-alpha pipeline. */
@Mixin(GuiGraphicsExtractor.class)
public interface GraphicCanvasAccessor {
  @Accessor("guiRenderState")
  GuiRenderState reform$graphicState();

  @Invoker("innerBlit")
  void reform$blitGraphic(
      RenderPipeline pipeline,
      GpuTextureView texture,
      GpuSampler sampler,
      int x0,
      int y0,
      int x1,
      int y1,
      float u0,
      float u1,
      float v0,
      float v1,
      int color);
}
