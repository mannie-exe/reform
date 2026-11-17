package design.inherent.reform.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

/** Pinned native controls: successful native coercion is not exact authored-value admission. */
class NativeCodecSemanticsTest {
  @Test
  void nativeIntegerDecoderTruncatesFractions() {
    assertEquals(1, Codec.INT.parse(JsonOps.INSTANCE, JsonParser.parseString("1.9")).getOrThrow());
  }

  @Test
  void nativeIntegerDecoderWrapsOverflow() {
    assertEquals(
        Integer.MIN_VALUE,
        Codec.INT.parse(JsonOps.INSTANCE, JsonParser.parseString("2147483648")).getOrThrow());
  }

  @Test
  void failedNativeListCanCarryPartialValues() {
    var result =
        Codec.INT.listOf().parse(JsonOps.INSTANCE, JsonParser.parseString("[1,\"bad\",2]"));
    assertTrue(result.isError());
    assertTrue(result.result().isEmpty());
    assertTrue(result.resultOrPartial().isPresent());
  }
}
