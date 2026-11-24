package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueType;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.CowRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.PigRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;

/** Curated detached animals with fresh renderers; never inserts or ticks an entity. */
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
            client.getItemRenderer(),
            client.getBlockRenderer(),
            client.gameRenderer.itemInHandRenderer,
            client.getResourceManager(),
            parts,
            client.font);
    return switch (input.kind()) {
      case COW -> {
        var animal = new Cow(EntityType.COW, client.level);
        animal.setBaby(input.baby());
        yield prepare(new CowRenderer(factory), animal, context);
      }
      case PIG -> {
        var animal = new Pig(EntityType.PIG, client.level);
        animal.setBaby(input.baby());
        yield prepare(new PigRenderer(factory), animal, context);
      }
    };
  }

  private static <T extends Mob, M extends EntityModel<T>> Prepared prepare(
      MobRenderer<T, M> renderer, T animal, NativeGraphicPreparationContext context) {
    var bounds = new NativeModelGeometry(context.ledger().limits(), 0);
    renderer.render(animal, 0, 0, new PoseStack(), layer -> bounds, 15728880);
    var surface =
        NativeGuiModelGraphic.prepare(
            context,
            bounds.bounds(),
            true,
            0,
            0,
            (graphics, pose) ->
                renderer.render(animal, 0, 0, pose, graphics.bufferSource(), 15728880));
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw);
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }

  private static final class FreshParts extends EntityModelSet {
    private final EntityModelSet source;
    private final int maximum;
    private int baked;

    FreshParts(EntityModelSet source, int maximum) {
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
