package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.vertex.PoseStack;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.value.ValueType;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/** Native feature rendering into the shared owned target, without enlarging a GUI icon cache. */
final class NativeLoadedItemAdapter implements NativeGraphicAdapter<ClientItemSubject> {
  static final NativeLoadedItemAdapter INSTANCE = new NativeLoadedItemAdapter();

  public DocumentId id() {
    return DocumentId.parse("reform:loaded_item");
  }

  public ValueType<ClientItemSubject> inputType() {
    return ClientItemSubject.type();
  }

  @SuppressWarnings("try")
  public Prepared prepare(ClientItemSubject input, NativeGraphicPreparationContext context) {
    var stack = NativeItemSnapshots.decode(input);
    var state = new State(context.ledger().limits().resources());
    var client = context.client();
    client
        .getItemModelResolver()
        .updateForTopItem(state, stack, ItemDisplayContext.GUI, client.level, client.player, 0);
    if (state.isEmpty())
      return new Prepared() {
        public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
          return ClientMeshGraphic.DrawOutcome.EMPTY;
        }

        public void release(NativeGraphicReleaseContext release) {
          state.clear();
        }
      };
    var bounds = state.getModelBoundingBox();
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
                    .setupFor(
                        state.usesBlockLight()
                            ? Lighting.Entry.ITEMS_3D
                            : Lighting.Entry.ITEMS_FLAT);
                var pose = new PoseStack();
                pose.translate(width * (0.5 + camera.panX()), height * (0.5 - camera.panY()), 0);
                float fit =
                    (float)
                        (Math.min(width, height)
                            * camera.zoom()
                            / 1.75
                            / (extent > 0 ? extent : 1));
                pose.scale(fit, -fit, fit);
                pose.last()
                    .mulPose(
                        new Matrix4f()
                            .rotateY(
                                (float) Math.toRadians(camera.yaw() - GraphicCamera.STANDARD.yaw()))
                            .rotateX(
                                (float)
                                    Math.toRadians(
                                        camera.pitch() - GraphicCamera.STANDARD.pitch())));
                pose.translate(-center.x, -center.y, -center.z);
                state.submit(pose, submits, 15728880, OverlayTexture.NO_OVERLAY, 0);
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
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw, context::isCurrent);
      }

      public void release(NativeGraphicReleaseContext release) {
        state.clear();
      }
    };
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

  private static final class State extends ItemStackRenderState {
    private final int maximumLayers;
    private int layers;

    State(int maximumLayers) {
      this.maximumLayers = maximumLayers;
    }

    @Override
    public void ensureCapacity(int requested) {
      if (requested < 0 || requested > maximumLayers - layers)
        throw new design.inherent.reform.owner.PolicyRefusal(
            "preview.budget", "The native item exceeds its prepared layer allowance");
      super.ensureCapacity(requested);
    }

    @Override
    public LayerRenderState newLayer() {
      var result = super.newLayer();
      layers++;
      return result;
    }

    @Override
    public void clear() {
      super.clear();
      layers = 0;
    }
  }
}
