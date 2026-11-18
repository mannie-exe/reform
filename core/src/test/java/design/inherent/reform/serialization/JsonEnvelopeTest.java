package design.inherent.reform.serialization;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class JsonEnvelopeTest {
  private static final JsonLimits LIMITS = new JsonLimits(8192, 32, 512, 1024, 128, 4096);

  @Test
  void selectsValuesWithoutChangingSourceSpellingOrNullMembership() throws Exception {
    var input = JsonInput.read("{\"schemaVersion\":1,\"values\":{\"n\":1.00e2}}", LIMITS);
    assertEquals(SourcePath.ROOT.property("values"), JsonEnvelope.values(input, 1));
    assertEquals(
        "1.00e2", input.scalarText().get(SourcePath.ROOT.property("values").property("n")));
    var nullable = JsonInput.read("{\"schemaVersion\":1.0,\"values\":null}", LIMITS);
    assertEquals(SourcePath.ROOT.property("values"), JsonEnvelope.values(nullable, 1));
    assertTrue(nullable.copyTree().getAsJsonObject().get("values").isJsonNull());
  }

  @Test
  void envelopeShapeAndTargetVersionAreExplicitAndNeverGuessed() throws Exception {
    for (String source :
        List.of(
            "[]",
            "null",
            "{}",
            "{\"values\":1}",
            "{\"schemaVersion\":1}",
            "{\"schemaVersion\":1,\"values\":1,\"legacy\":true}")) {
      var input = JsonInput.read(source, LIMITS);
      assertEquals(
          "json.envelope",
          assertThrows(JsonInputException.class, () -> JsonEnvelope.values(input, 1)).code());
    }
    for (String version : List.of("null", "\"1\"", "1.5", "2", "-1", "2147483648")) {
      var input = JsonInput.read("{\"schemaVersion\":" + version + ",\"values\":1}", LIMITS);
      var failure = assertThrows(JsonInputException.class, () -> JsonEnvelope.values(input, 1));
      assertEquals("json.schema", failure.code());
      assertEquals("$.schemaVersion", failure.location());
    }
    var valid = JsonInput.read("{\"schemaVersion\":1,\"values\":1}", LIMITS);
    assertThrows(IllegalArgumentException.class, () -> JsonEnvelope.values(valid, 0));
  }
}
