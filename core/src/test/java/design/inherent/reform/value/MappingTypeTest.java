package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MappingTypeTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);

  @Test
  void dynamicObjectKeysRemainObjectsWithCompressedOps() {
    var type = ValueTypes.objectMap(ValueTypes.text(16), ValueTypes.integer(0, 100), 8);
    var encoded = type.codec().encodeStart(JsonOps.COMPRESSED, Map.of("height", 12)).getOrThrow();
    assertTrue(encoded.isJsonObject());
    assertEquals(
        Map.of("height", 12), type.codec().parse(JsonOps.COMPRESSED, encoded).getOrThrow());
  }

  @Test
  void encodingsAreExplicitAndRoundtripWithoutInjectingRowIds() throws Exception {
    var object = ValueTypes.objectMap(ValueTypes.text(32), ValueTypes.integer(0, 20), 4);
    var entries = ValueTypes.entryMap(ValueTypes.integer(0, 20), ValueTypes.text(32), 4);
    assertEquals(
        Map.of("height", 12),
        JsonInput.read("{\"height\":12}", JSON).decode(object).value().orElseThrow());
    assertEquals(
        "{\"height\":12}",
        object.codec().encodeStart(JsonOps.INSTANCE, Map.of("height", 12)).getOrThrow().toString());
    assertEquals(
        Map.of(12, "height"),
        JsonInput.read("[{\"key\":12,\"value\":\"height\"}]", JSON)
            .decode(entries)
            .value()
            .orElseThrow());
    assertEquals(
        "[{\"key\":12,\"value\":\"height\"}]",
        entries
            .codec()
            .encodeStart(JsonOps.INSTANCE, Map.of(12, "height"))
            .getOrThrow()
            .toString());
    assertTrue(JsonInput.read("[]", JSON).decode(object).value().isEmpty());
    assertTrue(JsonInput.read("{}", JSON).decode(entries).value().isEmpty());
    assertEquals(Map.of(), JsonInput.read("{}", JSON).decode(object).value().orElseThrow());
    assertEquals(Map.of(), JsonInput.read("[]", JSON).decode(entries).value().orElseThrow());
  }

  @Test
  void invalidEntryFieldsAndNumericKindsNeverProduceAPartialMap() throws Exception {
    var entries = ValueTypes.entryMap(ValueTypes.integer(0, 20), ValueTypes.integer(0, 20), 4);
    for (String input :
        List.of(
            "[{\"key\":1.5,\"value\":2}]",
            "[{\"key\":\"1\",\"value\":2}]",
            "[{\"key\":1,\"value\":2},{\"key\":3,\"value\":999}]",
            "[{\"key\":1}]",
            "[{\"key\":1,\"value\":2,\"extra\":0}]",
            "[null]")) {
      var result = JsonInput.read(input, JSON).decode(entries.codec());
      assertTrue(result.error().isPresent(), input);
      assertTrue(result.result().isEmpty(), input);
    }
  }

  @Test
  void duplicateDecodedKeysAreRefusedInBothFormats() throws Exception {
    var lowered =
        ValueType.of(
            ExactCodecs.STRING.xmap(s -> s.toLowerCase(Locale.ROOT), s -> s),
            ValueTypes.text(32).shape(),
            SnapshotPolicy.<String>immutable());
    var object = ValueTypes.objectMap(lowered, ValueTypes.bool(), 4);
    var entries = ValueTypes.entryMap(lowered, ValueTypes.bool(), 4);
    assertTrue(JsonInput.read("{\"A\":true,\"a\":false}", JSON).decode(object).value().isEmpty());
    assertTrue(
        JsonInput.read("[{\"key\":\"A\",\"value\":true},{\"key\":\"a\",\"value\":false}]", JSON)
            .decode(entries)
            .value()
            .isEmpty());
    assertEquals(
        Map.of("a", true),
        JsonInput.read("{\"A\":true}", JSON).decode(object).value().orElseThrow());
  }

  @Test
  void objectEncodingNeverCoercesNumbersOrLosesUnequalKeys() throws Exception {
    var integers = ValueTypes.objectMap(ValueTypes.integer(0, 20), ValueTypes.bool(), 4);
    assertTrue(integers.codec().encodeStart(JsonOps.INSTANCE, Map.of(1, true)).error().isPresent());
    assertTrue(JsonInput.read("{\"1\":true}", JSON).decode(integers).value().isEmpty());
    var lossy =
        ValueType.of(
            ExactCodecs.STRING.xmap(s -> s, s -> s.toLowerCase(Locale.ROOT)),
            ValueTypes.text(32).shape(),
            SnapshotPolicy.<String>immutable());
    var object = ValueTypes.objectMap(lossy, ValueTypes.bool(), 4);
    assertTrue(
        object
            .codec()
            .encodeStart(JsonOps.INSTANCE, Map.of("A", true, "a", false))
            .error()
            .isPresent());
    var entry = ValueTypes.entryMap(lossy, ValueTypes.bool(), 4);
    assertTrue(entry.codec().encodeStart(JsonOps.INSTANCE, Map.of("A", true)).error().isPresent());
  }

  @Test
  void sizeBoundsApplyToDecodeEncodeAndOwnedSnapshots() throws Exception {
    for (var type :
        List.of(
            ValueTypes.objectMap(ValueTypes.text(8), ValueTypes.bool(), 1),
            ValueTypes.entryMap(ValueTypes.text(8), ValueTypes.bool(), 1))) {
      assertTrue(
          type.codec()
              .encodeStart(JsonOps.INSTANCE, Map.of("a", true, "b", false))
              .error()
              .isPresent());
      assertThrows(
          IllegalArgumentException.class, () -> type.freeze(Map.of("a", true, "b", false)));
    }
    assertTrue(
        JsonInput.read("{\"a\":true,\"b\":false}", JSON)
            .decode(ValueTypes.objectMap(ValueTypes.text(8), ValueTypes.bool(), 1))
            .value()
            .isEmpty());
    assertTrue(
        JsonInput.read("[{\"key\":\"a\",\"value\":true},{\"key\":\"b\",\"value\":false}]", JSON)
            .decode(ValueTypes.entryMap(ValueTypes.text(8), ValueTypes.bool(), 1))
            .value()
            .isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.objectMap(ValueTypes.text(8), ValueTypes.bool(), -1));
  }

  @Test
  void ownedCopiesProtectCompositeKeysAndValuesFromExternalMutation() {
    var type =
        ValueTypes.entryMap(
            ValueTypes.list(ValueTypes.text(16), 4),
            ValueTypes.list(ValueTypes.integer(0, 10), 4),
            4);
    var key = new ArrayList<>(List.of("a"));
    var value = new ArrayList<>(List.of(2));
    var input = new LinkedHashMap<List<String>, List<Integer>>();
    input.put(key, value);
    var frozen = type.freeze(input);
    key.clear();
    value.clear();
    input.clear();
    assertEquals(Map.of(List.of("a"), List.of(2)), frozen);
    assertThrows(UnsupportedOperationException.class, () -> frozen.clear());
    assertThrows(
        UnsupportedOperationException.class, () -> frozen.keySet().iterator().next().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> frozen.values().iterator().next().clear());
  }

  @Test
  void copyUnstableKeyPoliciesAreRefusedRatherThanChangingIdentity() {
    var key =
        ValueType.of(
            ExactCodecs.STRING,
            ValueTypes.text(16).shape(),
            (String s, SnapshotContext context) -> s.toLowerCase(Locale.ROOT));
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.entryMap(key, ValueTypes.bool(), 4).freeze(Map.of("A", true)));
  }

  @Test
  void mapEqualityIgnoresOrderButUsesExactKeysAndChildValuePolicy() {
    var insensitive =
        ValueType.of(
            Codec.STRING,
            new ValueShape.Custom("fixture:case"),
            new SnapshotPolicy<String>() {
              @Override
              public String freeze(String s, SnapshotContext context) {
                return s;
              }

              @Override
              public boolean equivalent(String first, String second) {
                return first.equalsIgnoreCase(second);
              }
            });
    var type = ValueTypes.objectMap(ValueTypes.text(8), insensitive, 4);
    var first = new LinkedHashMap<String, String>();
    first.put("a", "Hello");
    first.put("b", "World");
    var second = new LinkedHashMap<String, String>();
    second.put("b", "world");
    second.put("a", "hello");
    assertTrue(type.equivalent(first, second));
    assertFalse(type.equivalent(first, Map.of("A", "Hello", "b", "World")));
    assertFalse(type.equivalent(first, Map.of("a", "Hello", "b", "Other")));
  }

  @Test
  void partialKeyCodecsDoNotBecomeSuccessfulMapResults() throws Exception {
    var partial =
        ValueType.of(
            ExactCodecs.STRING.flatXmap(
                s -> DataResult.error(() -> "key unavailable", s), DataResult::success),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    for (var type :
        List.of(
            ValueTypes.objectMap(partial, ValueTypes.bool(), 4),
            ValueTypes.entryMap(partial, ValueTypes.bool(), 4)))
      assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, Map.of("a", true)).error().isPresent());
    assertTrue(
        JsonInput.read("{\"a\":true}", JSON)
            .decode(ValueTypes.objectMap(partial, ValueTypes.bool(), 4))
            .value()
            .isEmpty());
    assertTrue(
        JsonInput.read("[{\"key\":\"a\",\"value\":true}]", JSON)
            .decode(ValueTypes.entryMap(partial, ValueTypes.bool(), 4))
            .value()
            .isEmpty());
  }
}
