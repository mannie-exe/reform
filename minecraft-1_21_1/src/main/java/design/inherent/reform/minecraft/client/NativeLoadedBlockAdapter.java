package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Captured actual native quads/materials through the native block vertex pipeline. */
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
          "preview.block_entity", "This state needs an explicit block-entity preview provider");
    var client = context.client();
    var renderer = client.getBlockRenderer();
    var model = renderer.getBlockModel(state);
    if (model == client.getModelManager().getMissingModel())
      throw new NativeGraphicUnavailable(
          "preview.block_model", "The loaded block model is missing");
    var captured = new Quads(state, context);
    NativeBlockModels.capture(model, state, captured, context.ledger().limits().resources());
    if (captured.values.isEmpty()) return empty();
    var quads = List.copyOf(captured.values);
    var surface =
        NativeGuiModelGraphic.prepare(
            context,
            captured.bounds(),
            true,
            0,
            0,
            (graphics, pose) -> {
              for (var entry : quads) {
                int tint = entry.tint();
                graphics
                    .bufferSource()
                    .getBuffer(entry.layer())
                    .putBulkData(
                        pose.last(),
                        entry.quad(),
                        ((tint >>> 16) & 255) / 255f,
                        ((tint >>> 8) & 255) / 255f,
                        (tint & 255) / 255f,
                        1f,
                        15728880,
                        OverlayTexture.NO_OVERLAY);
              }
            });
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return surface.drawSurface(draw);
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }

  record Quad(RenderType layer, BakedQuad quad, int tint) {}

  static final class Quads {
    private final BlockState state;
    private final NativeGraphicPreparationContext context;
    final List<Quad> values = new ArrayList<>();
    private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

    Quads(BlockState state, NativeGraphicPreparationContext context) {
      this.state = state;
      this.context = context;
    }

    void add(RenderType layer, List<BakedQuad> quads) {
      if (quads.size() > context.ledger().limits().outputBytes() / QUAD_BYTES - values.size())
        throw budget();
      for (var quad : quads) {
        var vertices = quad.getVertices();
        int stride =
            com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
        if (vertices.length != 4 * stride)
          throw new NativeGraphicUnavailable(
              "preview.block_model", "The native block quad has an unsupported vertex layout");
        for (int i = 0; i < 4; i++) {
          float x = Float.intBitsToFloat(vertices[i * stride]),
              y = Float.intBitsToFloat(vertices[i * stride + 1]),
              z = Float.intBitsToFloat(vertices[i * stride + 2]);
          if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
            throw new NativeGraphicUnavailable(
                "preview.block_model", "The native block model has non-finite bounds");
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          minZ = Math.min(minZ, z);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
          maxZ = Math.max(maxZ, z);
        }
        int tint =
            quad.isTinted()
                ? context.client().getBlockColors().getColor(state, null, null, quad.getTintIndex())
                : -1;
        values.add(new Quad(layer, quad, tint));
      }
    }

    AABB bounds() {
      return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
  }

  private static PolicyRefusal budget() {
    return new PolicyRefusal("preview.budget", "The native block model exceeds its quad allowance");
  }

  private static Prepared empty() {
    return new Prepared() {
      public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
        return ClientMeshGraphic.DrawOutcome.EMPTY;
      }

      public void release(NativeGraphicReleaseContext release) {}
    };
  }
}
