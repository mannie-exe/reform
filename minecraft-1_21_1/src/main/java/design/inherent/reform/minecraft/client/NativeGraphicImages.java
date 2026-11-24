package design.inherent.reform.minecraft.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;

final class NativeGraphicImages {
  static ClientGraphicImages.Frame frame(JsonObject metadata, int width, int height) {
    var animation = AnimationMetadataSection.SERIALIZER.fromJson(metadata);
    var dimensions = animation.calculateFrameSize(width, height);
    int[] first = {-1};
    boolean[] found = {false};
    animation.forEachFrame(
        (index, duration) -> {
          if (!found[0]) {
            first[0] = index;
            found[0] = true;
          }
          ClientGraphicImages.frame(width, height, dimensions.width(), dimensions.height(), index);
        });
    return ClientGraphicImages.frame(
        width, height, dimensions.width(), dimensions.height(), found[0] ? first[0] : 0);
  }

  static int argb(NativeImage image, int x, int y) {
    int abgr = image.getPixelRGBA(x, y);
    return (abgr & 0xff00ff00) | ((abgr & 0xff) << 16) | ((abgr >>> 16) & 0xff);
  }

  static int[] abgr(NativeImage image) {
    return image.getPixelsRGBA();
  }

  private NativeGraphicImages() {}
}
