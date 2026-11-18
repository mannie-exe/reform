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
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OptionalDraftTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);
  private static final FieldPath OPTION = FieldPath.ROOT.field("height");
  private static final FieldPath CONTENT = OPTION.append(FieldPath.Part.VALUE);
  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(32));
  private static final FieldKey<Optional<Integer>> HEIGHT =
      FieldKey.of(
          "height",
          ValueTypes.optional(
              ValueTypes.integer(0, 100), ValueShape.OptionalEncoding.NULL_OR_VALUE));

  private record Profile(String name, Optional<Integer> height) {}

  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(v -> new Profile(v.get(NAME), v.get(HEIGHT)))
          .field(NAME, Profile::name)
          .field(HEIGHT, Profile::height)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:optional"), 1, TYPE, () -> new Profile("a", Optional.empty()));

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
  void oldSourceDiagnosticsBecomeStaleWhenTheirPresentValueIsDisabled() throws Exception {
    try (var session = session("{\"name\":\"a\",\"height\":7}")) {
      var submission = session.prepareSubmission().submission().orElseThrow();
      var source = SourcePath.ROOT.property("height");
      assertEquals(EditSession.TargetState.CURRENT, session.locate(submission, source).state());
      session.edit(OPTION, n -> ((DraftNode.OptionalValue) n).withPresent(false), "Clear");
      assertEquals(EditSession.TargetState.STALE_VALUE, session.locate(submission, source).state());
      assertEquals(CONTENT, session.locate(submission, source).path());
      session.undo();
      assertEquals(EditSession.TargetState.CURRENT, session.locate(submission, source).state());
    }
  }

  @Test
  void explicitEmptyIsValidButMissingRequiredFieldAndMalformedPresentValueRemainRepairable()
      throws Exception {
    var empty = draft("{\"name\":\"a\",\"height\":null}");
    assertFalse(((DraftNode.OptionalValue) empty.at(OPTION)).present());
    assertEquals(
        new Profile("a", Optional.empty()),
        empty.inspect(DEFINITION, JSON).candidate().orElseThrow().value());
    assertTrue(draft("{\"name\":\"a\"}").inspect(DEFINITION, JSON).candidate().isEmpty());
    var present = draft("{\"name\":\"a\",\"height\":4.00}");
    assertEquals("4.00", ((DraftNode.Scalar) present.at(CONTENT)).raw());
    var candidate = present.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertEquals(CONTENT, candidate.addresses().get(SourcePath.ROOT.property("height")));
    assertFalse(candidate.addresses().containsKey(SourcePath.ROOT.property("height").index(0)));
    var invalid = draft("{\"name\":\"a\",\"height\":\"wrong\"}");
    assertInstanceOf(DraftNode.Opaque.class, invalid.at(CONTENT));
    assertTrue(invalid.inspect(DEFINITION, JSON).candidate().isEmpty());
  }

  @Test
  void enablingAnEmptyOptionalRequiresAnAuthoredChildInsteadOfAnInventedDefault() throws Exception {
    try (var session = session("{\"name\":\"a\",\"height\":null}")) {
      session.edit(OPTION, n -> ((DraftNode.OptionalValue) n).withPresent(true), "Enable");
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertEquals(DraftTree.InputState.EMPTY, session.snapshot().inspect().inputs().get(CONTENT));
      session.setRaw(CONTENT, "7");
      assertEquals(
          Optional.of(7), session.snapshot().inspect().candidate().orElseThrow().value().height());
      session.undo();
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      session.undo();
      assertFalse(session.dirty());
      session.redo();
      session.redo();
      assertEquals(
          Optional.of(7), session.snapshot().inspect().candidate().orElseThrow().value().height());
    }
  }

  @Test
  void inactiveInvalidInputSurvivesUnrelatedSaveAndLaterMembershipChanges() throws Exception {
    try (var session = session("{\"name\":\"a\",\"height\":7}")) {
      session.setRaw(CONTENT, "-");
      session.edit(OPTION, n -> ((DraftNode.OptionalValue) n).withPresent(false), "Clear");
      session.setRaw(FieldPath.ROOT.field("name"), "b");
      var submission = session.prepareSubmission().submission().orElseThrow();
      assertTrue(submission.candidate().authoredJson().contains("\"height\":null"));
      session.markInFlight(submission);
      session.setRaw(CONTENT, "9");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submission, draft("{\"name\":\"b\",\"height\":null}"), "two"));
      assertEquals("9", ((DraftNode.Scalar) session.snapshot().draft().at(CONTENT)).raw());
      assertFalse(session.dirty());
      session.edit(OPTION, n -> ((DraftNode.OptionalValue) n).withPresent(true), "Restore");
      assertEquals(
          Optional.of(9), session.snapshot().inspect().candidate().orElseThrow().value().height());
      session.undo();
      session.undo();
      assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(CONTENT)).raw());
      assertFalse(session.dirty());
      assertFalse(
          session
              .snapshot()
              .inspect()
              .candidate()
              .orElseThrow()
              .addresses()
              .containsValue(CONTENT));
    }
  }

  @Test
  void optionalCollectionKeepsRowIdentityAcrossAcknowledgementAndInactiveEdits() throws Exception {
    var list =
        ValueTypes.optional(
            ValueTypes.list(ValueTypes.integer(0, 100), 4),
            ValueShape.OptionalEncoding.SINGLETON_LIST);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:optional_list"), 1, list, () -> Optional.of(List.of(1, 2)));
    try (var session =
        new EditSession<>(
            definition,
            UUID.randomUUID(),
            "one",
            Optional.of(List.of(1, 2)),
            JSON,
            DraftLimits.CONFIGURATION)) {
      var contents = FieldPath.ROOT.append(FieldPath.Part.VALUE);
      var rows = ((DraftNode.Sequence) session.snapshot().draft().at(contents)).rows();
      var submission = session.prepareSubmission().submission().orElseThrow();
      session.setRaw(contents.row(rows.getFirst().id()), "-");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submission, Optional.of(List.of(1, 2)), "two"));
      assertEquals(
          rows.stream().map(DraftNode.Row::id).toList(),
          ((DraftNode.Sequence) session.baseline().at(contents))
              .rows().stream().map(DraftNode.Row::id).toList());
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      session.edit(FieldPath.ROOT, n -> ((DraftNode.OptionalValue) n).withPresent(false), "Clear");
      assertEquals("[]", session.snapshot().inspect().candidate().orElseThrow().authoredJson());
      session.edit(FieldPath.ROOT, n -> ((DraftNode.OptionalValue) n).withPresent(true), "Restore");
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertEquals(
          "-",
          ((DraftNode.Scalar) session.snapshot().draft().at(contents.row(rows.getFirst().id())))
              .raw());
    }
  }

  @Test
  void nestedMembershipKeepsAmbiguousNullDraftsInvalidAndSingletonEmptyDistinct() throws Exception {
    var inner =
        ValueTypes.optional(ValueTypes.integer(0, 100), ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var ambiguous = ValueTypes.optional(inner, ValueShape.OptionalEncoding.NULL_OR_VALUE);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:ambiguous_optional"), 1, ambiguous, Optional::empty);
    var raw =
        new DraftTree(
            new DraftNode.OptionalValue(
                true, new DraftNode.OptionalValue(false, new DraftNode.Scalar("7"))),
            DraftLimits.CONFIGURATION);
    assertTrue(raw.inspect(definition, JSON).candidate().isEmpty());
    assertTrue(
        raw.inspect(definition, JSON).report().issues().stream()
            .anyMatch(i -> i.code().equals("input.optional_ambiguous")));
    var list = ValueTypes.optional(inner, ValueShape.OptionalEncoding.SINGLETON_LIST);
    var listDefinition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:nested_optional"), 1, list, Optional::empty);
    var parsed =
        DraftTree.fromParsed(list, JsonInput.read("[null]", JSON), DraftLimits.CONFIGURATION);
    assertEquals(
        Optional.of(Optional.empty()),
        parsed.inspect(listDefinition, JSON).candidate().orElseThrow().value());
    assertEquals(
        FieldPath.ROOT.append(FieldPath.Part.VALUE),
        parsed
            .inspect(listDefinition, JSON)
            .candidate()
            .orElseThrow()
            .addresses()
            .get(SourcePath.ROOT.index(0)));
    assertInstanceOf(
        DraftNode.Missing.class,
        parsed.at(FieldPath.ROOT.append(FieldPath.Part.VALUE).append(FieldPath.Part.VALUE)));
  }

  @Test
  void invalidMembershipShapeIsRetainedAndInactiveContentStillConsumesItsBudget() throws Exception {
    var list =
        ValueTypes.optional(ValueTypes.integer(0, 100), ValueShape.OptionalEncoding.SINGLETON_LIST);
    var malformed =
        DraftTree.fromParsed(list, JsonInput.read("[1,2]", JSON), DraftLimits.CONFIGURATION);
    assertEquals("[1,2]", ((DraftNode.Opaque) malformed.root()).json());
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:invalid_optional"), 1, list, Optional::empty);
    assertTrue(malformed.inspect(definition, JSON).candidate().isEmpty());
    var tree =
        new DraftTree(
            new DraftNode.OptionalValue(false, new DraftNode.Scalar("7")),
            new DraftLimits(8, 16, 2, 8, 1024));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            tree.change(
                FieldPath.ROOT.append(FieldPath.Part.VALUE),
                n -> new DraftNode.Scalar("oversized")));
    assertEquals(
        "7", ((DraftNode.Scalar) tree.at(FieldPath.ROOT.append(FieldPath.Part.VALUE))).raw());
  }

  @Test
  void manualConflictChoosesPresenceAndPayloadAsOneField() throws Exception {
    try (var session = session("{\"name\":\"a\",\"height\":7}")) {
      session.setRaw(CONTENT, "-");
      var review = session.reviewConflict(draft("{\"name\":\"a\",\"height\":null}"), "two");
      assertEquals(
          List.of(OPTION), review.entries().stream().map(ConflictReview.Entry::path).toList());
      session.resolve(review, Map.of(OPTION, ConflictReview.Choice.DRAFT));
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(CONTENT)).raw());
      assertTrue(session.dirty());
    }
  }
}
