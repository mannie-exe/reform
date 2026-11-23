package example.reform.platform;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientGraphicImages;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicTexture;
import design.inherent.reform.serialization.JsonLimits;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
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

  /** Runs on the real bounded worker; returns detached observations for client-thread reporting. */
  static Map<String, Boolean> batchWorker() {
    var observations = new LinkedHashMap<String, Boolean>();
    BiConsumer<String, Boolean> check = observations::put;
    check.accept(
        "batch preparation executes off the client thread",
        !net.minecraft.client.Minecraft.getInstance().isSameThread());
    try {
      var first = texture(png(true), "{\"animation\":{\"frames\":[1,0]}}");
      var second =
          new GraphicTexture(
              LIMITS,
              DocumentId.parse("fixture:other_frame"),
              png(false),
              Optional.of("{\"animation\":{}}".getBytes(StandardCharsets.UTF_8)));
      var inputs = List.of(first, second);
      var ledger = new GraphicLedger(new GraphicLimits(4096, 40, 1024, 4, 4096));
      var pixels = ClientGraphicImages.decodeStills(inputs, ledger, JSON, 8, () -> {});
      check.accept(
          "batch preserves first declared frames beside reserved geometry",
          pixels.size() == 2
              && pixels.get(ID).pixel(0, 0) == SECOND
              && pixels.get(second.id()).pixel(0, 0) == FIRST);
      check.accept(
          "batch closes native strip allocations before delivery",
          ledger.state().bytes() == 0
              && ledger.state().resources() == 0
              && ledger.state().peakBytes() == 32);
      try {
        pixels.clear();
        throw new AssertionError("Batch result was mutable");
      } catch (UnsupportedOperationException expected) {
        check.accept("batch result is immutable", true);
      }
      // Truncated but admissible PNG headers discriminate preflight from attempted decode.
      var header1 = texture(java.util.Arrays.copyOf(png(true), 33), null);
      var header2 =
          new GraphicTexture(
              LIMITS, second.id(), java.util.Arrays.copyOf(png(false), 33), Optional.empty());
      for (var budget :
          List.of(
              new GraphicLimits(65, 4096, 1024, 4, 4096),
              new GraphicLimits(4096, 4096, 15, 4, 4096))) {
        var bounded = new GraphicLedger(budget);
        refuses(
            check,
            "aggregate encoded/strip admission precedes decoding: " + budget,
            () ->
                ClientGraphicImages.decodeStills(
                    List.of(header1, header2), bounded, JSON, 0, () -> {}),
            "preview.budget");
        check.accept(
            "aggregate refusal allocates no native strip: " + budget,
            bounded.state().peakResources() == 0);
      }
      refuses(
          check,
          "batch accumulates frame output with geometry reservation",
          () -> ClientGraphicImages.decodeStills(inputs, ledger, JSON, 9, () -> {}),
          "preview.budget");
      check.accept("later frame refusal releases earlier strips", ledger.state().bytes() == 0);
      var occupied = new GraphicLedger(new GraphicLimits(4096, 4096, 1024, 4, 32));
      var old = occupied.reserve(1, 1);
      try {
        refuses(
            check,
            "batch includes native resources awaiting retirement",
            () -> ClientGraphicImages.decodeStills(inputs, occupied, JSON, 0, () -> {}),
            "preview.budget");
        check.accept("refusal preserves the existing owner", occupied.state().bytes() == 1);
      } finally {
        old.close();
      }
      check.accept(
          "batch retries after actual prior release",
          ClientGraphicImages.decodeStills(inputs, occupied, JSON, 0, () -> {}).size() == 2
              && occupied.state().bytes() == 0);
      var checkpoints = new AtomicInteger();
      try {
        ClientGraphicImages.decodeStills(
            inputs,
            ledger,
            JSON,
            0,
            () -> {
              if (checkpoints.incrementAndGet() == 4)
                throw new CancellationException("fixture checkpoint");
            });
        throw new AssertionError("Batch checkpoint did not cancel");
      } catch (CancellationException expected) {
        check.accept(
            "cancel checkpoint returns no partial batch and releases entered image",
            checkpoints.get() == 4 && ledger.state().bytes() == 0);
      }
      refuses(
          check,
          "invalid second PNG does not publish a partial result",
          () ->
              ClientGraphicImages.decodeStills(List.of(first, header2), ledger, JSON, 0, () -> {}),
          "preview.texture_invalid");
      check.accept(
          "invalid later image releases entered reservations", ledger.state().bytes() == 0);
      try {
        ClientGraphicImages.decodeStills(List.of(first, first), ledger, JSON, 0, () -> {});
        throw new AssertionError("Duplicate identity was accepted");
      } catch (IllegalArgumentException expected) {
        check.accept("batch refuses duplicate captured identity", ledger.state().bytes() == 0);
      }
      return java.util.Collections.unmodifiableMap(observations);
    } catch (Exception failed) {
      throw new java.util.concurrent.CompletionException(failed);
    }
  }

  private PlatformGraphicImageChecks() {}
}
