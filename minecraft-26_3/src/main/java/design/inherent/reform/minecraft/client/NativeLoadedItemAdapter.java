package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.mixin.GraphicCanvasAccessor;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.value.ValueType;
import java.util.List;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;

/** Owned prepared native item state, consumed by the real native GUI extraction pipeline. */
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
    var state = new State(context.ledger().limits().resources());
    context
        .client()
        .getItemModelResolver()
        .updateForTopItem(
            state,
            stack,
            ItemDisplayContext.GUI,
            context.client().level,
            context.client().player,
            0);
    state.freeze();
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        if (state.isEmpty()) return ClientMeshGraphic.DrawOutcome.EMPTY;
        state.camera(draw.camera());
        var bounds = state.getModelBoundingBox();
        double sx = Math.max(1, bounds.getXsize()), sy = Math.max(1, bounds.getYsize());
        float fit =
            (float)
                (Math.min(draw.width() / sx, draw.height() / sy) * draw.camera().zoom() / 16 / 1.1);
        if (!(fit > 0) || !Float.isFinite(fit)) return ClientMeshGraphic.DrawOutcome.WAITING;
        var center = bounds.getCenter();
        var pose =
            new Matrix3x2f(draw.graphics().pose())
                .translate(
                    (float) (draw.x() + draw.width() * (0.5 + draw.camera().panX())),
                    (float) (draw.y() + draw.height() * (0.5 - draw.camera().panY())))
                .scale(fit)
                .translate((float) (-8 - center.x * 16), (float) (-8 + center.y * 16));
        // The outer callback's native scissor remains active; preserve its exact intersection.
        var clip = draw.graphics().textRenderer().defaultParameters().scissor();
        ((GraphicCanvasAccessor) draw.graphics())
            .reform$graphicState()
            .addItem(new GuiItemRenderState(pose, state, 0, 0, clip));
        return ClientMeshGraphic.DrawOutcome.RENDERED;
      }

      public void release(NativeGraphicReleaseContext release) {
        state.clear();
      }
    };
  }

  private static final class State extends TrackingItemStackRenderState {
    private final int maximumLayers;
    private int layers;
    private final Matrix4f rotation = new Matrix4f();
    private List<?> identity = List.of();
    private Object key = identity;
    private GraphicCamera camera;

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
      var layer = super.newLayer();
      layers++;
      return layer;
    }

    void freeze() {
      identity = List.copyOf((List<?>) super.getModelIdentity());
      setOversizedInGui(true);
    }

    void camera(GraphicCamera value) {
      if (value.equals(camera)) return;
      rotation
          .identity()
          .rotateY((float) Math.toRadians(value.yaw() - GraphicCamera.STANDARD.yaw()))
          .rotateX((float) Math.toRadians(value.pitch() - GraphicCamera.STANDARD.pitch()));
      camera = value;
      key = List.of(identity, value.yaw(), value.pitch());
    }

    @Override
    public Object getModelIdentity() {
      return key;
    }

    // Static prepared geometry. A changed camera receives a distinct native cache identity.
    @Override
    public boolean isAnimated() {
      return false;
    }

    @Override
    public void submit(
        com.mojang.blaze3d.vertex.PoseStack pose,
        net.minecraft.client.renderer.SubmitNodeCollector collector,
        int light,
        int overlay,
        int outline) {
      pose.pushPose();
      try {
        pose.last().mulPose(rotation);
        super.submit(pose, collector, light, overlay, outline);
      } finally {
        pose.popPose();
      }
    }

    @Override
    public void visitExtents(java.util.function.Consumer<org.joml.Vector3fc> output) {
      var scratch = new org.joml.Vector3f();
      super.visitExtents(value -> output.accept(scratch.set(value).mulPosition(rotation)));
    }

    @Override
    public AABB getModelBoundingBox() {
      var bounds = new AABB.Builder();
      visitExtents(bounds::include);
      return bounds.isDefined() ? bounds.build() : AABB.ofSize(Vec3.ZERO, 0, 0, 0);
    }

    @Override
    public void clear() {
      super.clear();
      layers = 0;
      rotation.identity();
      camera = null;
      identity = List.of();
      key = identity;
      ((List<?>) super.getModelIdentity()).clear();
    }
  }
}
