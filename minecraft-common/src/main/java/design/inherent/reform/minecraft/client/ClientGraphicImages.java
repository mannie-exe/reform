package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicPixels;
import design.inherent.reform.presentation.graphics.GraphicTexture;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit CPU preparation; no live resource manager, texture publication, or GPU allocation. */
public final class ClientGraphicImages {
  /**
   * Worker-safe batch preparation with aggregate input/strip/output admission. Use the workflow's
   * shared ledger so temporary decoding also accounts for native graphics still awaiting
   * retirement. The checkpoint belongs to the actual operation; this method creates no executor or
   * job owner.
   */
  public static Map<DocumentId, GraphicPixels> decodeStills(
      List<GraphicTexture> textures,
      GraphicLedger ledger,
      JsonLimits metadataLimits,
      long reservedOutputBytes,
      Runnable checkpoint) {
    Objects.requireNonNull(textures);
    Objects.requireNonNull(ledger);
    Objects.requireNonNull(metadataLimits);
    Objects.requireNonNull(checkpoint);
    var limits = ledger.limits();
    if (reservedOutputBytes < 0)
      throw new IllegalArgumentException("Negative reserved geometry output");
    if (reservedOutputBytes > limits.outputBytes() || textures.size() > limits.resources())
      throw new PolicyRefusal(
          "preview.budget", "The graphic input exceeds its output or resource allowance");
    var captured = List.copyOf(textures);
    if (captured.size() > limits.resources())
      throw new PolicyRefusal("preview.budget", "The captured texture count exceeds its allowance");
    long encoded = 0, strips = 0;
    var ids = new HashSet<DocumentId>();
    for (var texture : captured) {
      checkpoint.run();
      if (!ids.add(texture.id()))
        throw new IllegalArgumentException("Duplicate captured texture identity");
      long area = (long) texture.encodedWidth() * texture.encodedHeight();
      if (texture.encodedBytes() > limits.inputBytes() - encoded
          || area > limits.decodedPixels() - strips
          || area > limits.nativeBytes() / 4)
        throw new PolicyRefusal(
            "preview.budget",
            "The complete texture input exceeds its encoded, decoded-strip, or native allowance");
      encoded += texture.encodedBytes();
      strips += area;
    }
    var result = new LinkedHashMap<DocumentId, GraphicPixels>();
    long remaining = limits.outputBytes() - reservedOutputBytes;
    for (var texture : captured) {
      checkpoint.run();
      if (remaining < 4)
        throw new PolicyRefusal(
            "preview.budget", "The decoded frames exceed the remaining geometry/output allowance");
      var temporary =
          ledger.reserve((long) texture.encodedWidth() * texture.encodedHeight() * 4, 1);
      GraphicPixels pixels;
      try {
        pixels =
            decodeStill(
                texture,
                new GraphicLimits(
                    limits.inputBytes(),
                    remaining,
                    limits.decodedPixels(),
                    limits.resources(),
                    limits.nativeBytes()),
                metadataLimits);
      } finally {
        temporary.close();
      }
      checkpoint.run();
      result.put(texture.id(), pixels);
      remaining -= pixels.bytes();
    }
    checkpoint.run();
    return java.util.Collections.unmodifiableMap(result);
  }

  /**
   * Cropping uses native animation dimensions and its first declared frame, not the whole strip.
   */
  public static GraphicPixels decodeStill(
      GraphicTexture texture, GraphicLimits limits, JsonLimits metadataLimits) {
    Objects.requireNonNull(texture);
    Objects.requireNonNull(limits);
    Objects.requireNonNull(metadataLimits);
    long stripPixels = (long) texture.encodedWidth() * texture.encodedHeight();
    if (texture.encodedBytes() > limits.inputBytes()
        || stripPixels > limits.decodedPixels()
        || stripPixels > limits.nativeBytes() / 4)
      throw new PolicyRefusal(
          "preview.budget",
          "The PNG exceeds the preparation input, strip-pixel, or native decode budget");
    texture.validatePng();
    Frame frame = new Frame(0, 0, texture.encodedWidth(), texture.encodedHeight());
    if (texture.metadata().isPresent()) {
      try {
        var metadata = JsonInput.read(texture.metadata().orElseThrow(), metadataLimits).copyTree();
        if (!metadata.isJsonObject())
          throw new IllegalArgumentException("Texture metadata must be an object");
        if (metadata.getAsJsonObject().has("animation")) {
          var animation = metadata.getAsJsonObject().get("animation");
          if (!animation.isJsonObject())
            throw new IllegalArgumentException("Animation metadata must be an object");
          frame =
              NativeGraphicImages.frame(
                  animation.getAsJsonObject(), texture.encodedWidth(), texture.encodedHeight());
        }
      } catch (JsonInputException | RuntimeException failed) {
        if (failed instanceof PolicyRefusal refused) throw refused;
        throw invalid("The captured texture animation metadata is invalid", failed);
      }
    }
    long pixels = (long) frame.width() * frame.height();
    if (pixels > Integer.MAX_VALUE || pixels * Integer.BYTES > limits.outputBytes())
      throw new PolicyRefusal("preview.budget", "The still frame exceeds its output-byte budget");
    try (var image = NativeImage.read(new ByteArrayInputStream(texture.png()))) {
      if (image.getWidth() != texture.encodedWidth()
          || image.getHeight() != texture.encodedHeight())
        throw new IllegalArgumentException("The native PNG decoder returned a different extent");
      var argb = new int[(int) pixels];
      for (int y = 0; y < frame.height(); y++)
        for (int x = 0; x < frame.width(); x++)
          argb[y * frame.width() + x] =
              NativeGraphicImages.argb(image, frame.x() + x, frame.y() + y);
      return new GraphicPixels(limits, texture.id(), frame.width(), frame.height(), argb);
    } catch (IOException | IllegalArgumentException failed) {
      throw invalid("The captured PNG could not be decoded", failed);
    }
  }

  static Frame frame(int imageWidth, int imageHeight, int width, int height, int first) {
    if (width < 1 || height < 1 || imageWidth % width != 0 || imageHeight % height != 0)
      throw new IllegalArgumentException("Animation frame dimensions do not divide the image");
    long columns = imageWidth / width, count = columns * (imageHeight / height);
    if (first < 0 || first >= count)
      throw new IllegalArgumentException("The first declared animation frame is outside the image");
    return new Frame(
        (int) (first % columns) * width, (int) (first / columns) * height, width, height);
  }

  private static PolicyRefusal invalid(String message, Exception cause) {
    var refusal = new PolicyRefusal("preview.texture_invalid", message);
    refusal.initCause(cause);
    return refusal;
  }

  record Frame(int x, int y, int width, int height) {}

  private ClientGraphicImages() {}
}
