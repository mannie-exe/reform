package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.value.ValueType;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

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
    var surface =
        NativeFeatureGraphic.prepare(
            context,
            state.getModelBoundingBox(),
            state.usesBlockLight(),
            GraphicCamera.STANDARD.yaw(),
            GraphicCamera.STANDARD.pitch(),
            (pose, submits) -> state.submit(pose, submits, 15728880, OverlayTexture.NO_OVERLAY, 0));
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw, context::isCurrent);
      }

      public void release(NativeGraphicReleaseContext release) {
        state.clear();
      }
    };
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
