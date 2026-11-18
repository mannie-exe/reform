package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.operation.FieldIndex;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReferenceTypeTest {
  private static final DocumentId SOURCE = DocumentId.parse("fixture:biomes");
  private static final JsonLimits JSON = new JsonLimits(8192, 32, 1024, 512, 64, 1024);

  private record Profile(String biome) {}

  @Test
  void metadataPreservesCodecDataPathsAndUnresolvedDraftsWithoutAResolver() throws Exception {
    var reference = ValueTypes.reference(ValueTypes.text(128), SOURCE);
    var field = FieldKey.of("biome", reference);
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(field)))
            .field(field, Profile::biome)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:profile"), 1, type, () -> new Profile("optional:missing"));
    var path = FieldPath.ROOT.field("biome");
    try (var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "one",
            JsonInput.read("{\"biome\":\"optional:missing\"}", JSON),
            JSON,
            DraftLimits.CONFIGURATION)) {
      assertEquals(
          new Profile("optional:missing"),
          session.snapshot().inspect().candidate().orElseThrow().value());
      assertEquals(
          "optional:missing", ((DraftNode.Scalar) session.snapshot().draft().at(path)).raw());
      var index = new FieldIndex(type, 16, 16);
      assertTrue(index.field("biome").orElseThrow().scalar());
      assertEquals(path, index.field("biome").orElseThrow().path());
      assertEquals(
          SOURCE,
          ((ValueShape.Reference<?>) index.field("biome").orElseThrow().type().shape()).source());
      session.setRaw(path, "another:missing");
      assertEquals(
          "{\"biome\":\"another:missing\"}",
          session.snapshot().inspect().candidate().orElseThrow().authoredJson());
      session.undo();
      assertFalse(session.dirty());
    }
  }

  @Test
  void referencesComposeWithMapKeysDefaultsAndOptionalsWithoutAnotherValueWrapper()
      throws Exception {
    var reference = ValueTypes.reference(ValueTypes.text(128), SOURCE);
    var map = ValueTypes.objectMap(reference, ValueTypes.integer(0, 10), 4);
    assertEquals(
        Map.of("optional:missing", 2),
        JsonInput.read("{\"optional:missing\":2}", JSON).decode(map).value().orElseThrow());
    var optional = ValueTypes.optional(reference, ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var field = FieldKey.of("biome", ValueTypes.defaultedField(reference, "optional:missing"));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(field)))
            .field(field, Profile::biome)
            .build();
    assertEquals(
        new Profile("optional:missing"),
        JsonInput.read("{}", JSON).decode(type).value().orElseThrow());
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:optional_ref"), 1, optional, java.util.Optional::empty);
    var draft =
        DraftTree.fromParsed(
            optional, JsonInput.read("\"optional:missing\"", JSON), DraftLimits.CONFIGURATION);
    assertEquals(
        java.util.Optional.of("optional:missing"),
        draft.inspect(definition, JSON).candidate().orElseThrow().value());
    assertInstanceOf(DraftNode.Scalar.class, draft.at(FieldPath.ROOT.append(FieldPath.Part.VALUE)));
    assertThrows(
        IllegalArgumentException.class, () -> ValueTypes.reference(ValueTypes.bool(), SOURCE));
  }

  @Test
  void decoratingACustomTextValueDoesNotAddAFalseSnapshotCycle() {
    record Key(String id) {}
    var base =
        ValueType.of(
            ValueTypes.text(128).codec().xmap(Key::new, Key::id),
            ValueTypes.text(128).shape(),
            new SnapshotPolicy<Key>() {
              @Override
              public Key freeze(Key key, SnapshotContext context) {
                return new Key(key.id());
              }

              @Override
              public boolean equivalent(Key first, Key second) {
                return first.equals(second);
              }
            });
    var type = ValueTypes.reference(base, SOURCE);
    var original = new Key("optional:missing");
    var owned = type.freeze(original);
    assertEquals(original, owned);
    assertNotSame(original, owned);
    assertEquals(
        "\"optional:missing\"",
        type.codec().encodeStart(JsonOps.INSTANCE, owned).getOrThrow().toString());
  }
}
