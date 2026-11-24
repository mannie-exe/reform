package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/** Shared owned target and scoped native feature submission, without another resource lifetime. */
final class NativeFeatureGraphic {
  @SuppressWarnings("try")
  static NativeMeshGraphic prepare(
      NativeGraphicPreparationContext context,
      net.minecraft.world.phys.AABB bounds,
      boolean blockLight,
      double yawOrigin,
      double pitchOrigin,
      java.util.function.BiConsumer<PoseStack, SubmitNodeStorage> submit) {
    var client = context.client();
    var center = bounds.getCenter();
    double extent = Math.max(bounds.getXsize(), Math.max(bounds.getYsize(), bounds.getZsize()));
    var projection = new Projection();
    var submits = new SubmitNodeStorage();
    // Attach the surface before any GPU allocation, and retain it through the enclosing use.
    var uniform = new com.mojang.renderpearl.api.buffers.GpuBuffer[1];
    var surface =
        NativeMeshGraphic.surface(
            context,
            (color, depth, width, height, camera) -> {
              var encoder = RenderSystem.getDevice().createCommandEncoder();
              try (var borrowed = new GuiState();
                  Scope submission = encoder::submit) {
                borrowed.modelView.identity();
                projection.setupOrtho(-1000, 1000, width, height, true);
                try (var memory = MemoryStack.stackPush()) {
                  var bytes =
                      Std140Builder.onStack(memory, RenderSystem.PROJECTION_MATRIX_UBO_SIZE)
                          .putMat4f(projection.getMatrix(new Matrix4f()))
                          .get();
                  var upload = RenderSystem.getDevice().createCommandEncoder();
                  upload.writeToBuffer(uniform[0].slice(), bytes);
                  upload.submit();
                }
                RenderSystem.setProjectionMatrix(uniform[0].slice(), ProjectionType.ORTHOGRAPHIC);
                RenderSystem.enableScissorForRenderTypeDraws(0, 0, width, height);
                client
                    .gameRenderer
                    .lighting()
                    .setupFor(blockLight ? Lighting.Entry.ITEMS_3D : Lighting.Entry.ITEMS_FLAT);
                var pose = new PoseStack();
                float fit =
                    (float)
                        (Math.min(width, height)
                            * camera.zoom()
                            / 1.75
                            / (extent > 0 ? extent : 1));
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
                submit.accept(pose, submits);
                try (var frame =
                        client.gameRenderer.featureRenderDispatcher().prepareFrame(submits);
                    var pass =
                        encoder.createRenderPass(
                            () -> "re:form native item",
                            color,
                            Optional.of(GuiRenderer.CLEAR_COLOR),
                            depth,
                            OptionalDouble.of(0))) {
                  RenderSystem.bindDefaultUniforms(pass);
                  FeatureRenderDispatcher.renderAllFeatures(pass, frame);
                }
              }
            });
    uniform[0] =
        surface.uniformBuffer(
            RenderSystem.PROJECTION_MATRIX_UBO_SIZE, "re:form native item projection");
    return surface;
  }

  private interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Try-with-resources preserves the primary render failure if submission/restoration also fail.
   */
  private static final class GuiState implements Scope {
    private final com.mojang.renderpearl.api.buffers.GpuBufferSlice
        projection = RenderSystem.getProjectionMatrixBuffer(),
        lights = RenderSystem.getShaderLights(),
        fog = RenderSystem.getShaderFog();
    private final ProjectionType type = RenderSystem.getProjectionType();
    private final ScissorState scissor =
        new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
    private final org.joml.Matrix4fStack modelView = RenderSystem.getModelViewStack();

    GuiState() {
      modelView.pushMatrix();
    }

    public void close() {
      NativeGraphicCleanup.run(
          () -> RenderSystem.setProjectionMatrix(projection, type),
          () -> RenderSystem.setShaderLights(lights),
          () -> RenderSystem.setShaderFog(fog),
          () -> RenderSystem.getScissorStateForRenderTypeDraws().setFrom(scissor),
          modelView::popMatrix);
    }
  }

  private NativeFeatureGraphic() {}
}
