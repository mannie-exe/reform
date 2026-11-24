package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import design.inherent.reform.minecraft.mixin.GraphicSpriteContentsAccessor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** One owned heap copy; the native atlas and image are borrowed only during client capture. */
record LoadedSpriteSource(
    String atlas,
    String sprite,
    int width,
    int height,
    int stripWidth,
    int stripHeight,
    int[] abgr) {
  static LoadedSpriteSource capture(
      TextureAtlasSprite sprite, ClientSpriteSubject subject, GraphicLimits limits) {
    var contents = sprite.contents();
    var image = ((GraphicSpriteContentsAccessor) contents).reform$originalImage();
    long area = (long) image.getWidth() * image.getHeight();
    if (area > Integer.MAX_VALUE
        || area > limits.decodedPixels()
        || area * Integer.BYTES > limits.inputBytes())
      throw new PolicyRefusal(
          "preview.budget",
          "The loaded sprite's complete source pixels exceed its capture allowance");
    if (image.format() != NativeImage.Format.RGBA)
      throw new PolicyRefusal(
          "preview.provider_unavailable", "This sprite's native pixel format is not supported");
    if (contents.width() < 1 || contents.height() < 1)
      throw new IllegalArgumentException("The loaded sprite has no positive frame extent");
    try {
      var frame =
          ClientGraphicImages.frame(
              image.getWidth(),
              image.getHeight(),
              contents.width(),
              contents.height(),
              subject.sourceFrame());
      if ((long) frame.width() * frame.height() * Integer.BYTES > limits.outputBytes())
        throw new PolicyRefusal("preview.budget", "The sprite frame exceeds its output allowance");
    } catch (IllegalArgumentException invalid) {
      var refusal =
          new PolicyRefusal(
              "preview.sprite_frame", "Choose a physical frame contained in the loaded sprite");
      refusal.initCause(invalid);
      throw refusal;
    }
    return new LoadedSpriteSource(
        sprite.atlasLocation().toString(),
        contents.name().toString(),
        contents.width(),
        contents.height(),
        image.getWidth(),
        image.getHeight(),
        NativeGraphicImages.abgr(image));
  }
}
