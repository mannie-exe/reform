package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.value.ValueType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;

/** Cached native item model in an owned offscreen surface; never an inventory or world writer. */
final class NativeLoadedItemAdapter implements NativeGraphicAdapter<ClientItemSubject> {
  static final NativeLoadedItemAdapter INSTANCE = new NativeLoadedItemAdapter();

  public DocumentId id() {
    return DocumentId.parse("reform:loaded_item");
  }

  public ValueType<ClientItemSubject> inputType() {
    return ClientItemSubject.type();
  }

  public Prepared prepare(ClientItemSubject input, NativeGraphicPreparationContext context) {
    var stack = NativeItemSnapshots.decode(input);
    if (stack.isEmpty())
      return new Prepared() {
        public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
          return ClientMeshGraphic.DrawOutcome.EMPTY;
        }

        public void release(NativeGraphicReleaseContext release) {}
      };
    var client = context.client();
    var renderer = client.getItemRenderer();
    var model = renderer.getModel(stack, client.level, client.player, 0);
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
                if (model.usesBlockLight()) Lighting.setupFor3DItems();
                else Lighting.setupForFlatItems();
                var graphics = new GuiGraphics(client, client.renderBuffers().bufferSource());
                var pose = graphics.pose();
                float fit = (float) (Math.min(width, height) * camera.zoom() / 1.75);
                pose.translate(width * (0.5 + camera.panX()), height * (0.5 - camera.panY()), 0);
                pose.scale(fit, -fit, fit);
                pose.mulPose(
                    Axis.YP.rotationDegrees((float) (camera.yaw() - GraphicCamera.STANDARD.yaw())));
                pose.mulPose(
                    Axis.XP.rotationDegrees(
                        (float) (camera.pitch() - GraphicCamera.STANDARD.pitch())));
                try {
                  renderer.render(
                      stack,
                      ItemDisplayContext.GUI,
                      false,
                      pose,
                      graphics.bufferSource(),
                      15728880,
                      OverlayTexture.NO_OVERLAY,
                      model);
                } finally {
                  graphics.flush();
                }
              } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
              }
            });
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw);
      }

      // The surface was registered before its first native allocation and shares this lifetime.
      public void release(NativeGraphicReleaseContext release) {}
    };
  }
}
