package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueType;
import java.util.Map;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.CowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.PigRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;

/** Fresh native animal render states and models through actual feature submission. */
final class NativeEntityAdapter implements NativeGraphicAdapter<ClientEntitySubject> {
  static final NativeEntityAdapter INSTANCE = new NativeEntityAdapter();

  public DocumentId id() {
    return DocumentId.parse("reform:curated_entity");
  }

  public ValueType<ClientEntitySubject> inputType() {
    return ClientEntitySubject.type();
  }

  public Prepared prepare(ClientEntitySubject input, NativeGraphicPreparationContext context) {
    var client = context.client();
    if (client.level == null || client.player == null || client.getConnection() == null)
      throw new NativeGraphicUnavailable(
          "preview.entity_context",
          "This native animal reference needs an existing joined world; no world was opened");
    var parts = new FreshParts(client.getEntityModels(), context.ledger().limits().resources());
    var factory =
        new EntityRendererProvider.Context(
            client.getEntityRenderDispatcher(),
            ((design.inherent.reform.minecraft.mixin.GraphicBlockResolverAccessor)
                    client.getBlockEntityRenderDispatcher())
                .reform$blockModelResolver(),
            client.getItemModelResolver(),
            client.getMapRenderer(),
            client.getResourceManager(),
            parts,
            ((design.inherent.reform.minecraft.mixin.GraphicEquipmentAssetsAccessor)
                    client.getEntityRenderDispatcher())
                .reform$equipmentAssets(),
            client.getAtlasManager(),
            client.font,
            client.playerSkinRenderCache(),
            client.getPalettedTextureManager());
    return switch (input.kind()) {
      case COW -> {
        var animal = new Cow(EntityTypes.COW, client.level);
        animal.setBaby(input.baby());
        yield prepare(new CowRenderer(factory), animal, context);
      }
      case PIG -> {
        var animal = new Pig(EntityTypes.PIG, client.level);
        animal.setBaby(input.baby());
        yield prepare(new PigRenderer(factory), animal, context);
      }
    };
  }

  private static <
          T extends Mob,
          S extends LivingEntityRenderState,
          M extends net.minecraft.client.model.EntityModel<? super S>>
      Prepared prepare(
          MobRenderer<T, S, M> renderer, T animal, NativeGraphicPreparationContext context) {
    var state = renderer.createRenderState();
    renderer.extractRenderState(animal, state, 0);
    state.lightCoords = 15728880;
    var camera = new CameraRenderState();
    var bounds = new NativeModelGeometry(context.ledger().limits(), 0);
    var measure = new Measure(bounds);
    renderer.submit(state, new PoseStack(), measure, camera);
    if (!measure.getSubmitsPerOrder().isEmpty())
      throw new NativeGraphicUnavailable(
          "preview.entity_geometry",
          "This native renderer added geometry outside the curated model capability");
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

  /** Actual native model choice, pose and vertex compilation; no queued replacement mesh. */
  private static final class Measure extends SubmitNodeStorage {
    private final NativeModelGeometry bounds;

    Measure(NativeModelGeometry bounds) {
      this.bounds = bounds;
    }

    @Override
    public <S> void submitModel(
        Model<? super S> model,
        S state,
        PoseStack pose,
        RenderType type,
        int light,
        int overlay,
        int color,
        UvMapping uv,
        int outline) {
      model.setupAnim(state);
      model.renderToBuffer(pose, bounds, light, overlay, color);
    }
  }

  private static final class FreshParts extends EntityModelSet {
    private final EntityModelSet source;
    private final int maximum;
    private int baked;

    FreshParts(EntityModelSet source, int maximum) {
      super(Map.of());
      this.source = source;
      this.maximum = maximum;
    }

    @Override
    public ModelPart bakeLayer(ModelLayerLocation layer) {
      if (++baked > maximum)
        throw new PolicyRefusal(
            "preview.budget", "The animal exceeds its native model-layer allowance");
      return source.bakeLayer(layer);
    }
  }
}
