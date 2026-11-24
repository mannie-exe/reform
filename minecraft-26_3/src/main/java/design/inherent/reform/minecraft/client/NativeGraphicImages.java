package design.inherent.reform.minecraft.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;

final class NativeGraphicImages {
  static ClientGraphicImages.Frame frame(JsonObject metadata, int width, int height) {
    var decoded = AnimationMetadataSection.CODEC.parse(JsonOps.INSTANCE, metadata);
    if (decoded.error().isPresent())
      throw new IllegalArgumentException(
          "Native animation metadata decoding failed: " + decoded.error().orElseThrow().message());
    var animation = decoded.getOrThrow();
    var dimensions = animation.calculateFrameSize(width, height);
    var frames = animation.frames().orElse(java.util.List.of());
    for (var frame : frames)
      ClientGraphicImages.frame(
          width, height, dimensions.width(), dimensions.height(), frame.index());
    return ClientGraphicImages.frame(
        width,
        height,
        dimensions.width(),
        dimensions.height(),
        frames.isEmpty() ? 0 : frames.getFirst().index());
  }

  static int argb(NativeImage image, int x, int y) {
    return image.getPixel(x, y);
  }

  static int[] abgr(NativeImage image) {
    return image.getPixelsABGR();
  }

  private NativeGraphicImages() {}
}
