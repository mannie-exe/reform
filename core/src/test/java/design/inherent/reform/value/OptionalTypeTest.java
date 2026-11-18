package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonNull;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OptionalTypeTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);
  private static final ValueType<Integer> INTEGER = ValueTypes.integer(0, 100);

  @Test
  void explicitEncodingsRoundtripEmptyAndPresentWithoutCoercion() throws Exception {
    for (var encoding : ValueShape.OptionalEncoding.values()) {
      var type = ValueTypes.optional(INTEGER, encoding);
      for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED))
        for (var value : List.of(Optional.<Integer>empty(), Optional.of(7))) {
          var encoded = type.codec().encodeStart(ops, value).getOrThrow();
          assertEquals(
              encoding == ValueShape.OptionalEncoding.SINGLETON_LIST
                  ? (value.isPresent() ? "[7]" : "[]")
                  : (value.isPresent() ? "7" : "null"),
              encoded.toString());
          assertEquals(value, type.codec().parse(ops, encoded).getOrThrow());
          assertEquals(value, type.decode(ops, encoded, FieldPath.ROOT).value().orElseThrow());
        }
      for (String invalid :
          encoding == ValueShape.OptionalEncoding.SINGLETON_LIST
              ? List.of("[1,2]", "null", "[null]", "[1.2]", "[\"1\"]", "{}", "[101]")
              : List.of("[]", "[7]", "{}", "1.2", "\"1\"", "101")) {
        var parsed = JsonInput.read(invalid, JSON);
        assertTrue(parsed.decode(type).value().isEmpty(), invalid);
        assertTrue(
            type.codec().parse(JsonOps.INSTANCE, parsed.copyTree()).error().isPresent(), invalid);
      }
    }
  }

  public record Profile(Optional<Integer> height) {}

  @Test
  void nonemptyFallbackCannotReplaceAnExplicitEmptyCompressedValue() throws Exception {
    var key =
        FieldKey.of(
            "height",
            ValueTypes.defaultedField(
                ValueTypes.optional(INTEGER, ValueShape.OptionalEncoding.NULL_OR_VALUE),
                Optional.of(7)));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(key)))
            .field(key, Profile::height)
            .build();
    var empty = new Profile(Optional.empty());
    assertEquals(
        empty,
        type.codec()
            .parse(JsonOps.INSTANCE, type.codec().encodeStart(JsonOps.INSTANCE, empty).getOrThrow())
            .getOrThrow());
    assertEquals(
        new Profile(Optional.of(7)), JsonInput.read("{}", JSON).decode(type).value().orElseThrow());
    assertTrue(type.codec().encodeStart(JsonOps.COMPRESSED, empty).error().isPresent());
  }

  @Test
  void declaredEmptyFieldDefaultMatchesActualNativeOptionalFieldEncoding() throws Exception {
    var key =
        FieldKey.of(
            "height",
            ValueTypes.defaultedField(
                ValueTypes.optional(INTEGER, ValueShape.OptionalEncoding.NULL_OR_VALUE),
                Optional.empty()));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(key)))
            .field(key, Profile::height)
            .build();
    var nativeCodec =
        RecordCodecBuilder.<Profile>create(
            i ->
                i.group(INTEGER.codec().optionalFieldOf("height").forGetter(Profile::height))
                    .apply(i, Profile::new));
    for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED))
      for (var profile : List.of(new Profile(Optional.empty()), new Profile(Optional.of(7)))) {
        var encoded = type.codec().encodeStart(ops, profile).getOrThrow();
        assertEquals(nativeCodec.encodeStart(ops, profile).getOrThrow(), encoded);
        assertEquals(profile, type.codec().parse(ops, encoded).getOrThrow());
        assertEquals(profile, nativeCodec.parse(ops, encoded).getOrThrow());
      }
    assertEquals(
        new Profile(Optional.empty()),
        JsonInput.read("{}", JSON).decode(type).value().orElseThrow());
    assertEquals(
        new Profile(Optional.empty()),
        JsonInput.read("{\"height\":null}", JSON).decode(type).value().orElseThrow());
    assertTrue(JsonInput.read("{\"height\":\"bad\"}", JSON).decode(type).value().isEmpty());
  }

  @Test
  void requiredNullableFieldDoesNotInventAMissingValueOrClaimCompressedRepresentation()
      throws Exception {
    var key =
        FieldKey.of(
            "height", ValueTypes.optional(INTEGER, ValueShape.OptionalEncoding.NULL_OR_VALUE));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(key)))
            .field(key, Profile::height)
            .build();
    assertTrue(JsonInput.read("{}", JSON).decode(type).value().isEmpty());
    var empty = new Profile(Optional.empty());
    assertEquals(
        empty, JsonInput.read("{\"height\":null}", JSON).decode(type).value().orElseThrow());
    assertEquals(
        "{\"height\":null}",
        type.codec().encodeStart(JsonOps.INSTANCE, empty).getOrThrow().toString());
    assertTrue(type.codec().encodeStart(JsonOps.COMPRESSED, empty).error().isPresent());
    assertTrue(
        type.codec()
            .parse(JsonOps.COMPRESSED, JsonInput.read("[null]", JSON).copyTree())
            .error()
            .isPresent());
  }

  @Test
  void singletonListsPreserveNestedEmptyWhereNullableEncodingWouldBeAmbiguous() throws Exception {
    var inner = ValueTypes.optional(INTEGER, ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var outer = ValueTypes.optional(inner, ValueShape.OptionalEncoding.SINGLETON_LIST);
    var nestedEmpty = Optional.of(Optional.<Integer>empty());
    for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED)) {
      assertEquals("[null]", outer.codec().encodeStart(ops, nestedEmpty).getOrThrow().toString());
      assertEquals(
          nestedEmpty,
          outer.codec().parse(ops, JsonInput.read("[null]", JSON).copyTree()).getOrThrow());
      assertEquals(
          Optional.empty(),
          outer.codec().parse(ops, JsonInput.read("[]", JSON).copyTree()).getOrThrow());
    }
    var ambiguous = ValueTypes.optional(inner, ValueShape.OptionalEncoding.NULL_OR_VALUE);
    assertTrue(ambiguous.codec().encodeStart(JsonOps.INSTANCE, nestedEmpty).error().isPresent());
    assertEquals(
        Optional.empty(),
        ambiguous.codec().parse(JsonOps.INSTANCE, JsonNull.INSTANCE).getOrThrow());
    var bothLists = ValueTypes.optional(outer, ValueShape.OptionalEncoding.SINGLETON_LIST);
    assertEquals(
        "[[null]]",
        bothLists
            .codec()
            .encodeStart(JsonOps.INSTANCE, Optional.of(nestedEmpty))
            .getOrThrow()
            .toString());
  }

  @Test
  void childPartialsAndLossyNullCanonicalizationNeverBecomeSuccessfulMembership() throws Exception {
    var partial =
        ValueType.of(
            ExactCodecs.INT.flatXmap(
                value -> DataResult.error(() -> "partial child", value), DataResult::success),
            INTEGER.shape(),
            SnapshotPolicy.<Integer>immutable());
    for (var encoding : ValueShape.OptionalEncoding.values()) {
      var type = ValueTypes.optional(partial, encoding);
      var input =
          JsonInput.read(encoding == ValueShape.OptionalEncoding.SINGLETON_LIST ? "[7]" : "7", JSON)
              .copyTree();
      assertTrue(type.codec().parse(JsonOps.INSTANCE, input).error().isPresent());
      assertTrue(type.decode(JsonOps.INSTANCE, input, FieldPath.ROOT).value().isEmpty());
      assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, Optional.of(7)).error().isPresent());
    }
    var lossyCodec =
        new com.mojang.serialization.Codec<Integer>() {
          @Override
          public <O> DataResult<com.mojang.datafixers.util.Pair<Integer, O>> decode(
              com.mojang.serialization.DynamicOps<O> ops, O input) {
            return DataResult.success(com.mojang.datafixers.util.Pair.of(7, ops.empty()));
          }

          @Override
          public <O> DataResult<O> encode(
              Integer value, com.mojang.serialization.DynamicOps<O> ops, O prefix) {
            return DataResult.success(ops.empty());
          }
        };
    var lossy =
        ValueTypes.optional(
            ValueType.of(lossyCodec, INTEGER.shape(), SnapshotPolicy.immutable()),
            ValueShape.OptionalEncoding.NULL_OR_VALUE);
    assertTrue(
        lossy
            .codec()
            .parse(JsonOps.INSTANCE, JsonInput.read("7", JSON).copyTree())
            .error()
            .isPresent());
    assertTrue(lossy.codec().encodeStart(JsonOps.INSTANCE, Optional.of(7)).error().isPresent());
  }

  @Test
  void ownedCopiesAndCollectionCompositionRetainMembershipAndChildRules() throws Exception {
    var type =
        ValueTypes.optional(
            ValueTypes.list(ValueTypes.text(16), 4), ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var source = new ArrayList<>(List.of("a"));
    var frozen = type.freeze(Optional.of(source));
    source.clear();
    assertEquals(Optional.of(List.of("a")), frozen);
    assertThrows(UnsupportedOperationException.class, () -> frozen.orElseThrow().clear());
    assertFalse(type.equivalent(Optional.empty(), Optional.of(List.of())));
    assertTrue(type.equivalent(Optional.of(List.of("a")), frozen));
    var values = ValueTypes.objectMap(ValueTypes.text(16), type, 4);
    var expected =
        Map.of("empty", Optional.<List<String>>empty(), "present", Optional.of(List.of("a")));
    var encoded = values.codec().encodeStart(JsonOps.INSTANCE, expected).getOrThrow();
    assertEquals(
        expected, JsonInput.read(encoded.toString(), JSON).decode(values).value().orElseThrow());
    var members =
        ValueTypes.set(ValueTypes.optional(INTEGER, ValueShape.OptionalEncoding.SINGLETON_LIST), 4);
    assertTrue(JsonInput.read("[[],[]]", JSON).decode(members).value().isEmpty());
    assertEquals(
        java.util.Set.of(Optional.empty(), Optional.of(7)),
        JsonInput.read("[[],[7]]", JSON).decode(members).value().orElseThrow());
  }
}
