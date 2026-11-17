package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DraftTreeTest {
  static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 256, 1024);
  static final DraftLimits DRAFT = DraftLimits.CONFIGURATION;

  record Profile(String name, int count, List<Integer> entries) {}

  static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(64));
  static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 1000));
  static final FieldKey<List<Integer>> ENTRIES =
      FieldKey.of("entries", ValueTypes.list(ValueTypes.integer(0, 1000), 16));
  static final RecordType<Profile> TYPE = type(new AtomicInteger());
  static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:profile"),
          1,
          TYPE,
          () -> new Profile("Initial", 2, List.of(10, 20)));

  static RecordType<Profile> type(AtomicInteger calls) {
    return RecordType.<Profile>builder(
            values -> {
              calls.incrementAndGet();
              return new Profile(values.get(NAME), values.get(COUNT), values.get(ENTRIES));
            })
        .field(NAME, Profile::name)
        .field(COUNT, Profile::count)
        .field(ENTRIES, Profile::entries)
        .build();
  }

  static DraftTree initial() {
    return DraftTree.from(TYPE, DEFINITION.defaults(), DRAFT);
  }

  @Test
  void incompleteInputNeverConstructsAnOwnerObject() {
    var calls = new AtomicInteger();
    var type = type(calls);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:counted"), 1, type, DEFINITION::defaults);
    var tree = DraftTree.from(type, definition.defaults(), DRAFT);
    calls.set(0);
    for (String raw : List.of("", "-", "1e+", "2.5")) {
      var next = tree.change(FieldPath.ROOT.field("count"), node -> new DraftNode.Scalar(raw));
      assertTrue(next.inspect(definition, JSON).candidate().isEmpty());
      assertEquals(raw, ((DraftNode.Scalar) next.at(FieldPath.ROOT.field("count"))).raw());
    }
    assertEquals(0, calls.get());
  }

  @Test
  void movingRowsRetainsIdentityAndSubmittedAddresses() {
    var tree = initial();
    var path = FieldPath.ROOT.field("entries");
    UUID moved = ((DraftNode.Sequence) tree.at(path)).rows().getFirst().id();
    var movedTree = tree.change(path, node -> ((DraftNode.Sequence) node).move(moved, 1));
    var edited = movedTree.change(path.row(moved), node -> new DraftNode.Scalar("15"));
    var candidate = edited.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertEquals(List.of(20, 15), candidate.value().entries());
    assertEquals(
        path.row(moved), candidate.addresses().get(SourcePath.ROOT.property("entries").index(1)));
    assertEquals("10", ((DraftNode.Scalar) tree.at(path.row(moved))).raw());
    var removed = edited.change(path, node -> ((DraftNode.Sequence) node).remove(moved));
    assertFalse(removed.contains(path.row(moved)));
    assertThrows(
        IllegalArgumentException.class,
        () -> removed.change(path.row(moved), node -> new DraftNode.Scalar("0")));
  }

  @Test
  void originalNumberSpellingSurvivesAnotherFieldEdit() throws Exception {
    var parsed =
        JsonInput.read("{\"name\":\"Original\",\"count\":1.00e2,\"entries\":[1.0E1,2.00e1]}", JSON);
    var tree =
        DraftTree.fromParsed(TYPE, parsed, DRAFT)
            .change(FieldPath.ROOT.field("name"), node -> new DraftNode.Scalar("Changed"));
    var candidate = tree.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertTrue(candidate.authoredJson().contains("1.00e2"));
    assertTrue(candidate.authoredJson().contains("[1.0E1,2.00e1]"));
    assertEquals(100, candidate.value().count());
    assertEquals("1.00e2", parsed.scalarText().get(SourcePath.ROOT.property("count")));
  }

  @Test
  void absenceNullAndEmptyTextRemainDistinct() throws Exception {
    var missing =
        DraftTree.fromParsed(TYPE, JsonInput.read("{\"count\":1,\"entries\":[]}", JSON), DRAFT);
    assertInstanceOf(DraftNode.Missing.class, missing.at(FieldPath.ROOT.field("name")));
    assertEquals(
        DraftTree.InputState.EMPTY,
        missing.inspect(DEFINITION, JSON).inputs().get(FieldPath.ROOT.field("name")));
    var explicitNull =
        DraftTree.fromParsed(
            TYPE, JsonInput.read("{\"name\":null,\"count\":1,\"entries\":[]}", JSON), DRAFT);
    assertInstanceOf(DraftNode.NullValue.class, explicitNull.at(FieldPath.ROOT.field("name")));
    assertTrue(explicitNull.inspect(DEFINITION, JSON).candidate().isEmpty());
    assertEquals(
        "",
        missing
            .change(FieldPath.ROOT.field("name"), node -> new DraftNode.Scalar(""))
            .inspect(DEFINITION, JSON)
            .candidate()
            .orElseThrow()
            .value()
            .name());
  }

  @Test
  void wrongSavedScalarKindIsNotSilentlyCoerced() throws Exception {
    var wrong =
        DraftTree.fromParsed(
            TYPE, JsonInput.read("{\"name\":123,\"count\":1,\"entries\":[]}", JSON), DRAFT);
    assertInstanceOf(DraftNode.Opaque.class, wrong.at(FieldPath.ROOT.field("name")));
    assertTrue(wrong.inspect(DEFINITION, JSON).candidate().isEmpty());
  }

  @Test
  void blankUnknownKeyIsInspectableAndRetained() throws Exception {
    var source = JsonInput.read("{\"name\":\"\",\"count\":1,\"entries\":[],\"\":2}", JSON);
    assertTrue(DEFINITION.decode(source).value().isEmpty());
    var tree = DraftTree.fromParsed(TYPE, source, DRAFT);
    assertTrue(((DraftNode.ObjectValue) tree.root()).fields().containsKey(""));
    assertTrue(tree.inspect(DEFINITION, JSON).candidate().isEmpty());
    assertTrue(
        tree.change(FieldPath.ROOT, node -> ((DraftNode.ObjectValue) node).without(""))
            .inspect(DEFINITION, JSON)
            .candidate()
            .isPresent());
  }

  @Test
  void sessionIdsAreNotAuthoredChanges() {
    assertTrue(initial().sameValue(initial()));
    var row = DraftNode.Row.create(new DraftNode.Scalar("1"));
    assertThrows(IllegalArgumentException.class, () -> new DraftNode.Sequence(List.of(row, row)));
    assertNotEquals(SourcePath.ROOT.property("0"), SourcePath.ROOT.index(0));
  }

  @Test
  void parsedSourcesAndCandidatesDoNotExposeMutableBackingState() throws Exception {
    var source =
        JsonInput.read("{\"name\":\"Original\",\"count\":1.00e2,\"entries\":[10,20]}", JSON);
    source.copyTree().getAsJsonObject().addProperty("count", 999);
    assertThrows(
        UnsupportedOperationException.class,
        () -> source.scalarText().put(SourcePath.ROOT.property("count"), "999"));
    var candidate =
        DraftTree.fromParsed(TYPE, source, DRAFT)
            .inspect(DEFINITION, JSON)
            .candidate()
            .orElseThrow();
    assertEquals(100, candidate.value().count());
    assertTrue(candidate.authoredJson().contains("1.00e2"));
    assertThrows(UnsupportedOperationException.class, () -> candidate.addresses().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> ((DraftNode.ObjectValue) candidate.draft().root()).fields().clear());
    assertThrows(UnsupportedOperationException.class, () -> candidate.value().entries().clear());
  }

  @Test
  void budgetRefusalDoesNotMutateTheOriginal() {
    var tree =
        new DraftTree(
            new DraftNode.ObjectValue(Map.of("x", new DraftNode.Scalar("short"))),
            new DraftLimits(4, 8, 10, 4, 100));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tree.change(
                FieldPath.ROOT.field("x"), node -> new DraftNode.Scalar("long".repeat(10))));
    assertEquals("short", ((DraftNode.Scalar) tree.at(FieldPath.ROOT.field("x"))).raw());
  }
}
