package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.presentation.graphics.GraphicInputBasis;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class GraphicInputBasisTest {
  @Test
  void streamedUtf8LengthAndDigestMatchTheActualJdkIncludingMalformedSurrogates() throws Exception {
    var random = new java.util.Random(47);
    for (int n = 0; n < 100; n++) {
      var text = new StringBuilder("ASCII © Ā 中 😀\ud800x\udfff\udbff\udfff");
      for (int i = 0; i < n * 7; i++) text.append((char) random.nextInt(65536));
      var bytes = text.toString().getBytes(StandardCharsets.UTF_8);
      var content = GraphicInputBasis.Content.of(text.toString());
      assertEquals(bytes.length, content.bytes());
      assertEquals(
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          content.sha256());
    }
    assertThrows(
        IllegalArgumentException.class, () -> new GraphicInputBasis.Content(-1, "0".repeat(64)));
    assertThrows(
        IllegalArgumentException.class, () -> new GraphicInputBasis.Content(0, "A".repeat(64)));
  }
}
