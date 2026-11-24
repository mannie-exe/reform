package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.value.ValueType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Fresh native renderer and baked parts: never borrows the live dispatcher's mutable poses. */
final class NativeChestAdapter implements NativeGraphicAdapter<ClientChestSubject> {
  static final NativeChestAdapter INSTANCE = new NativeChestAdapter();

  public DocumentId id() {
    return DocumentId.parse("reform:isolated_chest");
  }

  public ValueType<ClientChestSubject> inputType() {
    return ClientChestSubject.type();
  }

  public Prepared prepare(ClientChestSubject input, NativeGraphicPreparationContext context) {
    var bounds = new NativeModelGeometry(context.ledger().limits(), 3, "preview.chest_geometry");
    var client = context.client();
    var renderer =
        new ChestRenderer<ChestBlockEntity>(
            new BlockEntityRendererProvider.Context(
                client.getBlockEntityRenderDispatcher(),
                client.getBlockRenderer(),
                client.getItemRenderer(),
                client.getEntityRenderDispatcher(),
                client.getEntityModels(),
                client.font));
    var chest = NativeChestSubjects.create(input);
    // Native Material/Sprite wrapping and ModelPart compilation produce the actual posed vertices.
    // This sink has no native buffer or GPU allocation and retains no geometry.
    renderer.render(
        chest, 0, new PoseStack(), layer -> bounds, 15728880, OverlayTexture.NO_OVERLAY);
    var surface =
        NativeGuiModelGraphic.prepare(
            context,
            bounds.bounds(),
            true,
            0,
            0,
            (graphics, pose) ->
                renderer.render(
                    chest, 0, pose, graphics.bufferSource(), 15728880, OverlayTexture.NO_OVERLAY));
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw);
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }
}
