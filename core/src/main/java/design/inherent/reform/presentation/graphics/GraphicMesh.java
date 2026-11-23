package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Frozen local-space geometry; material IDs refer to explicit captured texture bindings. */
public final class GraphicMesh {
  /** UVs address a material's own texture, not a borrowed atlas. No normalization or clamping. */
  public record Vertex(
      float x, float y, float z, float u, float v, int argb, float nx, float ny, float nz) {
    public Vertex {
      if (!Float.isFinite(x)
          || !Float.isFinite(y)
          || !Float.isFinite(z)
          || !Float.isFinite(u)
          || !Float.isFinite(v)
          || !Float.isFinite(nx)
          || !Float.isFinite(ny)
          || !Float.isFinite(nz))
        throw new IllegalArgumentException("Mesh vertices need finite coordinates and normals");
    }
  }

  /** Triangle winding and normal are authored; submission must not silently reverse them. */
  public record Triangle(int a, int b, int c, DocumentId material, GraphicLighting lighting) {
    public Triangle(int a, int b, int c, DocumentId material) {
      this(a, b, c, material, GraphicLighting.DIRECTIONAL);
    }

    public Triangle {
      Objects.requireNonNull(material);
      Objects.requireNonNull(lighting);
      if (a < 0 || b < 0 || c < 0 || a == b || b == c || a == c)
        throw new IllegalArgumentException("A mesh triangle needs three distinct vertex indices");
    }
  }

  public record Bounds(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}

  private final List<Vertex> vertices;
  private final List<Triangle> triangles;
  private final Set<DocumentId> materials;
  private final Bounds bounds;
  private final long geometryBytes;

  public GraphicMesh(GraphicLimits limits, List<Vertex> vertices, List<Triangle> triangles) {
    Objects.requireNonNull(limits);
    Objects.requireNonNull(vertices);
    Objects.requireNonNull(triangles);
    // Check declared sizes before copying supplied lists or walking geometry.
    long minimumBytes = (long) vertices.size() * 36 + (long) triangles.size() * 16;
    if (minimumBytes > limits.outputBytes())
      throw new PolicyRefusal("preview.budget", "The mesh exceeds the declared output-byte budget");
    this.vertices = List.copyOf(vertices);
    this.triangles = List.copyOf(triangles);
    minimumBytes = (long) this.vertices.size() * 36 + (long) this.triangles.size() * 16;
    if (minimumBytes > limits.outputBytes())
      throw new PolicyRefusal("preview.budget", "The copied mesh exceeds its output-byte budget");
    var used = new HashSet<DocumentId>();
    long bytes = minimumBytes;
    for (var triangle : this.triangles) {
      if (triangle.a() >= this.vertices.size()
          || triangle.b() >= this.vertices.size()
          || triangle.c() >= this.vertices.size())
        throw new IllegalArgumentException("A triangle references a missing mesh vertex");
      if (used.add(triangle.material())) {
        if (used.size() > limits.resources())
          throw new PolicyRefusal(
              "preview.budget", "The mesh exceeds its material-resource budget");
        bytes += triangle.material().toString().length() * 2L;
        if (bytes > limits.outputBytes())
          throw new PolicyRefusal(
              "preview.budget", "Mesh material identities exceed its byte budget");
      }
    }
    materials = Set.copyOf(used);
    geometryBytes = bytes;
    // An intentionally invisible model is ready, not invalid or still loading. Its fit has no
    // extent; a viewport keeps its existing camera rather than dividing by this zero-size box.
    if (this.triangles.isEmpty()) {
      bounds = new Bounds(0, 0, 0, 0, 0, 0);
      return;
    }
    double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
    double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
    for (var vertex : this.vertices) {
      minX = Math.min(minX, vertex.x());
      minY = Math.min(minY, vertex.y());
      minZ = Math.min(minZ, vertex.z());
      maxX = Math.max(maxX, vertex.x());
      maxY = Math.max(maxY, vertex.y());
      maxZ = Math.max(maxZ, vertex.z());
    }
    bounds = new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
  }

  public List<Vertex> vertices() {
    return vertices;
  }

  /** No drawable faces. Native preparation must not invent geometry or allocate a draw for it. */
  public boolean isEmpty() {
    return triangles.isEmpty();
  }

  public List<Triangle> triangles() {
    return triangles;
  }

  public Set<DocumentId> materials() {
    return materials;
  }

  public Bounds bounds() {
    return bounds;
  }

  /** Canonical geometry/identity payload size, not a claim about VM or GPU allocation size. */
  public long geometryBytes() {
    return geometryBytes;
  }
}
