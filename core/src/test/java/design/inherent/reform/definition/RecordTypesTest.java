package design.inherent.reform.definition;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class RecordTypesTest {
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 2048, 8192, 128, 1024);

  public enum Mode {
    QUIET,
    LOUD
  }

  public record Scalars(
      boolean enabled,
      byte tiny,
      short small,
      int integer,
      long wide,
      float single,
      double precise,
      String text,
      Mode mode) {}

  public record Profile(String label, List<Scalars> entries) {}

  public record Recursive(Recursive child) {}

  public record Unsupported(Map<Integer, String> mappings) {}

  public record Mappings(Map<String, List<Counted>> mappings) {}

  public record Sets(Set<List<Counted>> members) {}

  public record Wildcard(List<? extends Number> entries) {}

  public record Custom(Duration duration) {}

  private record PrivateValue(int value) {}

  private static final AtomicInteger CONSTRUCTED = new AtomicInteger();

  public record Counted(int low, int high) {
    public Counted {
      CONSTRUCTED.incrementAndGet();
    }
  }

  @Test
  void publicRecordsDeriveAllCommonScalarCodecsAndOrderedFields() throws Exception {
    var type = RecordTypes.record(Scalars.class).build();
    var value = new Scalars(true, (byte) 1, (short) 2, 3, 4L, 1.5F, 2.25, "Test", Mode.QUIET);
    var encoded = type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow();
    assertEquals(
        value, JsonInput.read(encoded.toString(), JSON).decode(type).value().orElseThrow());
    assertEquals(
        List.of("enabled", "tiny", "small", "integer", "wide", "single", "precise", "text", "mode"),
        ((ValueShape.ObjectValue) type.shape()).fields().stream().map(FieldKey::id).toList());
    assertTrue(
        JsonInput.read(encoded.toString().replace("\"tiny\":1", "\"tiny\":256"), JSON)
            .decode(type)
            .value()
            .isEmpty());
    assertTrue(
        JsonInput.read(encoded.toString().replace("\"integer\":3", "\"integer\":3.5"), JSON)
            .decode(type)
            .value()
            .isEmpty());
  }

  @Test
  void explicitSerializedIdsAndBoundsReuseTheSameRecordType() throws Exception {
    var type =
        RecordTypes.record(Counted.class)
            .field("low", FieldKey.of("minimum", ValueTypes.integer(0, 20)))
            .field("high", FieldKey.of("maximum", ValueTypes.integer(0, 20)))
            .build();
    assertEquals(
        new Counted(2, 12),
        JsonInput.read("{\"minimum\":2,\"maximum\":12}", JSON).decode(type).value().orElseThrow());
    assertEquals(
        "{\"minimum\":2,\"maximum\":12}",
        type.codec().encodeStart(JsonOps.INSTANCE, new Counted(2, 12)).getOrThrow().toString());
    CONSTRUCTED.set(0);
    for (var input :
        List.of(
            "{\"minimum\":-1,\"maximum\":12}",
            "{\"minimum\":2.5,\"maximum\":12}",
            "{\"minimum\":2}",
            "{\"minimum\":2,\"maximum\":12,\"extra\":true}"))
      assertTrue(JsonInput.read(input, JSON).decode(type).value().isEmpty());
    assertEquals(0, CONSTRUCTED.get(), "Invalid fields constructed a temporary record");
  }

  @Test
  void nestedRecordListsOwnMutableSourcesWithoutCustomSnapshotCode() {
    var type = RecordTypes.record(Profile.class).build();
    var entries =
        new ArrayList<>(
            List.of(new Scalars(false, (byte) 1, (short) 2, 3, 4L, 1, 2, "Original", Mode.LOUD)));
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:derived"), 1, type, () -> new Profile("Default", entries));
    entries.clear();
    assertEquals(1, definition.defaults().entries().size());
    assertThrows(
        UnsupportedOperationException.class, () -> definition.defaults().entries().clear());
    assertEquals(definition.defaults(), type.freeze(definition.defaults()));
  }

  @Test
  void declaredStringMapsDeriveObjectEncodingWithOwnedNestedValues() throws Exception {
    var type = RecordTypes.record(Mappings.class).build();
    var source = "{\"mappings\":{\"\":[{\"low\":2,\"high\":4}]}}";
    var value = JsonInput.read(source, JSON).decode(type).value().orElseThrow();
    assertEquals(new Mappings(Map.of("", List.of(new Counted(2, 4)))), value);
    assertEquals(source, type.codec().encodeStart(JsonOps.INSTANCE, value).getOrThrow().toString());
    var values = new ArrayList<>(List.of(new Counted(3, 6)));
    var owned = type.freeze(new Mappings(Map.of("a", values)));
    values.clear();
    assertEquals(1, owned.mappings().get("a").size());
    assertThrows(UnsupportedOperationException.class, () -> owned.mappings().clear());
    var shortKeys =
        RecordTypes.record(Mappings.class).register(String.class, ValueTypes.text(1)).build();
    assertTrue(
        JsonInput.read("{\"mappings\":{\"long\":[]}}", JSON).decode(shortKeys).value().isEmpty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordTypes.record(Mappings.class)
                .limits(new RecordTypes.Limits(2, 32, 64, 8))
                .build());
  }

  @Test
  void nonStringMapsRequireAnExplicitLosslessEncoding() throws Exception {
    var type =
        RecordTypes.record(Unsupported.class)
            .field(
                "mappings",
                FieldKey.of(
                    "mappings",
                    ValueTypes.entryMap(ValueTypes.integer(0, 10), ValueTypes.text(16), 8)))
            .build();
    assertEquals(
        new Unsupported(Map.of(2, "a")),
        JsonInput.read("{\"mappings\":[{\"key\":2,\"value\":\"a\"}]}", JSON)
            .decode(type)
            .value()
            .orElseThrow());
  }

  @Test
  void declaredSetsDeriveUniqueListEncodingAndOwnedNestedMembers() throws Exception {
    var type = RecordTypes.record(Sets.class).build();
    var parsed =
        JsonInput.read("{\"members\":[[{\"low\":1,\"high\":2}]]}", JSON)
            .decode(type)
            .value()
            .orElseThrow();
    assertEquals(new Sets(Set.of(List.of(new Counted(1, 2)))), parsed);
    assertTrue(JsonInput.read("{\"members\":[[],[]]}", JSON).decode(type).value().isEmpty());
    var child = new ArrayList<>(List.of(new Counted(2, 4)));
    var owned = type.freeze(new Sets(Set.of(child)));
    child.clear();
    assertEquals(1, owned.members().iterator().next().size());
    assertThrows(UnsupportedOperationException.class, () -> owned.members().clear());
  }

  @Test
  void privateRecordsRequireExplicitCallerAccessInsteadOfOpeningModules() throws Exception {
    assertThrows(
        IllegalArgumentException.class, () -> RecordTypes.record(PrivateValue.class).build());
    var type = RecordTypes.record(MethodHandles.lookup(), PrivateValue.class).build();
    assertEquals(
        new PrivateValue(7),
        JsonInput.read("{\"value\":7}", JSON).decode(type).value().orElseThrow());
  }

  @Test
  void unknownRecursiveAndWildcardTypesRequireExplicitBindings() {
    assertThrows(IllegalArgumentException.class, () -> RecordTypes.record(Recursive.class).build());
    assertThrows(
        IllegalArgumentException.class, () -> RecordTypes.record(Unsupported.class).build());
    assertThrows(IllegalArgumentException.class, () -> RecordTypes.record(Wildcard.class).build());
    assertThrows(IllegalArgumentException.class, () -> RecordTypes.record(Custom.class).build());
    var duration =
        ValueType.of(
            ExactCodecs.LONG.xmap(Duration::ofMillis, Duration::toMillis),
            new ValueShape.Custom("fixture:duration"),
            SnapshotPolicy.<Duration>immutable());
    var type = RecordTypes.record(Custom.class).register(Duration.class, duration).build();
    assertEquals(
        new Custom(Duration.ofMillis(25)),
        type.codec()
            .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString("{\"duration\":25}"))
            .getOrThrow());
  }

  @Test
  void declarationTyposDuplicatesAndWrongTypesDoNotBecomeSilentLastWins() {
    var field = FieldKey.of("bound", ValueTypes.integer(0, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordTypes.record(Counted.class).field("missing", field).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordTypes.record(Counted.class).field("low", field).field("low", field));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordTypes.record(Counted.class).field("low", field).field("high", field).build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordTypes.record(Counted.class)
                .register(String.class, ValueTypes.text(2))
                .register(String.class, ValueTypes.text(3)));
    var wrong =
        RecordTypes.record(Counted.class)
            .field("low", FieldKey.of("low", ValueTypes.bool()))
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DocumentDefinition<>(
                DocumentId.parse("fixture:wrong"), 1, wrong, () -> new Counted(1, 2)));
  }

  @Test
  void derivationAndDefaultCollectionLimitsAreExplicit() throws Exception {
    var tiny = new RecordTypes.Limits(4, 4, 3, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordTypes.record(Profile.class).limits(tiny).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordTypes.record(Counted.class).limits(new RecordTypes.Limits(4, 1, 3, 1)).build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordTypes.record(CustomText.class)
                .limits(new RecordTypes.Limits(2, 4, 3, 1))
                .build());
    var stringType = RecordTypes.record(CustomText.class).limits(tiny).build();
    assertTrue(
        JsonInput.read("{\"text\":\"long\",\"values\":[]}", JSON)
            .decode(stringType)
            .value()
            .isEmpty());
    assertTrue(
        JsonInput.read("{\"text\":\"ok\",\"values\":[1,2]}", JSON)
            .decode(stringType)
            .value()
            .isEmpty());
  }

  public record CustomText(String text, List<Integer> values) {}

  public record Empty() {}

  @Test
  void floatingBoundsUseTheirDeclaredFloatSpellingWithoutWideningDrift() throws Exception {
    var decimal = ValueTypes.floating(0.1F, 0.3F);
    assertTrue(decimal.codec().encodeStart(JsonOps.INSTANCE, 0.1F).isSuccess());
    assertEquals(0.1F, JsonInput.read("0.1", JSON).decode(decimal).value().orElseThrow());
    assertTrue(JsonInput.read("0.099999999", JSON).decode(decimal).value().isEmpty());
    var full = ValueTypes.floating(-Float.MAX_VALUE, Float.MAX_VALUE);
    assertTrue(full.codec().encodeStart(JsonOps.INSTANCE, Float.MAX_VALUE).isSuccess());
    assertTrue(full.codec().encodeStart(JsonOps.INSTANCE, -Float.MAX_VALUE).isSuccess());
  }

  @Test
  void zeroComponentRecordsStillSupplyACanonicalConstructorAndSnapshot() throws Exception {
    var type = RecordTypes.record(Empty.class).build();
    assertEquals(new Empty(), JsonInput.read("{}", JSON).decode(type).value().orElseThrow());
    assertEquals(new Empty(), type.freeze(new Empty()));
    assertEquals(
        "{}", type.codec().encodeStart(JsonOps.INSTANCE, new Empty()).getOrThrow().toString());
  }
}
