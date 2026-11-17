package design.inherent.reform.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class JsonInputTest {
  private static final JsonLimits LIMITS = new JsonLimits(4096, 8, 128, 128, 64, 1024);

  @Test
  void sourceIsRetainedAndExactDecodePreservesValues() throws Exception {
    String source = " { \"values\": [1.00, 2e1] } \n";
    var parsed = JsonInput.read(source, LIMITS);
    assertEquals(source, parsed.source());
    assertEquals(
        java.util.Map.of("values", java.util.List.of(1, 20)),
        parsed.decode(Codec.unboundedMap(Codec.STRING, ExactCodecs.INT.listOf())).getOrThrow());
    assertEquals(
        Double.doubleToRawLongBits(-0.0d),
        Double.doubleToRawLongBits(
            JsonInput.read("-0.0", LIMITS).decode(ExactCodecs.DOUBLE).getOrThrow()));
  }

  @Test
  void rejectsInformationLossBeforeBuildingDom() {
    rejects("json.duplicate_key", "{\"a\":1,\"a\":2}");
    rejects("json.duplicate_key", "{\"a\":1,\"\\u0061\":2}");
    rejects("json.trailing", "{} {}");
    rejects("json.syntax", "{\"a\":1,}");
    rejects("json.syntax", "{a:1}");
    rejects("json.syntax", "/*comment*/{}");
    rejects("json.syntax", "");
  }

  @Test
  void admitsEmptyCollectionsAndFalseWithoutInventingDefaults() throws Exception {
    assertEquals(
        java.util.List.of(),
        JsonInput.read("[]", LIMITS).decode(Codec.STRING.listOf()).getOrThrow());
    assertFalse(JsonInput.read("false", LIMITS).decode(Codec.BOOL).getOrThrow());
    assertTrue(JsonInput.read("null", LIMITS).decode(Codec.BOOL).isError());
  }

  @Test
  void failedPartialCodecCannotProducePartialSubmission() throws Exception {
    var result = JsonInput.read("[1,\"bad\",2]", LIMITS).decode(ExactCodecs.INT.listOf());
    assertTrue(result.isError());
    assertTrue(result.result().isEmpty());
    assertTrue(result.resultOrPartial().isEmpty());
  }

  @Test
  void enforceIndependentResourceBounds() {
    rejects("json.bytes", "\"" + "a".repeat(4096) + "\"");
    rejects("json.depth", "[".repeat(10) + "0" + "]".repeat(10));
    rejects("json.nodes", "[" + "0,".repeat(127) + "0]");
    rejects("json.string_length", "\"" + "a".repeat(129) + "\"");
    rejects("json.number_length", "1".repeat(65));
    rejects("json.number_scale", "1e99999");
    rejects("json.number_scale", "1e2147483648");
  }

  @Test
  void rejectsInvalidEncodingAndUnpairedSurrogates() {
    var invalidBytes =
        assertThrows(
            JsonInputException.class, () -> JsonInput.read(new byte[] {(byte) 0xc3, 0x28}, LIMITS));
    assertEquals("json.encoding", invalidBytes.code());
    rejects("json.encoding", "\"\ud800\"");
    rejects("json.unicode", "\"\\ud800\"");
    rejects("json.unicode", "{\"\\udc00\":1}");
  }

  @Test
  void byteBudgetIsUtf8RatherThanStringLength() {
    String source = "\"" + "é".repeat(10) + "\"";
    var budget = new JsonLimits(20, 4, 10, 32, 32, 32);
    assertEquals(22, source.getBytes(StandardCharsets.UTF_8).length);
    assertEquals(
        "json.bytes",
        assertThrows(JsonInputException.class, () -> JsonInput.read(source, budget)).code());
  }

  @Test
  void decoderCannotMutateAnotherReadOfSameParsedInput() throws Exception {
    var parsed = JsonInput.read("{\"a\":1}", LIMITS);
    var dynamic = parsed.decode(Codec.PASSTHROUGH).getOrThrow();
    ((com.google.gson.JsonObject) dynamic.getValue()).remove("a");
    assertEquals(
        java.util.Map.of("a", 1),
        parsed.decode(Codec.unboundedMap(Codec.STRING, ExactCodecs.INT)).getOrThrow());
  }

  private static void rejects(String code, String source) {
    var failure =
        assertThrows(JsonInputException.class, () -> JsonInput.read(source, LIMITS), source);
    assertEquals(code, failure.code(), source);
    assertNotNull(failure.location());
  }
}
