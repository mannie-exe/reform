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
import org.junit.jupiter.api.Test;

class DefaultedDraftTest {
  @Test
  void disablingAnOverrideMakesItsOldSourceDiagnosticStaleDespiteRetainedRawInput()
      throws Exception {
    try (var session = session("{\"name\":\"a\",\"height\":4}")) {
      var submission = session.prepareSubmission().submission().orElseThrow();
      var source = SourcePath.ROOT.property("height");
      assertEquals(EditSession.TargetState.CURRENT, session.locate(submission, source).state());
      session.edit(HEIGHT_PATH, n -> ((DraftNode.Defaulted) n).withOverride(false), "Use default");
      assertEquals(EditSession.TargetState.STALE_VALUE, session.locate(submission, source).state());
      assertEquals("4", ((DraftNode.Scalar) session.snapshot().draft().at(HEIGHT_VALUE)).raw());
      session.undo();
      assertEquals(EditSession.TargetState.CURRENT, session.locate(submission, source).state());
    }
  }

  @Test
  void materializedDefaultRetainsInvalidInactiveInputWithoutChangingSavedMeaning()
      throws Exception {
    try (var session = session("{\"name\":\"a\"}")) {
      session.setRaw(HEIGHT_VALUE, "-");
      session.setRaw(FieldPath.ROOT.field("name"), "b");
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submitted);
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submitted, draft("{\"name\":\"b\",\"height\":4}"), "two"));
      var current = (DraftNode.Defaulted) session.snapshot().draft().at(HEIGHT_PATH);
      assertTrue(current.overridden());
      assertEquals("4", ((DraftNode.Scalar) current.value()).raw());
      assertEquals("-", ((DraftNode.Scalar) current.retainedOverrides().getFirst().value()).raw());
      assertEquals(4, session.snapshot().inspect().candidate().orElseThrow().value().height());
      assertFalse(session.dirty());
      var id = current.retainedOverrides().getFirst().id();
      session.edit(
          HEIGHT_PATH,
          n -> ((DraftNode.Defaulted) n).restoreRetained(id),
          "Recover inactive input");
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ((DraftNode.Defaulted) session.snapshot().draft().at(HEIGHT_PATH))
                  .restoreRetained(id));
      session.undo();
      assertFalse(session.dirty());
    }
  }

  @Test
  void materializedCollectionDoesNotClaimDifferentInactiveRowsAsSavedRows() throws Exception {
    try (var session = session("{\"name\":\"a\"}")) {
      var field = FieldPath.ROOT.field("values");
      var contents = field.append(FieldPath.Part.VALUE);
      var before = ((DraftNode.Sequence) session.snapshot().draft().at(contents)).rows();
      session.setRaw(contents.row(before.getFirst().id()), "9");
      session.setRaw(FieldPath.ROOT.field("name"), "b");
      var submitted = session.prepareSubmission().submission().orElseThrow();
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submitted, draft("{\"name\":\"b\",\"values\":[1,2]}"), "two"));
      var current = (DraftNode.Defaulted) session.snapshot().draft().at(field);
      var actual = ((DraftNode.Sequence) current.value()).rows();
      assertTrue(
          java.util.Collections.disjoint(
              before.stream().map(DraftNode.Row::id).toList(),
              actual.stream().map(DraftNode.Row::id).toList()));
      var retained = current.retainedOverrides().getFirst();
      assertEquals(
          before.stream().map(DraftNode.Row::id).toList(),
          ((DraftNode.Sequence) retained.value()).rows().stream().map(DraftNode.Row::id).toList());
      assertEquals(
          List.of(1, 2), session.snapshot().inspect().candidate().orElseThrow().value().values());
      session.edit(
          field,
          n -> ((DraftNode.Defaulted) n).restoreRetained(retained.id()),
          "Restore inactive collection");
      assertEquals(
          List.of(9, 2), session.snapshot().inspect().candidate().orElseThrow().value().values());
    }
  }

  @Test
  void retainedOverridesAreStableDeduplicatedAndBudgetedPrivateState() {
    var initial = new DraftNode.Defaulted(true, new DraftNode.Scalar("4"));
    var previous = new DraftNode.Scalar("7");
    var retained = initial.retainOverride(previous);
    assertEquals(retained, retained.retainOverride(previous));
    assertTrue(DraftNode.sameValue(initial, retained));
    var id = retained.retainedOverrides().getFirst().id();
    assertTrue(retained.discardRetained(id).retainedOverrides().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> retained.discardRetained(UUID.randomUUID()));
    var tree = new DraftTree(initial, new DraftLimits(8, 32, 2, 4, 2048));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tree.change(
                FieldPath.ROOT,
                n -> ((DraftNode.Defaulted) n).retainOverride(new DraftNode.Scalar("too long"))));
    assertTrue(((DraftNode.Defaulted) tree.root()).retainedOverrides().isEmpty());
  }

  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);

  record Profile(String name, int height, List<Integer> values) {}

  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(32));
  private static final FieldKey<Integer> HEIGHT =
      FieldKey.of("height", ValueTypes.defaultedField(ValueTypes.integer(0, 100), 4));
  private static final FieldKey<List<Integer>> VALUES =
      FieldKey.of(
          "values",
          ValueTypes.defaultedField(ValueTypes.list(ValueTypes.integer(0, 100), 8), List.of(1, 2)));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(v -> new Profile(v.get(NAME), v.get(HEIGHT), v.get(VALUES)))
          .field(NAME, Profile::name)
          .field(HEIGHT, Profile::height)
          .field(VALUES, Profile::values)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:defaulted"), 1, TYPE, () -> new Profile("a", 4, List.of(1, 2)));
  private static final FieldPath HEIGHT_PATH = FieldPath.ROOT.field("height"),
      HEIGHT_VALUE = HEIGHT_PATH.append(FieldPath.Part.VALUE);

  private static DraftTree draft(String source) throws Exception {
    return DraftTree.fromParsed(TYPE, JsonInput.read(source, JSON), DraftLimits.CONFIGURATION);
  }

  private static EditSession<Profile> session(String source) throws Exception {
    return EditSession.fromSource(
        DEFINITION,
        UUID.randomUUID(),
        "one",
        JsonInput.read(source, JSON),
        JSON,
        DraftLimits.CONFIGURATION);
  }

  @Test
  void sourceOmissionAndExplicitOverrideRetainDifferentAuthoredStates() throws Exception {
    var implicit = draft("{\"name\":\"a\"}");
    assertFalse(((DraftNode.Defaulted) implicit.at(HEIGHT_PATH)).overridden());
    assertEquals("4", ((DraftNode.Scalar) implicit.at(HEIGHT_VALUE)).raw());
    var candidate = implicit.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertEquals("{\"name\":\"a\"}", candidate.authoredJson());
    assertEquals(new Profile("a", 4, List.of(1, 2)), candidate.value());
    assertFalse(candidate.addresses().containsKey(SourcePath.ROOT.property("height")));
    var explicit = draft("{\"name\":\"a\",\"height\":4.00}");
    assertTrue(((DraftNode.Defaulted) explicit.at(HEIGHT_PATH)).overridden());
    assertFalse(implicit.sameValue(explicit));
    var written = explicit.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertTrue(written.authoredJson().contains("4.00"));
    assertEquals(HEIGHT_VALUE, written.addresses().get(SourcePath.ROOT.property("height")));
    assertFalse(explicit.contains(HEIGHT_PATH.field("value")));
    assertFalse(explicit.contains(HEIGHT_PATH.append(FieldPath.Part.KEY)));
  }

  @Test
  void invalidOverrideStaysLocalWhileUsingDefaultAndReturnsWhenReenabled() throws Exception {
    try (var session = session("{\"name\":\"a\"}")) {
      session.edit(HEIGHT_PATH, n -> ((DraftNode.Defaulted) n).withOverride(true), "Override");
      session.setRaw(HEIGHT_VALUE, "-");
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      session.edit(HEIGHT_PATH, n -> ((DraftNode.Defaulted) n).withOverride(false), "Use default");
      assertFalse(session.dirty());
      assertEquals(4, session.snapshot().inspect().candidate().orElseThrow().value().height());
      assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(HEIGHT_VALUE)).raw());
      session.edit(
          HEIGHT_PATH, n -> ((DraftNode.Defaulted) n).withOverride(true), "Override again");
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      session.undo();
      assertFalse(session.dirty());
    }
  }

  @Test
  void explicitNullAndMissingRequiredFieldsDoNotUseRecoveryDefaults() throws Exception {
    var source = draft("{\"name\":\"a\",\"height\":null}");
    assertTrue(((DraftNode.Defaulted) source.at(HEIGHT_PATH)).overridden());
    assertInstanceOf(DraftNode.NullValue.class, source.at(HEIGHT_VALUE));
    assertTrue(source.inspect(DEFINITION, JSON).candidate().isEmpty());
    assertTrue(draft("{}").inspect(DEFINITION, JSON).candidate().isEmpty());
  }

  @Test
  void nativeStyleDefaultOmissionAcknowledgesWithoutErasingLaterOverrides() throws Exception {
    try (var session = session("{\"name\":\"a\",\"height\":4.00}")) {
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submitted);
      session.setRaw(HEIGHT_VALUE, "7");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submitted, draft("{\"name\":\"a\"}"), "two"));
      assertFalse(((DraftNode.Defaulted) session.baseline().at(HEIGHT_PATH)).overridden());
      assertEquals(7, session.snapshot().inspect().candidate().orElseThrow().value().height());
      session.undo();
      assertFalse(session.dirty());
      session.redo();
      assertEquals(7, session.snapshot().inspect().candidate().orElseThrow().value().height());
    }
  }

  @Test
  void inactiveInputAndRowsSurviveAnUnrelatedSaveButNeverEnterItsSource() throws Exception {
    try (var session = session("{\"name\":\"a\"}")) {
      session.setRaw(HEIGHT_VALUE, "7");
      var values = FieldPath.ROOT.field("values").append(FieldPath.Part.VALUE);
      var row = ((DraftNode.Sequence) session.snapshot().draft().at(values)).rows().getFirst();
      session.setRaw(values.row(row.id()), "9");
      session.setRaw(FieldPath.ROOT.field("name"), "b");
      var submitted = session.prepareSubmission().submission().orElseThrow();
      assertFalse(submitted.candidate().authoredJson().contains("height"));
      assertFalse(submitted.candidate().authoredJson().contains("values"));
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submitted, draft("{\"name\":\"b\"}"), "two"));
      assertEquals("7", ((DraftNode.Scalar) session.snapshot().draft().at(HEIGHT_VALUE)).raw());
      assertEquals(
          "9", ((DraftNode.Scalar) session.snapshot().draft().at(values.row(row.id()))).raw());
      assertFalse(session.dirty());
      session.edit(
          HEIGHT_PATH, n -> ((DraftNode.Defaulted) n).withOverride(true), "Use retained override");
      assertEquals(7, session.snapshot().inspect().candidate().orElseThrow().value().height());
      assertEquals(
          List.of(1, 2), session.snapshot().inspect().candidate().orElseThrow().value().values());
    }
  }

  @Test
  void entryMapsRespectDefaultedKeyAndValueFields() throws Exception {
    var type =
        ValueTypes.entryMap(
            ValueTypes.defaultedField(ValueTypes.integer(0, 100), 7),
            ValueTypes.defaultedField(ValueTypes.text(16), "default"),
            8);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:defaulted_entries"), 1, type, Map::of);
    var tree = DraftTree.fromParsed(type, JsonInput.read("[{}]", JSON), DraftLimits.CONFIGURATION);
    var candidate = tree.inspect(definition, JSON).candidate().orElseThrow();
    assertEquals(Map.of(7, "default"), candidate.value());
    assertEquals("[{}]", candidate.authoredJson());
    assertFalse(candidate.addresses().containsKey(SourcePath.ROOT.index(0).property("key")));
    var entry = ((DraftNode.Mapping) tree.root()).rows().getFirst();
    var path = FieldPath.ROOT.row(entry.id()).append(FieldPath.Part.VALUE);
    var changed =
        tree.change(path, n -> ((DraftNode.Defaulted) n).withOverride(true))
            .change(path.append(FieldPath.Part.VALUE), n -> new DraftNode.Scalar("new"));
    assertEquals(
        Map.of(7, "new"), changed.inspect(definition, JSON).candidate().orElseThrow().value());
    var duplicate =
        DraftTree.fromParsed(type, JsonInput.read("[{},{}]", JSON), DraftLimits.CONFIGURATION)
            .inspect(definition, JSON);
    assertTrue(duplicate.candidate().isEmpty());
    assertTrue(
        duplicate.report().issues().stream()
            .anyMatch(
                issue -> issue.code().equals("input.duplicate_key") && !issue.related().isEmpty()));
  }

  @Test
  void omittedDefaultsHaveNoStandaloneOrArraySlotRepresentation() {
    var type = ValueTypes.defaultedField(ValueTypes.integer(0, 100), 4);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:standalone_default"), 1, type, () -> 4);
    var tree = DraftTree.from(type, 4, DraftLimits.CONFIGURATION);
    assertTrue(tree.inspect(definition, JSON).candidate().isPresent());
    var omitted = tree.change(FieldPath.ROOT, n -> ((DraftNode.Defaulted) n).withOverride(false));
    assertTrue(omitted.inspect(definition, JSON).candidate().isEmpty());
    var listType = ValueTypes.list(type, 8);
    var listDefinition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:array_default"), 1, listType, () -> List.of(4));
    var list = DraftTree.from(listType, List.of(4), DraftLimits.CONFIGURATION);
    var row = ((DraftNode.Sequence) list.root()).rows().getFirst();
    assertTrue(
        list.change(
                FieldPath.ROOT.row(row.id()), n -> ((DraftNode.Defaulted) n).withOverride(false))
            .inspect(listDefinition, JSON)
            .candidate()
            .isEmpty());
  }

  @Test
  void defaultedCollectionsKeepKnownRowIdentityAndWholePresenceConflictChoices() throws Exception {
    try (var session = session("{\"name\":\"a\",\"values\":[1.00,2]}")) {
      var field = FieldPath.ROOT.field("values");
      var contents = field.append(FieldPath.Part.VALUE);
      var before = ((DraftNode.Sequence) session.snapshot().draft().at(contents)).rows();
      var submitted = session.prepareSubmission().submission().orElseThrow();
      var observed = draft("{\"name\":\"a\"}");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submitted, observed, "two"));
      assertEquals(
          before.stream().map(DraftNode.Row::id).toList(),
          ((DraftNode.Sequence) session.baseline().at(contents))
              .rows().stream().map(DraftNode.Row::id).toList());
      session.edit(field, n -> ((DraftNode.Defaulted) n).withOverride(true), "Override collection");
      session.setRaw(contents.row(before.getFirst().id()), "9");
      var review = session.reviewConflict(draft("{\"name\":\"a\",\"values\":[3,4]}"), "three");
      assertEquals(
          List.of(field), review.entries().stream().map(ConflictReview.Entry::path).toList());
      session.resolve(review, Map.of(field, ConflictReview.Choice.DRAFT));
      assertEquals(
          List.of(9, 2), session.snapshot().inspect().candidate().orElseThrow().value().values());
    }
  }
}
