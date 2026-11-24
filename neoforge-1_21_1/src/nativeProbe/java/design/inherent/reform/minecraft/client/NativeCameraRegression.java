package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.presentation.graphics.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Actual native math classpath, no client, GPU, registry bootstrap, or copied rendering code. */
public final class NativeCameraRegression {
  public static void main(String[] args) {
    var limits = new GraphicLimits(4096, 4096, 16, 16, 65536);
    var id = DocumentId.parse("test:pixel");
    var lighting = new GraphicLighting(GraphicLighting.Shading.UNLIT, Optional.empty(), 0, false);
    var mesh =
        new GraphicMesh(
            limits,
            List.of(vertex(.2f, .3f, .4f), vertex(0, 0, 0), vertex(1, 1, 1)),
            List.of(new GraphicMesh.Triangle(0, 1, 2, id, lighting)));
    var scene =
        new GraphicScene(
            mesh,
            Map.of(
                id,
                new GraphicMaterial(
                    id,
                    GraphicMaterial.Alpha.OPAQUE,
                    0,
                    GraphicMaterial.Cull.NONE,
                    GraphicMaterial.Filter.NEAREST,
                    GraphicMaterial.Wrap.CLAMP)),
            Map.of(id, new GraphicPixels(limits, id, 1, 1, new int[] {-1})));
    for (var camera :
        List.of(
            new GraphicCamera(135, 30, 1, 0, 0),
            new GraphicCamera(47, -17, 1, 0, 0),
            new GraphicCamera(765, 390, 1, 0, 0))) {
      var expected = GraphicProjection.project(scene, camera, 1, limits).vertices();
      var pose = new PoseStack();
      NativeGraphicCamera.rotate(pose, camera, 0, 0);
      var actual = new Vector3f(.2f - .5f, .3f - .5f, .4f - .5f).mulPosition(pose.last().pose());
      close(expected.getFloat(), actual.x, "x " + camera);
      close(expected.getFloat(), actual.y, "y " + camera);
      close(-2 * expected.getFloat(), actual.z, "z " + camera);
    }
    var pose = new PoseStack();
    NativeGraphicCamera.rotate(pose, GraphicCamera.STANDARD, 0, 0);
    var eye = new Vector3f(0, 0, 1).mulDirection(new Matrix4f(pose.last().pose()).invert());
    if (!(eye.y > 0))
      throw new AssertionError("The default native block camera must be above its model: " + eye);
    var item = new PoseStack();
    NativeGraphicCamera.rotate(
        item, GraphicCamera.STANDARD, GraphicCamera.STANDARD.yaw(), GraphicCamera.STANDARD.pitch());
    if (!new Matrix4f().equals(item.last().pose()))
      throw new AssertionError("Authored GUI item origin changed");
    System.out.println(
        "NATIVE_CAMERA_REGRESSION PASS: core projection, positive pitch, authored item identity");
  }

  private static GraphicMesh.Vertex vertex(float x, float y, float z) {
    return new GraphicMesh.Vertex(x, y, z, 0, 0, -1, 0, 1, 0);
  }

  private static void close(float expected, float actual, String label) {
    if (Math.abs(expected - actual) > 1e-6)
      throw new AssertionError(label + ": expected " + expected + ", actual " + actual);
  }

  private NativeCameraRegression() {}
}
