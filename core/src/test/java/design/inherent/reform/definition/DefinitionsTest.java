package design.inherent.reform.definition;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefinitionsTest {
  @Test
  void completeEncodingRetainsNativeCodecLifecycle() {
    var type =
        ValueType.of(
            Codec.INT.withLifecycle(com.mojang.serialization.Lifecycle.stable()),
            new ValueShape.Custom("fixture:lifecycle"),
            SnapshotPolicy.<Integer>immutable());
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:lifecycle"), 1, type, () -> 1);
    assertEquals(com.mojang.serialization.Lifecycle.stable(), definition.encode(2).lifecycle());
  }

  @Test
  void encodeValidatesAndEncodesOneFrozenValueWithoutRepeatingCallbacks() {
    var calls = new AtomicInteger();
    var codec =
        Codec.INT.flatXmap(
            DataResult::success,
            value -> {
              calls.incrementAndGet();
              return DataResult.success(value);
            });
    var type =
        ValueType.of(
            codec, new ValueShape.Custom("fixture:integer"), SnapshotPolicy.<Integer>immutable());
    var definition = new DocumentDefinition<>(DocumentId.parse("fixture:single"), 1, type, () -> 1);
    calls.set(0);
    assertEquals("2", definition.encode(2).getOrThrow().toString());
    assertEquals(1, calls.get());
  }

  @Test
  void failingRequiredValidatorIsUnavailableRatherThanSuccessful() {
    var fail = new AtomicBoolean();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:failure"),
            1,
            ValueTypes.bool(),
            () -> false,
            value -> {
              if (fail.get()) throw new IllegalStateException("fixture failure");
              return ValidationReport.VALID;
            });
    fail.set(true);
    var result = definition.validate(true);
    assertTrue(result.value().isEmpty());
    assertFalse(result.report().complete());
    assertEquals("validation.unavailable", result.report().issues().getFirst().code());
    assertTrue(definition.encode(true).isError());
  }

  private static final JsonLimits JSON = new JsonLimits(8192, 16, 512, 256, 128, 1024);

  private record Range(int low, int high) {}

  private static final FieldKey<Integer> LOW = FieldKey.of("minimum", ValueTypes.integer(0, 100));
  private static final FieldKey<Integer> HIGH = FieldKey.of("maximum", ValueTypes.integer(0, 100));
  private static final RecordType<Range> RANGE =
      RecordType.<Range>builder(values -> new Range(values.get(LOW), values.get(HIGH)))
          .field(LOW, Range::low)
          .field(HIGH, Range::high)
          .build();

  @Test
  void oneDeclarationSuppliesCodecShapeAndStableSerializedIds() throws Exception {
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:ranges"), 1, RANGE, () -> new Range(2, 12));
    var result = definition.decode(JsonInput.read("{\"minimum\":4,\"maximum\":15}", JSON));
    assertEquals(new Range(4, 15), result.value().orElseThrow());
    assertEquals(
        "{\"minimum\":4,\"maximum\":15}",
        definition.encode(new Range(4, 15)).getOrThrow().toString());
    assertEquals(List.of(LOW, HIGH), ((ValueShape.ObjectValue) RANGE.shape()).fields());
    assertEquals("fixture:ranges", definition.id().toString());
  }

  @Test
  void absentUnknownAndFractionalFieldsNeverConstructAnOwnerObject() throws Exception {
    var calls = new AtomicInteger();
    var type =
        RecordType.<Range>builder(
                values -> {
                  calls.incrementAndGet();
                  return new Range(values.get(LOW), values.get(HIGH));
                })
            .field(LOW, Range::low)
            .field(HIGH, Range::high)
            .build();
    for (String text :
        List.of(
            "{\"minimum\":3}",
            "{\"minimum\":3,\"maximum\":4,\"extra\":0}",
            "{\"minimum\":3.5,\"maximum\":4}")) {
      assertTrue(JsonInput.read(text, JSON).decode(type).value().isEmpty());
    }
    assertEquals(0, calls.get());
    var issues =
        JsonInput.read("{\"minimum\":-1,\"maximum\":4}", JSON).decode(type).report().issues();
    assertTrue(
        issues.stream().anyMatch(issue -> issue.path().equals(FieldPath.ROOT.field("minimum"))));
  }

  @Test
  void completeCrossFieldDiagnosticsRetainRelatedLocations() throws Exception {
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:ranges"),
            1,
            RANGE,
            () -> new Range(2, 12),
            range ->
                range.low() <= range.high()
                    ? ValidationReport.VALID
                    : new ValidationReport(
                        List.of(
                            new Issue(
                                "range.order",
                                Issue.Severity.ERROR,
                                FieldPath.ROOT.field("minimum"),
                                List.of(FieldPath.ROOT.field("maximum")),
                                Map.of(),
                                "Minimum exceeds maximum")),
                        true));
    var result = definition.decode(JsonInput.read("{\"minimum\":15,\"maximum\":4}", JSON));
    assertTrue(result.value().isEmpty());
    assertEquals("range.order", result.report().issues().getFirst().code());
    assertEquals(
        List.of(FieldPath.ROOT.field("maximum")), result.report().issues().getFirst().related());
    assertTrue(definition.encode(new Range(15, 4)).isError());
  }

  private record Groups(List<List<String>> groups) {}

  @Test
  void defaultsAndNestedCollectionsDeeplyOwnTheirInput() {
    var key = FieldKey.of("groups", ValueTypes.list(ValueTypes.list(ValueTypes.text(32), 8), 8));
    var type =
        RecordType.<Groups>builder(values -> new Groups(values.get(key)))
            .field(key, Groups::groups)
            .build();
    var inner = new ArrayList<>(List.of("original"));
    var outer = new ArrayList<List<String>>(List.of(inner));
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:groups"), 1, type, () -> new Groups(outer));
    inner.set(0, "mutated");
    outer.clear();
    assertEquals(List.of(List.of("original")), definition.defaults().groups());
    assertThrows(
        UnsupportedOperationException.class,
        () -> definition.defaults().groups().getFirst().clear());
  }

  @Test
  void definitionsRefuseInvalidDefaultsAndDuplicateRegistration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DocumentDefinition<>(
                DocumentId.parse("fixture:ranges"), 1, RANGE, () -> new Range(-1, 5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RecordType.<Range>builder(values -> new Range(0, 0))
                .field(LOW, Range::low)
                .field(FieldKey.of("minimum", ValueTypes.bool()), value -> true));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DocumentDefinition<>(
                DocumentId.parse("fixture:ranges"), 0, RANGE, () -> new Range(0, 5)));
  }

  @Test
  void rawNumericConstraintsRunBeforeFloatingPointRounding() throws Exception {
    var type = ValueTypes.decimal(0, 0.1);
    assertTrue(JsonInput.read("0.1000000000000000000001", JSON).decode(type).value().isEmpty());
    assertEquals(0.1, JsonInput.read("0.1", JSON).decode(type).value().orElseThrow());
  }
}
