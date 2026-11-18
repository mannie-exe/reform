package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FormModelTest {
  private record Body(int key, Optional<List<Integer>> values) {}

  private static final ValueType<Integer> NUMBER = ValueTypes.integer(0, 100);
  private static final FieldKey<Integer> KEY = FieldKey.of("key", NUMBER);
  private static final FieldKey<Optional<List<Integer>>> VALUES =
      FieldKey.of(
          "values",
          ValueTypes.optional(
              ValueTypes.list(NUMBER, 4), ValueShape.OptionalEncoding.SINGLETON_LIST));
  private static final ValueType<Body> BODY =
      RecordType.<Body>builder(v -> new Body(v.get(KEY), v.get(VALUES)))
          .field(KEY, Body::key)
          .field(VALUES, Body::values)
          .build();

  @Test
  void recursiveMapPartsRemainDifferentFromRecordFieldNamesAndRetainRowIdentity() {
    var type = ValueTypes.objectMap(ValueTypes.text(32), BODY, 4);
    var draft =
        DraftTree.from(
            type, Map.of("role", new Body(3, Optional.of(List.of(4)))), DraftLimits.CONFIGURATION);
    var model = new FormModel(type, draft);
    var row = model.root().children().getFirst();
    assertEquals(FormModel.Role.MAP_ROW, row.role());
    var key = row.children().getFirst();
    var body = row.children().get(1);
    assertEquals(FieldPath.Part.KEY, key.path().segments().getLast());
    assertEquals(FieldPath.Part.VALUE, body.path().segments().getLast());
    assertEquals(
        new FieldPath.Field("key"), body.children().getFirst().path().segments().getLast());
    var optional = body.children().get(1);
    var sequence = optional.children().getFirst();
    assertEquals(FieldPath.Part.VALUE, sequence.path().segments().getLast());
    var leaf = sequence.children().getFirst();
    assertEquals("4", ((DraftNode.Scalar) leaf.input()).raw());
    assertEquals(leaf.input(), draft.at(leaf.path()));
    assertThrows(UnsupportedOperationException.class, () -> row.children().clear());
  }

  @Test
  void unknownEntryDataRemainsVisibleWithoutGainingADeclaredEditableType() throws Exception {
    var type = ValueTypes.entryMap(ValueTypes.text(32), NUMBER, 4);
    var draft =
        DraftTree.fromParsed(
            type,
            JsonInput.read(
                "[{\"key\":\"role\",\"value\":4,\"extra\":true}]",
                new JsonLimits(8192, 32, 1024, 512, 64, 1024)),
            DraftLimits.CONFIGURATION);
    var row = new FormModel(type, draft).root().children().getFirst();
    var unknown = row.children().getLast();
    assertEquals(FormModel.Role.UNKNOWN, unknown.role());
    assertEquals("extra", unknown.name());
    assertTrue(unknown.type().isEmpty());
    assertInstanceOf(DraftNode.Opaque.class, unknown.input());
  }

  @Test
  void layoutTracksStructureAndChoiceFlavorWithoutRebuildingForOrdinaryTyping() {
    var scalar = new DraftTree(new DraftNode.Scalar("1"), DraftLimits.CONFIGURATION);
    var typed = new DraftTree(new DraftNode.Scalar("-"), DraftLimits.CONFIGURATION);
    assertEquals(new FormModel(NUMBER, scalar).layout(), new FormModel(NUMBER, typed).layout());
    var yes = new DraftTree(new DraftNode.Scalar("true"), DraftLimits.CONFIGURATION);
    var no = new DraftTree(new DraftNode.Scalar("false"), DraftLimits.CONFIGURATION);
    assertEquals(
        new FormModel(ValueTypes.bool(), yes).layout(),
        new FormModel(ValueTypes.bool(), no).layout());
    assertNotEquals(
        new FormModel(ValueTypes.bool(), yes).layout(),
        new FormModel(ValueTypes.bool(), typed).layout());
    var type = ValueTypes.list(NUMBER, 4);
    var list = DraftTree.from(type, List.of(1, 2), DraftLimits.CONFIGURATION);
    var rows = (DraftNode.Sequence) list.root();
    var moved =
        list.change(
            FieldPath.ROOT, n -> ((DraftNode.Sequence) n).move(rows.rows().getFirst().id(), 1));
    assertNotEquals(new FormModel(type, list).layout(), new FormModel(type, moved).layout());
  }

  @Test
  void inactiveContentIsNotTraversedButMembershipAndRecoverySnapshotsAffectLayout() {
    var optional = ValueTypes.optional(NUMBER, ValueShape.OptionalEncoding.SINGLETON_LIST);
    var absent =
        new DraftTree(
            new DraftNode.OptionalValue(false, new DraftNode.Scalar("-")),
            DraftLimits.CONFIGURATION);
    var different =
        absent.change(
            FieldPath.ROOT,
            n -> ((DraftNode.OptionalValue) n).withValue(new DraftNode.Scalar("3")));
    assertEquals(
        new FormModel(optional, absent).layout(), new FormModel(optional, different).layout());
    assertTrue(new FormModel(optional, absent).root().children().isEmpty());
    var present =
        absent.change(FieldPath.ROOT, n -> ((DraftNode.OptionalValue) n).withPresent(true));
    assertNotEquals(
        new FormModel(optional, absent).layout(), new FormModel(optional, present).layout());
    var defaults = ValueTypes.defaultedField(NUMBER, 7);
    var base =
        new DraftTree(
            new DraftNode.Defaulted(false, new DraftNode.Scalar("7")), DraftLimits.CONFIGURATION);
    var retained =
        base.change(
            FieldPath.ROOT,
            n -> ((DraftNode.Defaulted) n).retainOverride(new DraftNode.Scalar("-")));
    assertNotEquals(
        new FormModel(defaults, base).layout(), new FormModel(defaults, retained).layout());
  }

  @Test
  void modelConstructionDoesNotInvokeCodecsResolversOrFactoriesAndRejectsOverBudgetExpansion() {
    var calls = new AtomicInteger();
    var type =
        ValueType.of(
            com.mojang.serialization.Codec.STRING.xmap(
                value -> {
                  calls.incrementAndGet();
                  return value;
                },
                value -> {
                  calls.incrementAndGet();
                  return value;
                }),
            ValueTypes.text(32).shape(),
            SnapshotPolicy.<String>immutable());
    var reference = ValueTypes.reference(type, DocumentId.parse("fixture:unavailable"));
    var model =
        new FormModel(
            reference,
            new DraftTree(new DraftNode.Scalar("optional:missing"), DraftLimits.CONFIGURATION));
    assertEquals(0, calls.get());
    assertEquals("optional:missing", ((DraftNode.Scalar) model.root().input()).raw());
    var tiny = new DraftLimits(8, 1, 128, 0, 0);
    var missingRecord = new DraftTree(new DraftNode.ObjectValue(Map.of()), tiny);
    assertThrows(IllegalArgumentException.class, () -> new FormModel(BODY, missingRecord));
  }

  @Test
  void opaqueWidgetKeysCannotAliasPathsWhoseHumanReadableRecordStringsCollide() {
    var flat = FieldPath.ROOT.field("a], Field[id=b");
    var nested = FieldPath.ROOT.field("a").field("b");
    assertEquals(flat.toString(), nested.toString());
    assertNotEquals(FormModel.key(flat), FormModel.key(nested));
    assertNotEquals(
        FormModel.key(FieldPath.ROOT.field("name/")),
        FormModel.key(FieldPath.ROOT.variant("name/")));
    assertEquals(FormModel.key(flat), FormModel.key(new FieldPath(flat.segments())));
  }
}
