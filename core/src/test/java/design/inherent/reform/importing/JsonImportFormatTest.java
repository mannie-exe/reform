package design.inherent.reform.importing;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JsonImportFormatTest {
  private static final JsonLimits JSON = new JsonLimits(8192, 32, 512, 1024, 128, 4096);
  private static final DocumentDefinition<Integer> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("example:count"), 3, ValueTypes.integer(0, 100), () -> 1);

  private static ImportInput input(String source) {
    return new ImportInput("Selected profile", source.getBytes(StandardCharsets.UTF_8), 8192);
  }

  private static JsonImportFormat<Integer> format(JsonImportFormat.Mode mode) {
    return new JsonImportFormat<>(
        DocumentId.parse("example:json"),
        DEFINITION,
        mode,
        JSON,
        DraftLimits.CONFIGURATION,
        ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
  }

  @Test
  void admittedInputOwnsBytesAndIdentifiesTheActualCapturedContent() {
    var bytes = "1.00e1".getBytes(StandardCharsets.UTF_8);
    var captured = new ImportInput("Profile", bytes, 6);
    bytes[0] = '9';
    captured.bytes()[0] = '8';
    assertArrayEquals("1.00e1".getBytes(StandardCharsets.UTF_8), captured.bytes());
    assertEquals(6, captured.size());
    assertEquals("Profile", captured.label());
    assertEquals(captured.sha256(), input("1.00e1").sha256());
    assertNotEquals(captured.sha256(), input("10").sha256());
    assertThrows(IllegalArgumentException.class, () -> new ImportInput("Profile", bytes, 5));
    assertThrows(IllegalArgumentException.class, () -> new ImportInput("Profile", bytes, 0));
    assertThrows(IllegalArgumentException.class, () -> new ImportInput(" ", bytes, 10));
    assertThrows(IllegalArgumentException.class, () -> new ImportInput("x".repeat(257), bytes, 10));
  }

  @Test
  void explicitValueFormatRetainsLexemesAndDoesNotClaimASourceVersion() throws Exception {
    var parser = format(JsonImportFormat.Mode.VALUE_JSON);
    var decoded = parser.read(input("1.00e1"));
    assertTrue(decoded.sourceSchemaVersion().isEmpty());
    assertSame(DEFINITION, parser.definition());
    assertEquals("1.00e1", ((DraftNode.Scalar) decoded.draft().at(FieldPath.ROOT)).raw());
    assertEquals(10, decoded.draft().inspect(DEFINITION, JSON).candidate().orElseThrow().value());
    var envelopeAsValue = parser.read(input("{\"schemaVersion\":3,\"values\":10}"));
    assertTrue(envelopeAsValue.sourceSchemaVersion().isEmpty());
    assertTrue(envelopeAsValue.draft().inspect(DEFINITION, JSON).candidate().isEmpty());
  }

  @Test
  void versionedFormatSelectsOnlyTheDeclaredEnvelopeAndPreservesItsInput() throws Exception {
    var parser = format(JsonImportFormat.Mode.SCHEMA_ENVELOPE);
    var decoded = parser.read(input("{\"schemaVersion\":3.0,\"values\":1.00e1}"));
    assertEquals(3, decoded.sourceSchemaVersion().orElseThrow());
    assertEquals("1.00e1", ((DraftNode.Scalar) decoded.draft().root()).raw());
    assertEquals(10, decoded.draft().inspect(DEFINITION, JSON).candidate().orElseThrow().value());
    assertEquals(
        "json.envelope",
        assertThrows(JsonInputException.class, () -> parser.read(input("10"))).code());
    for (String version : List.of("1", "4", "3.1", "\"3\""))
      assertEquals(
          "json.schema",
          assertThrows(
                  JsonInputException.class,
                  () -> parser.read(input("{\"schemaVersion\":" + version + ",\"values\":10}")))
              .code());
  }

  @Test
  void explicitNullSurvivesEnvelopeSelection() throws Exception {
    var type = ValueTypes.optional(ValueTypes.bool(), ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("example:nullable"), 1, type, Optional::empty);
    var parser =
        new JsonImportFormat<>(
            DocumentId.parse("example:nullable_json"),
            definition,
            JsonImportFormat.Mode.SCHEMA_ENVELOPE,
            JSON,
            DraftLimits.CONFIGURATION,
            ImportFormat.InvalidDraftPolicy.REFUSE);
    var decoded = parser.read(input("{\"schemaVersion\":1,\"values\":null}"));
    assertEquals(
        Optional.empty(),
        decoded.draft().inspect(definition, JSON).candidate().orElseThrow().value());
  }

  @Test
  void malformedInputIsRefusedBeforeAnyDraftIsReturned() {
    var parser = format(JsonImportFormat.Mode.VALUE_JSON);
    assertEquals(
        "json.encoding",
        assertThrows(
                JsonInputException.class,
                () -> parser.read(new ImportInput("Profile", new byte[] {(byte) 0xc3, 0x28}, 2)))
            .code());
    assertEquals(
        "json.duplicate_key",
        assertThrows(JsonInputException.class, () -> parser.read(input("{\"n\":1,\"n\":2}")))
            .code());
    assertEquals(
        "json.trailing",
        assertThrows(JsonInputException.class, () -> parser.read(input("10 20"))).code());
    var smaller =
        new JsonImportFormat<>(
            DocumentId.parse("example:small_json"),
            DEFINITION,
            JsonImportFormat.Mode.VALUE_JSON,
            new JsonLimits(2, 4, 8, 8, 8, 8),
            DraftLimits.CONFIGURATION,
            ImportFormat.InvalidDraftPolicy.REFUSE);
    assertEquals(
        "json.bytes",
        assertThrows(JsonInputException.class, () -> smaller.read(input("100"))).code());
  }

  @Test
  void parsePreservesRepairableInvalidInputWhilePolicyRemainsExplicit() throws Exception {
    for (var policy : ImportFormat.InvalidDraftPolicy.values()) {
      var parser =
          new JsonImportFormat<>(
              DocumentId.parse("example:repair"),
              DEFINITION,
              JsonImportFormat.Mode.VALUE_JSON,
              JSON,
              DraftLimits.CONFIGURATION,
              policy);
      var decoded = parser.read(input("101"));
      assertSame(policy, parser.invalidDraftPolicy());
      assertEquals("101", ((DraftNode.Scalar) decoded.draft().root()).raw());
      var inspected = decoded.draft().inspect(DEFINITION, JSON);
      assertTrue(inspected.candidate().isEmpty());
      assertFalse(inspected.report().valid());
    }
  }

  @Test
  void parserAndEditorBudgetsRemainIndependent() {
    var parser =
        new JsonImportFormat<>(
            DocumentId.parse("example:bounded"),
            DEFINITION,
            JsonImportFormat.Mode.VALUE_JSON,
            JSON,
            new DraftLimits(4, 8, 2, 2, 32),
            ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
    assertEquals(
        "import.draft_limits",
        assertThrows(JsonInputException.class, () -> parser.read(input("100"))).code());
  }
}
