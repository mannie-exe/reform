package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicTexture;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;

class GraphicPngTest {
  private static final GraphicLimits LIMITS = new GraphicLimits(4096, 4096, 1024, 4, 4096);
  private static final DocumentId ID = DocumentId.parse("fixture:png");

  private static GraphicTexture texture(byte[] png) {
    return new GraphicTexture(LIMITS, ID, png, Optional.empty());
  }

  private static byte[] chunk(String type, byte[] content) throws Exception {
    var output = new ByteArrayOutputStream();
    var data = new DataOutputStream(output);
    data.writeInt(content.length);
    byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    data.write(name);
    data.write(content);
    var crc = new CRC32();
    crc.update(name);
    crc.update(content);
    data.writeInt((int) crc.getValue());
    return output.toByteArray();
  }

  private static byte[] png() throws Exception {
    var output = new ByteArrayOutputStream();
    output.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    output.write(
        chunk(
            "IHDR",
            ByteBuffer.allocate(13).putInt(1).putInt(1).put(new byte[] {8, 6, 0, 0, 0}).array()));
    var compressed = new ByteArrayOutputStream();
    try (var deflater = new DeflaterOutputStream(compressed)) {
      deflater.write(new byte[] {0, 1, 2, 3, 4});
    }
    output.write(chunk("IDAT", compressed.toByteArray()));
    output.write(chunk("IEND", new byte[0]));
    return output.toByteArray();
  }

  @Test
  void completeContainerPassesWhileHeaderOnlyAndMissingEndRefuse() throws Exception {
    var png = png();
    assertDoesNotThrow(() -> texture(png).validatePng());
    var headerOnly = texture(Arrays.copyOf(png, 33));
    assertEquals(1, headerOnly.encodedWidth());
    var refused = assertThrows(PolicyRefusal.class, headerOnly::validatePng);
    assertEquals("preview.texture_invalid", refused.report().issues().getFirst().code());
    assertThrows(
        PolicyRefusal.class, () -> texture(Arrays.copyOf(png, png.length - 1)).validatePng());
  }

  @Test
  void corruptChunksDuplicateDimensionsAndTrailingBytesRefuse() throws Exception {
    var png = png();
    var damaged = png.clone();
    damaged[42] ^= 1;
    assertThrows(PolicyRefusal.class, () -> texture(damaged).validatePng());
    var oversized = png.clone();
    ByteBuffer.wrap(oversized).putInt(33, Integer.MAX_VALUE);
    assertThrows(PolicyRefusal.class, () -> texture(oversized).validatePng());
    var duplicate = new ByteArrayOutputStream();
    duplicate.write(png, 0, 33);
    duplicate.write(png, 8, png.length - 8);
    assertThrows(PolicyRefusal.class, () -> texture(duplicate.toByteArray()).validatePng());
    assertThrows(
        PolicyRefusal.class, () -> texture(Arrays.copyOf(png, png.length + 1)).validatePng());
    var noData = new ByteArrayOutputStream();
    noData.write(png, 0, 33);
    noData.write(chunk("IEND", new byte[0]));
    assertThrows(PolicyRefusal.class, () -> texture(noData.toByteArray()).validatePng());
  }
}
