package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicMesh;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphicMeshTest {
  private static final DocumentId MATERIAL = DocumentId.parse("fixture:barrel");
  private static final GraphicLimits LIMITS = new GraphicLimits(4096, 4096, 1024, 4, 8192);

  private static GraphicMesh.Vertex vertex(float x, float y, float z, float u, float v) {
    return new GraphicMesh.Vertex(x, y, z, u, v, 0xffffffff, 0, 0, 1);
  }

  private static List<GraphicMesh.Vertex> vertices() {
    return List.of(vertex(0, 0, 0, 0, 0), vertex(1, 0, 0, 1, 0), vertex(0, 1, 0, 0, 1));
  }

  @Test
  void meshFreezesGeometryAndPreservesCoordinatesUvWindingAndMaterialIdentity() {
    var vertices = new ArrayList<>(vertices());
    vertices.set(0, vertex(-2, 0, 0, -0.25f, 1.25f));
    var triangle = new GraphicMesh.Triangle(2, 1, 0, MATERIAL);
    var mesh = new GraphicMesh(LIMITS, vertices, List.of(triangle));
    vertices.clear();
    assertEquals(3, mesh.vertices().size());
    assertEquals(-0.25f, mesh.vertices().getFirst().u());
    assertEquals(1.25f, mesh.vertices().getFirst().v());
    assertEquals(triangle, mesh.triangles().getFirst());
    assertEquals(java.util.Set.of(MATERIAL), mesh.materials());
    assertEquals(-2, mesh.bounds().minX());
    assertEquals(1, mesh.bounds().maxY());
    assertThrows(UnsupportedOperationException.class, () -> mesh.vertices().clear());
    assertThrows(UnsupportedOperationException.class, () -> mesh.triangles().clear());
    assertThrows(UnsupportedOperationException.class, () -> mesh.materials().clear());
  }

  @Test
  void budgetRefusesBeforeWalkingOversizedSuppliedGeometry() {
    var unwalkable =
        new AbstractList<GraphicMesh.Vertex>() {
          @Override
          public int size() {
            return Integer.MAX_VALUE;
          }

          @Override
          public GraphicMesh.Vertex get(int index) {
            fail("The declared budget must refuse before geometry is copied");
            return null;
          }
        };
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () ->
                new GraphicMesh(
                    LIMITS, unwalkable, List.of(new GraphicMesh.Triangle(0, 1, 2, MATERIAL))));
    assertEquals("preview.budget", refused.report().issues().getFirst().code());
  }

  @Test
  void invalidGeometryAndMissingVerticesRefuseWithoutApproximation() {
    assertThrows(IllegalArgumentException.class, () -> vertex(Float.NaN, 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> vertex(0, 0, 0, Float.POSITIVE_INFINITY, 0));
    assertThrows(IllegalArgumentException.class, () -> new GraphicMesh.Triangle(0, 0, 1, MATERIAL));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphicMesh(
                LIMITS, vertices(), List.of(new GraphicMesh.Triangle(0, 1, 3, MATERIAL))));
    assertThrows(IllegalArgumentException.class, () -> new GraphicLimits(1, 1, 0, 1, 1));
  }

  @Test
  void materialAccountingAndBoundsRemainFiniteAtAcceptedExtremes() {
    var oneMaterial = new GraphicLimits(4096, 4096, 1024, 1, 8192);
    assertThrows(
        PolicyRefusal.class,
        () ->
            new GraphicMesh(
                oneMaterial,
                vertices(),
                List.of(
                    new GraphicMesh.Triangle(0, 1, 2, MATERIAL),
                    new GraphicMesh.Triangle(2, 1, 0, DocumentId.parse("fixture:other")))));
    var mesh =
        new GraphicMesh(
            LIMITS,
            List.of(
                vertex(-Float.MAX_VALUE, 0, 0, 0, 0),
                vertex(Float.MAX_VALUE, 0, 0, 1, 0),
                vertex(0, 1, 0, 0, 1)),
            List.of(new GraphicMesh.Triangle(0, 1, 2, MATERIAL)));
    assertTrue(Double.isFinite(mesh.bounds().maxX() - mesh.bounds().minX()));
    assertTrue(mesh.geometryBytes() <= LIMITS.outputBytes());
  }
}
