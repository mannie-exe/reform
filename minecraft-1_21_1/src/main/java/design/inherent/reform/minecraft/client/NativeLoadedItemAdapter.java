package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.value.ValueType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;

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
        NativeGuiModelGraphic.prepare(
            context,
            new net.minecraft.world.phys.AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
            model.usesBlockLight(),
            GraphicCamera.STANDARD.yaw(),
            GraphicCamera.STANDARD.pitch(),
            (graphics, pose) ->
                renderer.render(
                    stack,
                    ItemDisplayContext.GUI,
                    false,
                    pose,
                    graphics.bufferSource(),
                    15728880,
                    OverlayTexture.NO_OVERLAY,
                    model));
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw);
      }

      // The surface was registered before its first native allocation and shares this lifetime.
      public void release(NativeGraphicReleaseContext release) {}
    };
  }
}
