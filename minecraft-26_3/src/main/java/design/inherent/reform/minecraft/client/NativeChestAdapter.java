package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.value.ValueType;
import java.util.Map;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Detached native state and fresh native renderer, submitted by the existing feature pipeline. */
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
    var parts = new FreshParts(client.getEntityModels());
    var renderer =
        new ChestRenderer<ChestBlockEntity>(
            new BlockEntityRendererProvider.Context(
                client.getBlockEntityRenderDispatcher(),
                ((design.inherent.reform.minecraft.mixin.GraphicBlockResolverAccessor)
                        client.getBlockEntityRenderDispatcher())
                    .reform$blockModelResolver(),
                client.getItemModelResolver(),
                client.getEntityRenderDispatcher(),
                parts,
                client.font,
                client.getAtlasManager(),
                client.playerSkinRenderCache()));
    var chest = NativeChestSubjects.create(input);
    var state = renderer.createRenderState();
    renderer.extractRenderState(chest, state, 0, Vec3.ZERO, null);
    // Use the same owned root and the actual native pose setup for fit; retain no mesh.
    var model = new ChestModel(parts.single);
    float inverse = 1 - state.open;
    model.setupAnim(1 - inverse * inverse * inverse);
    parts.single.render(new PoseStack(), bounds, 15728880, OverlayTexture.NO_OVERLAY);
    var camera = new CameraRenderState();
    var surface =
        NativeFeatureGraphic.prepare(
            context,
            bounds.bounds(),
            true,
            0,
            0,
            (pose, submits) -> renderer.submit(state, pose, submits, camera));
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw, context::isCurrent);
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }

  /** Delegates only fresh baking; never edits or replaces the game's model-set registry. */
  private static final class FreshParts extends EntityModelSet {
    private final EntityModelSet source;
    private ModelPart single;

    FreshParts(EntityModelSet source) {
      super(Map.of());
      this.source = source;
    }

    @Override
    public ModelPart bakeLayer(ModelLayerLocation layer) {
      var root = source.bakeLayer(layer);
      if (layer.equals(ModelLayers.CHEST)) single = root;
      return root;
    }
  }
}
