package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import net.minecraft.world.phys.AABB;

/** Counts actual native emitted vertices for admission and fit; retains no replacement mesh. */
final class NativeModelGeometry implements VertexConsumer {
  private final long maximumVertices;
  private long vertices;
  private double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
  private double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;

  NativeModelGeometry(GraphicLimits limits, int requiredLayers) {
    // Canonical position/normal/UV size, not native object or allocator memory.
    maximumVertices = limits.outputBytes() / (8 * Float.BYTES);
    if (limits.resources() < requiredLayers)
      throw new PolicyRefusal(
          "preview.budget", "The reference exceeds its native model-layer allowance");
  }

  public VertexConsumer addVertex(float x, float y, float z) {
    if (++vertices > maximumVertices)
      throw new PolicyRefusal(
          "preview.budget", "The reference exceeds its native vertex allowance");
    if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
      throw new NativeGraphicUnavailable(
          "preview.model_geometry", "The native reference has non-finite geometry");
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    minZ = Math.min(minZ, z);
    maxX = Math.max(maxX, x);
    maxY = Math.max(maxY, y);
    maxZ = Math.max(maxZ, z);
    return this;
  }

  AABB bounds() {
    if (vertices == 0)
      throw new NativeGraphicUnavailable(
          "preview.model_geometry", "The native reference model is empty");
    return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
  }

  public VertexConsumer setColor(int r, int g, int b, int a) {
    return this;
  }

  public VertexConsumer setColor(int color) {
    return this;
  }

  public VertexConsumer setUv(float u, float v) {
    return this;
  }

  public VertexConsumer setUv1(int u, int v) {
    return this;
  }

  public VertexConsumer setUv2(int u, int v) {
    return this;
  }

  public VertexConsumer setUv3(float u, float v) {
    return this;
  }

  public VertexConsumer setNormal(float x, float y, float z) {
    return this;
  }

  public VertexConsumer setLineWidth(float width) {
    return this;
  }
}
