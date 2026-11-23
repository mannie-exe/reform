package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One shared orthographic vertex projection for both actual native adapters. */
public final class GraphicProjection {
  public static final int VERTEX_BYTES = 24;

  public record Draw(DocumentId material, int firstVertex, int vertexCount) {}

  public record Data(ByteBuffer vertices, List<Draw> draws) {
    public Data {
      var copy = ByteBuffer.allocate(vertices.remaining()).order(ByteOrder.nativeOrder());
      copy.put(vertices.duplicate()).flip();
      vertices = copy.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
      draws = List.copyOf(draws);
    }

    @Override
    public ByteBuffer vertices() {
      return vertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }
  }

  private record Face(GraphicMesh.Triangle triangle, double depth, boolean translucent) {}

  private record Point(double x, double y, double z) {}

  public static long vertexBytes(GraphicMesh mesh) {
    return (long) mesh.triangles().size() * 3 * VERTEX_BYTES;
  }

  public static Data project(
      GraphicScene scene, GraphicCamera camera, double aspect, GraphicLimits limits) {
    scene.requireBudget(limits);
    requireSupported(scene);
    if (!Double.isFinite(aspect) || aspect <= 0)
      throw new IllegalArgumentException("Invalid viewport aspect");
    long bytes = vertexBytes(scene.mesh());
    if (bytes > limits.outputBytes() || bytes > Integer.MAX_VALUE)
      throw new PolicyRefusal(
          "preview.budget", "The projected vertices exceed their output allowance");
    var mesh = scene.mesh();
    if (mesh.isEmpty()) return new Data(ByteBuffer.allocate(0), List.of());
    var points =
        mesh.vertices().stream()
            .map(vertex -> point(vertex, mesh.bounds(), camera, aspect))
            .toList();
    var faces = new ArrayList<Face>();
    for (var triangle : mesh.triangles())
      faces.add(
          new Face(
              triangle,
              (points.get(triangle.a()).z()
                      + points.get(triangle.b()).z()
                      + points.get(triangle.c()).z())
                  / 3,
              scene.materials().get(triangle.material()).alpha()
                  == GraphicMaterial.Alpha.TRANSLUCENT));
    // Opaque/cutout write depth first. Translucent faces keep a global far-to-near order, including
    // faces with different texture bindings; grouping by material first would change blending.
    faces.sort(
        Comparator.comparing(Face::translucent)
            .thenComparing((a, b) -> a.translucent() ? Double.compare(b.depth(), a.depth()) : 0));
    var output = ByteBuffer.allocate((int) bytes).order(ByteOrder.nativeOrder());
    var draws = new ArrayList<Draw>();
    int vertexIndex = 0;
    for (var face : faces) {
      var triangle = face.triangle();
      for (int index : new int[] {triangle.a(), triangle.b(), triangle.c()}) {
        var vertex = mesh.vertices().get(index);
        var point = points.get(index);
        output.putFloat(finite(point.x())).putFloat(finite(point.y())).putFloat(finite(point.z()));
        output.putFloat(vertex.u()).putFloat(vertex.v());
        float shade = shade(vertex, triangle.lighting());
        output.put((byte) Math.round(((vertex.argb() >>> 16) & 255) * shade));
        output.put((byte) Math.round(((vertex.argb() >>> 8) & 255) * shade));
        output.put((byte) Math.round((vertex.argb() & 255) * shade));
        output.put((byte) (vertex.argb() >>> 24));
      }
      // A material's alpha/depth/cull policy is constant; merge only adjacent equivalent draws.
      if (!draws.isEmpty() && draws.getLast().material().equals(triangle.material())) {
        var last = draws.removeLast();
        draws.add(new Draw(last.material(), last.firstVertex(), last.vertexCount() + 3));
      } else draws.add(new Draw(triangle.material(), vertexIndex, 3));
      vertexIndex += 3;
    }
    output.flip();
    return new Data(output, draws);
  }

  private static float finite(double value) {
    if (!Double.isFinite(value) || Math.abs(value) > Float.MAX_VALUE)
      throw new PolicyRefusal(
          "presentation.invalid_declaration",
          "The graphic camera produces a nonfinite native coordinate");
    return (float) value;
  }

  private static Point point(
      GraphicMesh.Vertex vertex, GraphicMesh.Bounds b, GraphicCamera camera, double aspect) {
    double extent =
        Math.max(b.maxX() - b.minX(), Math.max(b.maxY() - b.minY(), b.maxZ() - b.minZ()));
    if (extent == 0) extent = 1;
    double x = (vertex.x() - (b.minX() / 2 + b.maxX() / 2)) / extent;
    double y = (vertex.y() - (b.minY() / 2 + b.maxY() / 2)) / extent;
    double z = (vertex.z() - (b.minZ() / 2 + b.maxZ() / 2)) / extent;
    double yaw = Math.toRadians(camera.yaw() % 360), pitch = Math.toRadians(camera.pitch() % 360);
    double rx = x * Math.cos(yaw) + z * Math.sin(yaw), rz = -x * Math.sin(yaw) + z * Math.cos(yaw);
    double ry = y * Math.cos(pitch) - rz * Math.sin(pitch),
        depth = y * Math.sin(pitch) + rz * Math.cos(pitch);
    return new Point(
        rx * camera.zoom() / Math.max(1, aspect) + camera.panX(),
        ry * camera.zoom() * Math.min(1, aspect) + camera.panY(),
        -depth * 0.5);
  }

  private static float shade(GraphicMesh.Vertex vertex, GraphicLighting light) {
    if (light.shading() == GraphicLighting.Shading.UNLIT) return 1;
    double value;
    if (light.direction().isPresent())
      value =
          switch (light.direction().orElseThrow()) {
            case UP -> 1;
            case DOWN -> 0.5;
            case NORTH, SOUTH -> 0.8;
            case WEST, EAST -> 0.6;
          };
    else {
      double total =
          Math.abs((double) vertex.nx())
              + Math.abs((double) vertex.ny())
              + Math.abs((double) vertex.nz());
      if (total == 0)
        throw new PolicyRefusal(
            "preview.provider_unavailable",
            "A directionally shaded face needs a normal or explicit shade direction");
      value =
          (Math.abs(vertex.nx()) * 0.6
                  + Math.abs(vertex.nz()) * 0.8
                  + Math.abs(vertex.ny()) * (vertex.ny() < 0 ? 0.5 : 1))
              / total;
    }
    return (float) Math.max(value, light.lightEmission() / 15.0);
  }

  /** This studio projection's capabilities, separate from the scene's valid captured data. */
  public static void requireSupported(GraphicScene scene) {
    for (var triangle : scene.mesh().triangles()) {
      var light = triangle.lighting();
      if (light.ambientOcclusion())
        throw new PolicyRefusal(
            "preview.provider_unavailable",
            "This mesh renderer does not prepare world-dependent ambient occlusion");
      if (light.shading() == GraphicLighting.Shading.DIRECTIONAL && light.direction().isEmpty())
        for (int index : new int[] {triangle.a(), triangle.b(), triangle.c()}) {
          var vertex = scene.mesh().vertices().get(index);
          if (vertex.nx() == 0 && vertex.ny() == 0 && vertex.nz() == 0)
            throw new PolicyRefusal(
                "preview.provider_unavailable",
                "A directionally shaded face needs a normal or explicit shade direction");
        }
    }
  }

  private GraphicProjection() {}
}
