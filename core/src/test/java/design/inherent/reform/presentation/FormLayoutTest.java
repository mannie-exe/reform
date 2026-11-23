package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormLayoutTest {
  @Test
  void defaultedRowsAdmitTheDirectBooleanControlButNotStructuralOverrideEditors() {
    var row = new FormLayout(List.of(new FormLayout.Row("direct", List.of(A, B))), List.of());
    var a = FieldKey.of("a", ValueTypes.defaultedField(ValueTypes.bool(), true));
    var b = FieldKey.of("b", ValueTypes.bool());
    var direct =
        RecordType.<String>builder(values -> "unused")
            .field(a, value -> true)
            .field(b, value -> false)
            .build();
    assertDoesNotThrow(() -> row.requireCompatible(direct));
    var number = FieldKey.of("a", ValueTypes.defaultedField(ValueTypes.integer(0, 10), 1));
    var structural =
        RecordType.<String>builder(values -> "unused")
            .field(number, value -> 1)
            .field(b, value -> false)
            .build();
    assertThrows(IllegalArgumentException.class, () -> row.requireCompatible(structural));
    assertDoesNotThrow(
        () ->
            new FormLayout(List.of(), List.of(group("defaults", List.of(A, B))))
                .requireCompatible(structural));
  }

  private static final FieldPath A = FieldPath.ROOT.field("a");
  private static final FieldPath B = FieldPath.ROOT.field("b");
  private static final FieldPath C = FieldPath.ROOT.field("c");

  private static FormLayout.Group group(String id, List<FieldPath> fields) {
    return new FormLayout.Group(
        id, "test." + id, "Related choices", "Owner controls effects", fields);
  }

  @Test
  void relatedRowsAndBoxesKeepAuthoredOrderAndDoNotRequireNumericLeaves() {
    var row = new FormLayout.Row("placement", List.of(B, A));
    var box = group("choices", List.of(A, B));
    var layout = new FormLayout(List.of(row), List.of(box));
    var a = FieldKey.of("a", ValueTypes.bool());
    var b = FieldKey.of("b", ValueTypes.text(20));
    var type =
        RecordType.<String>builder(values -> "unused")
            .field(a, value -> true)
            .field(b, value -> "text")
            .build();
    layout.requireCompatible(type);
    assertEquals(List.of(B, A), layout.row(A).orElseThrow().fields());
    assertEquals(box, layout.group(B).orElseThrow());
    var presentation = FormPresentation.EMPTY.withLayout(layout);
    assertSame(
        layout,
        presentation.withCatalog(FormCatalog.NONE).withUnavailableFields(List.of()).layout());
    assertThrows(UnsupportedOperationException.class, () -> layout.rows().clear());
  }

  @Test
  void ambiguousCrossGroupAndDuplicateRelationshipsRefuseBeforeRendering() {
    var row = new FormLayout.Row("placement", List.of(A, B));
    assertThrows(IllegalArgumentException.class, () -> new FormLayout.Row("one", List.of(A)));
    assertThrows(IllegalArgumentException.class, () -> new FormLayout.Row("same", List.of(A, A)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FormLayout.Row("nested", List.of(A, B.field("child"))));
    assertThrows(
        IllegalArgumentException.class, () -> new FormLayout(List.of(row, row), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FormLayout(List.of(row), List.of(group("half", List.of(A)))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormLayout(
                List.of(), List.of(group("first", List.of(A)), group("second", List.of(A)))));
    var range = new FormPresentation.Range("bounds", "test.bounds", "Bounds", "", A, B);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormPresentation(List.of(range))
                .withLayout(new FormLayout(List.of(row), List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormPresentation(List.of(range))
                .withLayout(new FormLayout(List.of(), List.of(group("half", List.of(A))))));
    assertDoesNotThrow(
        () ->
            new FormPresentation(List.of(range))
                .withLayout(new FormLayout(List.of(), List.of(group("whole", List.of(A, B))))));
  }

  @Test
  void undeclaredAndStructuredRowsDoNotSilentlyGainLeafControls() {
    var a = FieldKey.of("a", ValueTypes.bool());
    var b = FieldKey.of("b", ValueTypes.list(ValueTypes.text(20), 10));
    var type =
        RecordType.<String>builder(values -> "unused")
            .field(a, value -> true)
            .field(b, value -> List.of())
            .build();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormLayout(List.of(new FormLayout.Row("structured", List.of(A, B))), List.of())
                .requireCompatible(type));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormLayout(List.of(), List.of(group("missing", List.of(C))))
                .requireCompatible(type));
    assertDoesNotThrow(
        () ->
            new FormLayout(List.of(), List.of(group("subtree", List.of(B))))
                .requireCompatible(type));
  }
}
