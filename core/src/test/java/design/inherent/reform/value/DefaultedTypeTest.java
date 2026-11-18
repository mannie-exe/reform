package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultedTypeTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);

  record Settings(String name, boolean enabled, int height) {}

  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(32));
  private static final FieldKey<Boolean> ENABLED =
      FieldKey.of("enabled", ValueTypes.defaultedField(ValueTypes.bool(), true));
  private static final FieldKey<Integer> HEIGHT =
      FieldKey.of("height", ValueTypes.defaultedField(ValueTypes.integer(0, 100), 4));
  private static final RecordType<Settings> TYPE =
      RecordType.<Settings>builder(v -> new Settings(v.get(NAME), v.get(ENABLED), v.get(HEIGHT)))
          .field(NAME, Settings::name)
          .field(ENABLED, Settings::enabled)
          .field(HEIGHT, Settings::height)
          .build();

  @Test
  void missingDefaultsResolveButExplicitValuesAndRequiredFieldsKeepTheirMeaning() throws Exception {
    assertEquals(
        new Settings("a", true, 4),
        JsonInput.read("{\"name\":\"a\"}", JSON).decode(TYPE).value().orElseThrow());
    assertEquals(
        new Settings("a", false, 7),
        JsonInput.read("{\"name\":\"a\",\"enabled\":false,\"height\":7}", JSON)
            .decode(TYPE)
            .value()
            .orElseThrow());
    assertEquals(
        "{\"name\":\"a\"}",
        TYPE.codec()
            .encodeStart(JsonOps.INSTANCE, new Settings("a", true, 4))
            .getOrThrow()
            .toString());
    assertTrue(JsonInput.read("{}", JSON).decode(TYPE).value().isEmpty());
    for (String source :
        List.of(
            "{\"name\":\"a\",\"height\":null}",
            "{\"name\":\"a\",\"height\":1000}",
            "{\"name\":\"a\",\"height\":4.5}",
            "{\"name\":\"a\",\"enabled\":\"no\"}"))
      assertTrue(JsonInput.read(source, JSON).decode(TYPE).value().isEmpty(), source);
  }

  @Test
  void ordinaryAndCompressedCodecsRetainNativeOmissionSemantics() throws Exception {
    var nativeCodec =
        RecordCodecBuilder.<Settings>create(
            i ->
                i.group(
                        ExactCodecs.STRING.fieldOf("name").forGetter(Settings::name),
                        ExactCodecs.BOOL
                            .optionalFieldOf("enabled", true)
                            .forGetter(Settings::enabled),
                        ValueTypes.integer(0, 100)
                            .codec()
                            .optionalFieldOf("height", 4)
                            .forGetter(Settings::height))
                    .apply(i, Settings::new));
    var explicitNull = JsonInput.read("{\"name\":\"a\",\"height\":null}", JSON).copyTree();
    var nativeMap = JsonOps.INSTANCE.getMap(explicitNull).getOrThrow();
    assertNull(nativeMap.get("height"));
    assertTrue(
        nativeMap.entries().anyMatch(pair -> pair.getFirst().getAsString().equals("height")));
    assertEquals(
        new Settings("a", true, 4), nativeCodec.parse(JsonOps.INSTANCE, explicitNull).getOrThrow());
    assertTrue(
        TYPE.decode(
                JsonOps.INSTANCE, explicitNull, design.inherent.reform.definition.FieldPath.ROOT)
            .value()
            .isEmpty());
    for (var ops : List.of(JsonOps.INSTANCE, JsonOps.COMPRESSED))
      for (var value : List.of(new Settings("a", true, 4), new Settings("a", false, 8))) {
        var expected = nativeCodec.encodeStart(ops, value).getOrThrow();
        var actual = TYPE.codec().encodeStart(ops, value).getOrThrow();
        assertEquals(expected, actual);
        assertEquals(value, TYPE.codec().parse(ops, actual).getOrThrow());
        assertEquals(value, nativeCodec.parse(ops, actual).getOrThrow());
      }
  }

  @Test
  void fallbacksAreOwnedAndOrdinaryValueCodecsNeverInventAbsentInputs() {
    var source = new ArrayList<>(List.of("original"));
    var type = ValueTypes.defaultedField(ValueTypes.list(ValueTypes.text(16), 8), source);
    source.clear();
    assertEquals(List.of("original"), type.fallback());
    assertThrows(UnsupportedOperationException.class, () -> type.fallback().clear());
    assertEquals(List.of("original"), type.shape().fallback());
    assertTrue(
        type.codec()
            .parse(JsonOps.INSTANCE, com.google.gson.JsonNull.INSTANCE)
            .error()
            .isPresent());
    assertThrows(IllegalArgumentException.class, () -> ValueTypes.defaultedField(type, List.of()));
  }

  @Test
  void malformedAndPartialDefaultsFailAtDeclaration() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ValueTypes.defaultedField(ValueTypes.integer(0, 10), 11));
    var partial =
        ValueType.of(
            ExactCodecs.STRING.flatXmap(
                s -> DataResult.error(() -> "unavailable", s), DataResult::success),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    assertThrows(IllegalArgumentException.class, () -> ValueTypes.defaultedField(partial, "a"));
  }

  @Test
  void semanticDefaultEqualityCannotHideAnInvalidTypedField() {
    var bounded = ValueTypes.integer(0, 10);
    var broad =
        ValueType.of(
            bounded.codec(),
            bounded.shape(),
            new SnapshotPolicy<Integer>() {
              @Override
              public Integer freeze(Integer value, SnapshotContext context) {
                return value;
              }

              @Override
              public boolean equivalent(Integer first, Integer second) {
                return true;
              }
            });
    record Number(int value) {}
    var key = FieldKey.of("value", ValueTypes.defaultedField(broad, 4));
    var type =
        RecordType.<Number>builder(v -> new Number(v.get(key))).field(key, Number::value).build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:default_validation"), 1, type, () -> new Number(4));
    assertTrue(definition.validate(new Number(99)).value().isEmpty());
    assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, new Number(99)).error().isPresent());
  }
}
