package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;

/** Native model submission in the existing owned surface and native GUI state scope. */
final class NativeGuiModelGraphic {
  static NativeMeshGraphic prepare(
      NativeGraphicPreparationContext context,
      net.minecraft.world.phys.AABB bounds,
      boolean blockLight,
      double yawOrigin,
      double pitchOrigin,
      java.util.function.BiConsumer<GuiGraphics, com.mojang.blaze3d.vertex.PoseStack> render) {
    var client = context.client();
    var center = bounds.getCenter();
    double size = Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
    double extent = size > 0 ? size : 1;
    var surface =
        NativeMeshGraphic.surface(
            context,
            (width, height, camera) -> {
              RenderSystem.setProjectionMatrix(
                  new Matrix4f().setOrtho(0, width, height, 0, -1000, 1000),
                  VertexSorting.ORTHOGRAPHIC_Z);
              var modelView = RenderSystem.getModelViewStack();
              modelView.pushMatrix();
              try {
                modelView.identity();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.disableScissor();
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.disableColorLogicOp();
                RenderSystem.disablePolygonOffset();
                if (blockLight) Lighting.setupFor3DItems();
                else Lighting.setupForFlatItems();
                var graphics = new GuiGraphics(client, client.renderBuffers().bufferSource());
                var pose = graphics.pose();
                float fit = (float) (Math.min(width, height) * camera.zoom() / 1.75 / extent);
                double panX = width * (0.5 + camera.panX());
                double panY = height * (0.5 - camera.panY());
                if (!Float.isFinite(fit)
                    || fit <= 0
                    || !Float.isFinite((float) panX)
                    || !Float.isFinite((float) panY))
                  throw new design.inherent.reform.owner.PolicyRefusal(
                      "preview.camera",
                      "This model and camera cannot form a finite native transform; reset the view");
                pose.translate(panX, panY, 0);
                pose.scale(fit, -fit, fit);
                NativeGraphicCamera.rotate(pose, camera, yawOrigin, pitchOrigin);
                pose.translate(-center.x, -center.y, -center.z);
                try {
                  render.accept(graphics, pose);
                } finally {
                  graphics.flush();
                }
              } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
              }
            });
    return surface;
  }

  private NativeGuiModelGraphic() {}
}
