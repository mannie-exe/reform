package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class ClientChestSubjectTest {
  @Test
  void typedPoseRoundTripsWithoutNativeRegistryOrWorld() {
    for (var kind : ClientChestSubject.Kind.values())
      for (float progress : new float[] {0, 0.5f, 1}) {
        var value = new ClientChestSubject(kind, progress);
        var type = ClientChestSubject.type();
        var encoded = type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
        assertEquals(value, type.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertSame(value, type.freeze(value));
        assertEquals(2, encoded.getAsJsonObject().size());
      }
  }

  @Test
  void unsupportedKindsAndUnboundedPosesRefuse() {
    for (float progress :
        new float[] {-1, 2, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class,
          () -> new ClientChestSubject(ClientChestSubject.Kind.NORMAL, progress));
    for (String value :
        new String[] {
          "{\"kind\":\"unknown\",\"lidProgress\":0}",
          "{\"kind\":\"normal\",\"lidProgress\":-0.1}",
          "{\"kind\":\"normal\",\"lidProgress\":1.1}",
          "{\"kind\":\"trapped\"}"
        })
      assertTrue(
          ClientChestSubject.type()
              .codec()
              .parse(JsonOps.INSTANCE, JsonParser.parseString(value))
              .error()
              .isPresent(),
          value);
  }
}
