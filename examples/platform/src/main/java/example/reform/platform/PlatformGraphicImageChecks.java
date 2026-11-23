package example.reform.platform;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientGraphicImages;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicTexture;
import design.inherent.reform.serialization.JsonLimits;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;

/** Real per-game PNG decode and animation metadata, without live resources or native uploads. */
final class PlatformGraphicImageChecks {
  private static final GraphicLimits LIMITS = new GraphicLimits(4096, 4096, 1024, 4, 4096);
  private static final JsonLimits JSON = new JsonLimits(4096, 16, 512, 256, 128, 1024);
  private static final DocumentId ID = DocumentId.parse("fixture:graphic_frame");
  private static final int FIRST = 0xffcc2211, SECOND = 0x7f1177dd;

  private static GraphicTexture texture(byte[] png, String metadata) {
    return new GraphicTexture(
        LIMITS,
        ID,
        png,
        metadata == null
            ? Optional.empty()
            : Optional.of(metadata.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] png(boolean horizontal) throws Exception {
    var image =
        new BufferedImage(horizontal ? 4 : 2, horizontal ? 2 : 4, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < image.getHeight(); y++)
      for (int x = 0; x < image.getWidth(); x++)
        image.setRGB(x, y, (horizontal ? x : y) < 2 ? FIRST : SECOND);
    var bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "png", bytes))
      throw new AssertionError("Fixture PNG encoder is absent");
    return bytes.toByteArray();
  }

  private static void refuses(
      BiConsumer<String, Boolean> check, String label, Runnable action, String code) {
    try {
      action.run();
      throw new AssertionError(label + " did not refuse");
    } catch (PolicyRefusal refused) {
      check.accept(
          label, refused.report().issues().stream().anyMatch(issue -> issue.code().equals(code)));
    }
  }

  static void run(BiConsumer<String, Boolean> check) throws Exception {
    for (boolean horizontal : new boolean[] {false, true}) {
      var png = png(horizontal);
      var second =
          ClientGraphicImages.decodeStill(
              texture(png, "{\"animation\":{\"frames\":[{\"index\":1,\"time\":4},0]}}"),
              LIMITS,
              JSON);
      check.accept(
          "native still chooses first declared frame with correct ARGB/alpha: " + horizontal,
          second.width() == 2
              && second.height() == 2
              && second.pixel(0, 0) == SECOND
              && second.pixel(1, 1) == SECOND);
      var first = ClientGraphicImages.decodeStill(texture(png, "{\"animation\":{}}"), LIMITS, JSON);
      check.accept(
          "native omitted frame dimensions use square minimum: " + horizontal,
          first.width() == 2 && first.height() == 2 && first.pixel(1, 1) == FIRST);
      var staticImage = ClientGraphicImages.decodeStill(texture(png, null), LIMITS, JSON);
      check.accept(
          "no animation metadata preserves complete rectangular image: " + horizontal,
          staticImage.width() == (horizontal ? 4 : 2)
              && staticImage.height() == (horizontal ? 2 : 4)
              && staticImage.pixel(staticImage.width() - 1, staticImage.height() - 1) == SECOND);
      refuses(
          check,
          "animation index outside native frame grid refuses",
          () ->
              ClientGraphicImages.decodeStill(
                  texture(png, "{\"animation\":{\"frames\":[2]}}"), LIMITS, JSON),
          "preview.texture_invalid");
      refuses(
          check,
          "native decode allocation budget refuses before strip allocation",
          () ->
              ClientGraphicImages.decodeStill(
                  texture(png, null), new GraphicLimits(4096, 4096, 1024, 4, 31), JSON),
          "preview.budget");
      refuses(
          check,
          "still output budget refuses before pixel allocation",
          () ->
              ClientGraphicImages.decodeStill(
                  texture(png, "{\"animation\":{}}"),
                  new GraphicLimits(4096, 15, 1024, 4, 4096),
                  JSON),
          "preview.budget");
    }
    var png = png(true);
    var row =
        ClientGraphicImages.decodeStill(
            texture(png, "{\"animation\":{\"height\":1,\"frames\":[1]}}"), LIMITS, JSON);
    check.accept(
        "explicit height keeps native full width",
        row.width() == 4
            && row.height() == 1
            && row.pixel(0, 0) == FIRST
            && row.pixel(3, 0) == SECOND);
    var column =
        ClientGraphicImages.decodeStill(
            texture(png, "{\"animation\":{\"width\":1,\"frames\":[2]}}"), LIMITS, JSON);
    check.accept(
        "explicit width keeps native full height",
        column.width() == 1 && column.height() == 2 && column.pixel(0, 1) == SECOND);
    refuses(
        check,
        "malformed captured metadata refuses",
        () -> ClientGraphicImages.decodeStill(texture(png, "{oops"), LIMITS, JSON),
        "preview.texture_invalid");
    refuses(
        check,
        "nondividing animation dimensions refuse",
        () ->
            ClientGraphicImages.decodeStill(
                texture(png, "{\"animation\":{\"width\":3}}"), LIMITS, JSON),
        "preview.texture_invalid");
    var damaged = java.util.Arrays.copyOf(png, 33);
    refuses(
        check,
        "admitted header alone does not pass actual PNG decoding",
        () -> ClientGraphicImages.decodeStill(texture(damaged, null), LIMITS, JSON),
        "preview.texture_invalid");
  }

  private PlatformGraphicImageChecks() {}
}
