package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import design.inherent.reform.presentation.graphics.GraphicCamera;

/** Native rotations share the orthographic camera convention used by projected mesh graphics. */
final class NativeGraphicCamera {
  static void rotate(PoseStack pose, GraphicCamera camera, double yawOrigin, double pitchOrigin) {
    pose.mulPose(
        Axis.XP.rotationDegrees((float) Math.IEEEremainder(camera.pitch() - pitchOrigin, 360)));
    pose.mulPose(
        Axis.YP.rotationDegrees((float) Math.IEEEremainder(camera.yaw() - yawOrigin, 360)));
  }

  private NativeGraphicCamera() {}
}
