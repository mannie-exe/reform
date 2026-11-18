package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FormPresentationTest {
  private record Pair(int minimum, int maximum) {}

  private static final FieldPath MINIMUM = FieldPath.ROOT.field("minimum");
  private static final FieldPath MAXIMUM = FieldPath.ROOT.field("maximum");

  private static FormPresentation.Range range(String id, FieldPath a, FieldPath b) {
    return new FormPresentation.Range(
        id, "fixture.range." + id, "Bounds", "Owner validates order", a, b);
  }

  private static ValueType<Pair> type(ValueType<Integer> number) {
    var min = FieldKey.of("minimum", number);
    var max = FieldKey.of("maximum", number);
    return RecordType.<Pair>builder(v -> new Pair(v.get(min), v.get(max)))
        .field(min, Pair::minimum)
        .field(max, Pair::maximum)
        .build();
  }

  @Test
  void relatedFieldsRetainTheirActualIdentityAndDoNotAddImplicitValidationOrOrdering() {
    var pair = range("limits", MINIMUM, MAXIMUM);
    var ranges = new ArrayList<>(List.of(pair));
    var presentation = new FormPresentation(ranges);
    ranges.clear();
    assertEquals(List.of(pair), presentation.ranges());
    assertEquals(Optional.of(pair), presentation.range(MINIMUM));
    assertEquals(Optional.of(pair), presentation.range(MAXIMUM));
    assertTrue(presentation.range(FieldPath.ROOT.field("other")).isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> presentation.ranges().clear());
    var type = type(ValueTypes.integer(0, 100));
    presentation.requireCompatible(type);
    assertEquals(
        new Pair(50, 10),
        type.codec()
            .parse(
                com.mojang.serialization.JsonOps.INSTANCE,
                com.google.gson.JsonParser.parseString("{\"minimum\":50,\"maximum\":10}"))
            .getOrThrow());
  }

  @Test
  void metadataAdmissionRunsNoCodecSnapshotOrValueConstructor() {
    var called = new AtomicInteger();
    var number =
        new ValueType<Integer>() {
          @Override
          public Codec<Integer> codec() {
            called.incrementAndGet();
            throw new AssertionError("Codec invoked");
          }

          @Override
          public ValueShape shape() {
            return ValueTypes.integer(0, 100).shape();
          }

          @Override
          public SnapshotPolicy<Integer> snapshotPolicy() {
            called.incrementAndGet();
            throw new AssertionError("Snapshot invoked");
          }
        };
    var min = FieldKey.of("minimum", number);
    var max = FieldKey.of("maximum", number);
    var root =
        RecordType.<Pair>builder(
                v -> {
                  called.incrementAndGet();
                  throw new AssertionError("Value constructed");
                })
            .field(min, Pair::minimum)
            .field(max, Pair::maximum)
            .build();
    new FormPresentation(List.of(range("limits", MINIMUM, MAXIMUM))).requireCompatible(root);
    assertEquals(0, called.get());
  }

  @Test
  void conflictingDuplicateOrNonSiblingRelationshipsRefuseBeforeUse() {
    var limits = range("limits", MINIMUM, MAXIMUM);
    assertThrows(IllegalArgumentException.class, () -> range("same", MINIMUM, MINIMUM));
    assertThrows(
        IllegalArgumentException.class,
        () -> range("different", MINIMUM, FieldPath.ROOT.field("nested").field("max")));
    assertThrows(
        IllegalArgumentException.class, () -> new FormPresentation(List.of(limits, limits)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FormPresentation(
                List.of(limits, range("other", MAXIMUM, FieldPath.ROOT.field("third")))));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FormPresentation.Range("bad space", "key", "Bounds", "", MINIMUM, MAXIMUM));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FormPresentation(java.util.Collections.nCopies(129, limits)));
  }

  @Test
  void undeclaredAndNonNumericEndpointsNeverGainARangeEditor() {
    var presentation = new FormPresentation(List.of(range("limits", MINIMUM, MAXIMUM)));
    var empty = RecordType.<String>builder(v -> "empty").build();
    assertThrows(IllegalArgumentException.class, () -> presentation.requireCompatible(empty));
    var min = FieldKey.of("minimum", ValueTypes.text(32));
    var max = FieldKey.of("maximum", ValueTypes.integer(0, 100));
    var root =
        RecordType.<String>builder(v -> "unused")
            .field(min, v -> "text")
            .field(max, v -> 1)
            .build();
    assertThrows(IllegalArgumentException.class, () -> presentation.requireCompatible(root));
    assertDoesNotThrow(() -> FormPresentation.EMPTY.requireCompatible(root));
  }

  @Test
  void declaredPathInspectionDoesNotGrantAccessToAnInactiveDraftPayload() {
    var root =
        ValueTypes.optional(
            type(ValueTypes.integer(0, 100)), ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var path = FieldPath.ROOT.append(FieldPath.Part.VALUE).field("minimum");
    assertEquals(
        ValueShape.ScalarKind.INTEGER,
        ((ValueShape.Scalar) DraftTypes.declaredAt(root, path).shape()).kind());
    var draft = DraftTree.from(root, Optional.empty(), DraftLimits.CONFIGURATION);
    assertThrows(IllegalArgumentException.class, () -> DraftTypes.at(root, draft, path));
  }
}
