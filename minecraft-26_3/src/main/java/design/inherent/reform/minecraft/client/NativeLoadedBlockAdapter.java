package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueType;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.AABB;

/** Native static state parts; block entities and world simulation are separate capabilities. */
final class NativeLoadedBlockAdapter implements NativeGraphicAdapter<ClientBlockSubject> {
  // Canonical position/normal/UV geometry, not an allocation or driver-memory estimate.
  private static final int QUAD_BYTES = 4 * 8 * Float.BYTES;
  static final NativeLoadedBlockAdapter INSTANCE = new NativeLoadedBlockAdapter();

  public DocumentId id() {
    return DocumentId.parse("reform:loaded_block");
  }

  public ValueType<ClientBlockSubject> inputType() {
    return ClientBlockSubject.type();
  }

  public Prepared prepare(ClientBlockSubject input, NativeGraphicPreparationContext context) {
    var state = input.decode();
    if (state.getRenderShape() == RenderShape.INVISIBLE) return empty();
    if (state.getRenderShape() != RenderShape.MODEL)
      throw new NativeGraphicUnavailable(
          "preview.block_model", "This state has no supported static block model");
    var models = context.client().getModelManager().getBlockStateModelSet();
    var model = models.get(state);
    if (model == models.missingModel())
      throw new NativeGraphicUnavailable(
          "preview.block_model", "The loaded block model is missing");
    var limits = context.ledger().limits();
    var collected = new Parts(limits.resources());
    NativeBlockModels.collect(model, state, RandomSource.create(42), collected);
    var parts = List.copyOf(collected);
    var tints = context.client().getBlockColors().getTintSources(state);
    if (tints.size() > limits.resources()) throw budget();
    int[] colors = new int[tints.size()];
    for (int i = 0; i < colors.length; i++) colors[i] = tints.get(i).color(state);
    double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    long quads = 0;
    boolean translucent = false;
    for (var part : parts) {
      translucent |= (part.materialFlags() & BakedQuad.FLAG_TRANSLUCENT) != 0;
      for (int face = 0; face <= 6; face++) {
        var faces = part.getQuads(face == 6 ? null : Direction.values()[face]);
        if (faces.size() > limits.outputBytes() / QUAD_BYTES - quads) throw budget();
        quads += faces.size();
        for (var quad : faces)
          for (int vertex = 0; vertex < 4; vertex++) {
            var p = quad.position(vertex);
            if (!Float.isFinite(p.x()) || !Float.isFinite(p.y()) || !Float.isFinite(p.z()))
              throw new NativeGraphicUnavailable(
                  "preview.block_model", "The native block model has non-finite bounds");
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
          }
      }
    }
    if (quads == 0) return empty();
    boolean alpha = translucent;
    var surface =
        NativeFeatureGraphic.prepare(
            context,
            new AABB(minX, minY, minZ, maxX, maxY, maxZ),
            true,
            0,
            0,
            (pose, submits) -> NativeBlockModels.submit(pose, submits, parts, colors, alpha));
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw, context::isCurrent);
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }

  private static Prepared empty() {
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return ClientMeshGraphic.DrawOutcome.EMPTY;
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }

  private static PolicyRefusal budget() {
    return new PolicyRefusal(
        "preview.budget", "The native block model exceeds its part, tint, or quad allowance");
  }

  /** Bound native model collection before a foreign implementation can grow the result list. */
  private static final class Parts extends AbstractList<BlockStateModelPart> {
    private final int maximum;
    private final ArrayList<BlockStateModelPart> values = new ArrayList<>();

    Parts(int maximum) {
      this.maximum = maximum;
    }

    public BlockStateModelPart get(int index) {
      return values.get(index);
    }

    public int size() {
      return values.size();
    }

    public void add(int index, BlockStateModelPart value) {
      if (size() >= maximum) throw budget();
      values.add(index, value);
    }
  }
}
