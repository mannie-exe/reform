package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphicProjectionTest {
  private static final GraphicLimits LIMITS =
      new GraphicLimits(65536, 65536, 1024, 64, 1024 * 1024);
  private static final DocumentId FRONT = DocumentId.parse("fixture:front"),
      BACK = DocumentId.parse("fixture:back"),
      TEXTURE = DocumentId.parse("fixture:pixel");
  private static final GraphicLighting UNLIT =
      new GraphicLighting(GraphicLighting.Shading.UNLIT, Optional.empty(), 0, false);
  private static final GraphicCamera CAMERA = new GraphicCamera(0, 0, 1, 0, 0);

  private static GraphicMaterial material(GraphicMaterial.Alpha alpha) {
    return new GraphicMaterial(
        TEXTURE,
        alpha,
        0.1f,
        GraphicMaterial.Cull.NONE,
        GraphicMaterial.Filter.NEAREST,
        GraphicMaterial.Wrap.CLAMP);
  }

  private static GraphicMesh.Vertex v(float x, float y, float z, int color) {
    return new GraphicMesh.Vertex(x, y, z, 0.25f, 0.75f, color, 0, 0, 1);
  }

  private static GraphicScene layers(
      GraphicMaterial.Alpha front, GraphicMaterial.Alpha back, GraphicLighting light) {
    var mesh =
        new GraphicMesh(
            LIMITS,
            List.of(
                v(0, 0, 1, 0x80ff0000),
                v(1, 0, 1, 0x80ff0000),
                v(0, 1, 1, 0x80ff0000),
                v(0, 0, -1, 0x800000ff),
                v(1, 0, -1, 0x800000ff),
                v(0, 1, -1, 0x800000ff)),
            List.of(
                new GraphicMesh.Triangle(0, 1, 2, FRONT, light),
                new GraphicMesh.Triangle(3, 4, 5, BACK, light)));
    return new GraphicScene(
        mesh,
        Map.of(FRONT, material(front), BACK, material(back)),
        Map.of(TEXTURE, new GraphicPixels(LIMITS, TEXTURE, 1, 1, new int[] {0xffffffff})));
  }

  @Test
  void translucentFacesSortAcrossMaterialsAndCameraDepthMatchesVisibleFront() {
    var scene = layers(GraphicMaterial.Alpha.TRANSLUCENT, GraphicMaterial.Alpha.TRANSLUCENT, UNLIT);
    var result = GraphicProjection.project(scene, CAMERA, 1, LIMITS);
    assertEquals(
        List.of(BACK, FRONT),
        result.draws().stream().map(GraphicProjection.Draw::material).toList());
    var bytes = result.vertices();
    assertEquals(0.25f, bytes.getFloat(8));
    assertEquals(-0.25f, bytes.getFloat(3 * 24 + 8));
    assertEquals(0, Byte.toUnsignedInt(bytes.get(20)));
    assertEquals(255, Byte.toUnsignedInt(bytes.get(22)));
    assertEquals(128, Byte.toUnsignedInt(bytes.get(23)));
    assertEquals(0.25f, bytes.getFloat(12));
    assertEquals(0.75f, bytes.getFloat(16));
    var turned = GraphicProjection.project(scene, new GraphicCamera(180, 0, 1, 0, 0), 1, LIMITS);
    assertEquals(
        List.of(FRONT, BACK),
        turned.draws().stream().map(GraphicProjection.Draw::material).toList());
  }

  @Test
  void solidDepthPassPrecedesTransparentEvenWhenInputOrderDiffers() {
    var result =
        GraphicProjection.project(
            layers(GraphicMaterial.Alpha.TRANSLUCENT, GraphicMaterial.Alpha.OPAQUE, UNLIT),
            CAMERA,
            1,
            LIMITS);
    assertEquals(BACK, result.draws().getFirst().material());
    var shade =
        new GraphicLighting(
            GraphicLighting.Shading.DIRECTIONAL,
            Optional.of(GraphicLighting.Direction.DOWN),
            0,
            false);
    var dark =
        GraphicProjection.project(
                layers(GraphicMaterial.Alpha.OPAQUE, GraphicMaterial.Alpha.OPAQUE, shade),
                CAMERA,
                1,
                LIMITS)
            .vertices();
    assertEquals(128, Byte.toUnsignedInt(dark.get(20)));
    assertEquals(128, Byte.toUnsignedInt(dark.get(23)));
    var emitting =
        new GraphicLighting(
            GraphicLighting.Shading.DIRECTIONAL,
            Optional.of(GraphicLighting.Direction.DOWN),
            15,
            false);
    assertEquals(
        255,
        Byte.toUnsignedInt(
            GraphicProjection.project(
                    layers(GraphicMaterial.Alpha.OPAQUE, GraphicMaterial.Alpha.OPAQUE, emitting),
                    CAMERA,
                    1,
                    LIMITS)
                .vertices()
                .get(20)));
  }

  @Test
  void emptyAndDegenerateFitRemainFiniteAndProjectionDataIsFrozen() {
    var empty = new GraphicScene(new GraphicMesh(LIMITS, List.of(), List.of()), Map.of(), Map.of());
    assertEquals(
        0,
        GraphicProjection.project(empty, GraphicCamera.STANDARD, 1, LIMITS).vertices().remaining());
    var source = ByteBuffer.allocate(4).order(java.nio.ByteOrder.nativeOrder()).putInt(123);
    source.flip();
    var frozen = new GraphicProjection.Data(source, List.of());
    source.putInt(0, 456);
    assertEquals(123, frozen.vertices().getInt(0));
    assertThrows(ReadOnlyBufferException.class, () -> frozen.vertices().put((byte) 0));
    var mesh =
        new GraphicMesh(
            LIMITS,
            List.of(v(0, 0, 0, -1), v(Float.MIN_VALUE, 0, 0, -1), v(0, Float.MIN_VALUE, 0, -1)),
            List.of(new GraphicMesh.Triangle(0, 1, 2, FRONT, UNLIT)));
    var scene =
        new GraphicScene(
            mesh,
            Map.of(FRONT, material(GraphicMaterial.Alpha.OPAQUE)),
            Map.of(TEXTURE, new GraphicPixels(LIMITS, TEXTURE, 1, 1, new int[] {-1})));
    var vertices = GraphicProjection.project(scene, CAMERA, 1, LIMITS).vertices();
    for (int i = 0; i < 3; i++)
      for (int axis = 0; axis < 3; axis++)
        assertTrue(Float.isFinite(vertices.getFloat(i * 24 + axis * 4)));
    assertThrows(IllegalArgumentException.class, () -> new GraphicCamera(0, 0, 0, 0, 0));
    assertThrows(
        PolicyRefusal.class,
        () ->
            GraphicProjection.project(
                scene, new GraphicCamera(0, 0, Double.MAX_VALUE, 0, 0), 1, LIMITS));
  }

  @Test
  void unsupportedLightingAndExpandedOutputBudgetRefuseRatherThanApproximate() {
    var ao = new GraphicLighting(GraphicLighting.Shading.DIRECTIONAL, Optional.empty(), 0, true);
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () ->
                GraphicProjection.project(
                    layers(GraphicMaterial.Alpha.OPAQUE, GraphicMaterial.Alpha.OPAQUE, ao),
                    CAMERA,
                    1,
                    LIMITS));
    assertEquals("preview.provider_unavailable", refused.report().issues().getFirst().code());
    var triangle = new GraphicMesh.Triangle(0, 1, 2, FRONT, UNLIT);
    var mesh =
        new GraphicMesh(
            LIMITS,
            List.of(v(0, 0, 0, -1), v(1, 0, 0, -1), v(0, 1, 0, -1)),
            java.util.Collections.nCopies(100, triangle));
    var scene =
        new GraphicScene(
            mesh,
            Map.of(FRONT, material(GraphicMaterial.Alpha.OPAQUE)),
            Map.of(TEXTURE, new GraphicPixels(LIMITS, TEXTURE, 1, 1, new int[] {-1})));
    assertThrows(
        PolicyRefusal.class,
        () ->
            GraphicProjection.project(
                scene, CAMERA, 1, new GraphicLimits(65536, 3000, 1024, 64, 1024 * 1024)));
    assertThrows(IllegalArgumentException.class, () -> new GraphicScene(mesh, Map.of(), Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphicScene(
                mesh, Map.of(FRONT, material(GraphicMaterial.Alpha.OPAQUE)), Map.of()));
  }
}
