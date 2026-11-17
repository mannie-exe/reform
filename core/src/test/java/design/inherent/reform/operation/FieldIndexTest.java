package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldIndexTest {
  @Test
  void selectorsDisambiguateNativeNamesAndNeverInterpretUndeclaredPaths() {
    var nestedKey = FieldKey.of("b", ValueTypes.integer(0, 10));
    var nested =
        RecordType.<Integer>builder(v -> v.get(nestedKey)).field(nestedKey, v -> v).build();
    var nestedField = FieldKey.of("a", nested);
    var literal = FieldKey.of("a.b", ValueTypes.integer(0, 10));
    var dollar = FieldKey.of("$", ValueTypes.integer(0, 10));
    var control = FieldKey.of("line\nfeed", ValueTypes.integer(0, 10));
    var escaped = FieldKey.of("line\\u000afeed", ValueTypes.integer(0, 10));
    var list = FieldKey.of("rows", ValueTypes.list(ValueTypes.integer(0, 10), 4));
    var type =
        RecordType.<List<Integer>>builder(
                v ->
                    List.of(
                        v.get(nestedField),
                        v.get(literal),
                        v.get(dollar),
                        v.get(control),
                        v.get(escaped)))
            .field(nestedField, v -> v.get(0))
            .field(literal, v -> v.get(1))
            .field(dollar, v -> v.get(2))
            .field(control, v -> v.get(3))
            .field(escaped, v -> v.get(4))
            .field(list, v -> List.of())
            .build();
    var index = new FieldIndex(type, 8, 2);
    assertEquals(FieldPath.ROOT.field("a").field("b"), index.field("a.b").orElseThrow().path());
    assertEquals(FieldPath.ROOT.field("a.b"), index.field("a\\.b").orElseThrow().path());
    assertEquals(FieldPath.ROOT.field("$"), index.field("\\$").orElseThrow().path());
    assertEquals(
        FieldPath.ROOT.field("line\nfeed"), index.field("line\\u000afeed").orElseThrow().path());
    assertEquals(
        FieldPath.ROOT.field("line\\u000afeed"),
        index.field("line\\\\u000afeed").orElseThrow().path());
    assertFalse(index.field("rows").orElseThrow().scalar());
    assertTrue(index.field("rows[0]").isEmpty());
    assertTrue(index.field("a.missing").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> index.selectors().clear());
    assertThrows(IllegalArgumentException.class, () -> new FieldIndex(type, 7, 2));
    assertThrows(IllegalArgumentException.class, () -> new FieldIndex(type, 8, 1));
    assertEquals(
        FieldPath.ROOT,
        new FieldIndex(ValueTypes.integer(0, 10), 1, 1).field("$").orElseThrow().path());
  }
}
