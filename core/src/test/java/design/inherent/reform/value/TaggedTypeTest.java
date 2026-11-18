package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaggedTypeTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);

  interface Mode {}

  record Land(int height, List<String> materials) implements Mode {}

  record Cavern(boolean open) implements Mode {}

  record Unknown() implements Mode {}

  private static final FieldKey<Integer> HEIGHT = FieldKey.of("height", ValueTypes.integer(0, 100));
  private static final FieldKey<List<String>> MATERIALS =
      FieldKey.of("materials", ValueTypes.list(ValueTypes.text(16), 4));
  private static final FieldKey<Boolean> OPEN = FieldKey.of("open", ValueTypes.bool());
  private static final RecordType<Land> LAND =
      RecordType.<Land>builder(v -> new Land(v.get(HEIGHT), v.get(MATERIALS)))
          .field(HEIGHT, Land::height)
          .field(MATERIALS, Land::materials)
          .build();
  private static final RecordType<Cavern> CAVERN =
      RecordType.<Cavern>builder(v -> new Cavern(v.get(OPEN))).field(OPEN, Cavern::open).build();

  private static TaggedType<Mode> type() {
    return TaggedType.<Mode>builder("kind", "settings")
        .variant("land", Land.class, LAND)
        .variant("cavern", Cavern.class, CAVERN)
        .build();
  }

  @Test
  void registeredRecordVariantsRoundtripInOrdinaryAndCompressedOps() {
    var type = type();
    for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED))
      for (Mode value : List.of(new Land(7, List.of("stone")), new Cavern(true))) {
        var encoded = type.codec().encodeStart(ops, value).getOrThrow();
        assertTrue(
            encoded.isJsonObject(),
            "The declared tagged envelope does not flatten or compress its dynamic keys");
        assertEquals(value, type.codec().parse(ops, encoded).getOrThrow());
        assertEquals(value, type.decode(ops, encoded, FieldPath.ROOT).value().orElseThrow());
      }
  }

  @Test
  void ordinaryEncodingIsTheDeclaredEnvelopeAndChildErrorsKeepTheirBranchAddress()
      throws Exception {
    var type = type();
    assertEquals(
        "{\"kind\":\"land\",\"settings\":{\"height\":7,\"materials\":[\"stone\"]}}",
        type.codec()
            .encodeStart(JsonOps.INSTANCE, new Land(7, List.of("stone")))
            .getOrThrow()
            .toString());
    var invalid =
        type.decode(
            JsonOps.INSTANCE,
            JsonInput.read(
                    "{\"kind\":\"land\",\"settings\":{\"height\":7.5,\"materials\":[]}}", JSON)
                .copyTree(),
            FieldPath.ROOT.field("profile"));
    assertTrue(invalid.value().isEmpty());
    assertEquals(
        FieldPath.ROOT.field("profile").variant("land").field("height"),
        invalid.report().issues().getFirst().path());
  }

  @Test
  void ownSnapshotsDoNotConfuseOneTaggedObjectWithARecursiveObjectCycle() {
    var original = new ArrayList<>(List.of("stone"));
    var type = type();
    var owned = (Land) type.freeze(new Land(7, original));
    original.clear();
    assertEquals(List.of("stone"), owned.materials());
    assertThrows(UnsupportedOperationException.class, () -> owned.materials().clear());
    assertTrue(type.equivalent(owned, new Land(7, List.of("stone"))));
    assertFalse(type.equivalent(owned, new Cavern(false)));
  }

  @Test
  void unknownExtraMissingAndWronglyTypedTagsNeverSelectADefaultBranch() throws Exception {
    var type = type();
    for (String input :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"kind\":\"unknown\",\"settings\":{}}",
            "{\"kind\":\"land\"}",
            "{\"settings\":{}}",
            "{\"kind\":true,\"settings\":{}}",
            "{\"kind\":\"cavern\",\"settings\":{\"open\":true},\"extra\":0}",
            "{\"kind\":\"cavern\",\"settings\":null}"))
      assertTrue(JsonInput.read(input, JSON).decode(type).value().isEmpty(), input);
    var numeric =
        TaggedType.<Mode>builder("kind", "settings").variant("7", Cavern.class, CAVERN).build();
    var compressedPayload =
        CAVERN.codec().encodeStart(JsonOps.COMPRESSED, new Cavern(true)).getOrThrow();
    var numericTag =
        JsonInput.read("{\"kind\":7,\"settings\":" + compressedPayload + "}", JSON).copyTree();
    assertTrue(numeric.codec().parse(JsonOps.COMPRESSED, numericTag).error().isPresent());
    assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, new Unknown()).error().isPresent());
    assertThrows(IllegalArgumentException.class, () -> type.freeze(new Unknown()));
  }

  @Test
  void duplicateIdentitiesAndAmbiguousFieldsAreRejectedBeforePublication() {
    assertThrows(
        IllegalArgumentException.class, () -> TaggedType.<Mode>builder("kind", "settings").build());
    assertThrows(
        IllegalArgumentException.class,
        () -> TaggedType.<Mode>builder("same", "same").variant("a", Land.class, LAND).build());
    var builder = TaggedType.<Mode>builder("kind", "settings").variant("land", Land.class, LAND);
    assertThrows(
        IllegalArgumentException.class, () -> builder.variant("land", Cavern.class, CAVERN));
    assertThrows(IllegalArgumentException.class, () -> builder.variant("other", Land.class, LAND));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TaggedType.<Mode>builder("kind", "settings").variant("interface", Mode.class, type()));
    var built = builder.build();
    builder.variant("cavern", Cavern.class, CAVERN);
    assertEquals(
        List.of("land"), built.shape().variants().stream().map(ValueShape.Variant::id).toList());
  }

  @Test
  void partialVariantPayloadsAreNotPromotedToACompleteTaggedValue() throws Exception {
    var partial =
        ValueType.of(
            CAVERN
                .codec()
                .flatXmap(
                    value -> DataResult.error(() -> "partial variant", value), DataResult::success),
            CAVERN.shape(),
            CAVERN.snapshotPolicy());
    var type =
        TaggedType.<Mode>builder("kind", "settings")
            .variant("cavern", Cavern.class, partial)
            .build();
    var input =
        JsonInput.read("{\"kind\":\"cavern\",\"settings\":{\"open\":true}}", JSON).copyTree();
    assertTrue(type.codec().parse(JsonOps.INSTANCE, input).error().isPresent());
    assertTrue(type.decode(JsonOps.INSTANCE, input, FieldPath.ROOT).value().isEmpty());
    assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, new Cavern(true)).error().isPresent());
  }

  @Test
  void compressedRecordInspectionComposesThroughWrappersAndKeepsNestedFieldPaths()
      throws Exception {
    var path = FieldPath.ROOT.field("profile");
    var value = new Land(7, List.of("stone"));
    var encoded = LAND.codec().encodeStart(JsonOps.COMPRESSED, value).getOrThrow();
    assertEquals(value, LAND.decode(JsonOps.COMPRESSED, encoded, path).value().orElseThrow());
    var invalid = JsonInput.read("[7.5,[]]", JSON).copyTree();
    var result = LAND.decode(JsonOps.COMPRESSED, invalid, path);
    assertTrue(result.value().isEmpty());
    assertEquals(path.field("height"), result.report().issues().getFirst().path());
    var optional = ValueTypes.optional(LAND, ValueShape.OptionalEncoding.SINGLETON_LIST);
    assertEquals(
        java.util.Optional.of(value),
        optional
            .codec()
            .parse(
                JsonOps.COMPRESSED,
                optional
                    .codec()
                    .encodeStart(JsonOps.COMPRESSED, java.util.Optional.of(value))
                    .getOrThrow())
            .getOrThrow());
  }
}
