package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.mixin.GraphicCanvasAccessor;
import design.inherent.reform.minecraft.mixin.GraphicItemLayerAccessor;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
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
    private final List<LayerRenderState> owned = new ArrayList<>();
    private final List<Matrix4f> originals = new ArrayList<>();
    private List<?> identity = List.of();
    private Object key = identity;
    private GraphicCamera camera;

    State(int maximumLayers) {
      this.maximumLayers = maximumLayers;
    }

    @Override
    public void ensureCapacity(int requested) {
      if (requested < 0 || requested > maximumLayers - owned.size())
        throw new design.inherent.reform.owner.PolicyRefusal(
            "preview.budget", "The native item exceeds its prepared layer allowance");
      super.ensureCapacity(requested);
    }

    @Override
    public LayerRenderState newLayer() {
      var layer = super.newLayer();
      owned.add(layer);
      return layer;
    }

    void freeze() {
      for (var layer : owned)
        originals.add(
            new Matrix4f(((GraphicItemLayerAccessor) layer).reform$graphicItemTransform()));
      identity = List.copyOf((List<?>) super.getModelIdentity());
      setOversizedInGui(true);
    }

    void camera(GraphicCamera value) {
      if (value.equals(camera)) return;
      var rotation =
          new Matrix4f()
              .rotateY((float) Math.toRadians(value.yaw() - GraphicCamera.STANDARD.yaw()))
              .rotateX((float) Math.toRadians(value.pitch() - GraphicCamera.STANDARD.pitch()));
      for (int i = 0; i < owned.size(); i++)
        owned.get(i).setLocalTransform(new Matrix4f(originals.get(i)).mul(rotation));
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
    public AABB getModelBoundingBox() {
      var bounds = new AABB.Builder();
      visitExtents(bounds::include);
      return bounds.isDefined() ? bounds.build() : AABB.ofSize(Vec3.ZERO, 0, 0, 0);
    }

    @Override
    public void clear() {
      super.clear();
      owned.clear();
      originals.clear();
      camera = null;
      identity = List.of();
      key = identity;
      ((List<?>) super.getModelIdentity()).clear();
    }
  }
}
