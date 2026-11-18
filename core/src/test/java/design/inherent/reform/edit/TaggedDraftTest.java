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
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaggedDraftTest {
  interface Mode {}

  record Land(int height, List<Integer> pillars) implements Mode {}

  record Cavern(boolean open) implements Mode {}

  static final FieldKey<Integer> HEIGHT = FieldKey.of("height", ValueTypes.integer(0, 100));
  static final FieldKey<List<Integer>> PILLARS =
      FieldKey.of("pillars", ValueTypes.list(ValueTypes.integer(0, 100), 4));
  static final FieldKey<Boolean> OPEN = FieldKey.of("open", ValueTypes.bool());
  static final RecordType<Land> LAND =
      RecordType.<Land>builder(v -> new Land(v.get(HEIGHT), v.get(PILLARS)))
          .field(HEIGHT, Land::height)
          .field(PILLARS, Land::pillars)
          .build();
  static final RecordType<Cavern> CAVERN =
      RecordType.<Cavern>builder(v -> new Cavern(v.get(OPEN))).field(OPEN, Cavern::open).build();
  static final TaggedType<Mode> TYPE =
      TaggedType.<Mode>builder("kind", "settings")
          .variant("land", Land.class, LAND)
          .variant("cavern", Cavern.class, CAVERN)
          .build();
  static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);
  static final DocumentDefinition<Mode> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:tagged"), 1, TYPE, () -> new Land(7, List.of(1, 2)));
  static final FieldPath LAND_PATH = FieldPath.ROOT.variant("land"),
      HEIGHT_PATH = LAND_PATH.field("height"),
      CAVERN_PATH = FieldPath.ROOT.variant("cavern");
  private static final String LAND_SOURCE =
      "{\"kind\":\"land\",\"settings\":{\"height\":7.00,\"pillars\":[1,2]}}";

  static DraftNode cavern(boolean open) {
    return DraftTree.from(CAVERN, new Cavern(open), DraftLimits.CONFIGURATION).root();
  }

  static DraftTree draft(String source) throws Exception {
    return DraftTree.fromParsed(TYPE, JsonInput.read(source, JSON), DraftLimits.CONFIGURATION);
  }

  static EditSession<Mode> session(String source) throws Exception {
    return EditSession.fromSource(
        DEFINITION,
        UUID.randomUUID(),
        "one",
        JsonInput.read(source, JSON),
        JSON,
        DraftLimits.CONFIGURATION);
  }

  static void select(EditSession<Mode> session, String id, DraftNode seed) {
    session.edit(
        FieldPath.ROOT, n -> ((DraftNode.Alternative) n).select(id, seed), "Select variant");
  }

  @Test
  void branchPathsSourceAddressesAndOriginalNumbersHaveSeparateIdentities() throws Exception {
    var tree = draft(LAND_SOURCE);
    assertEquals(
        "land", ((DraftNode.Scalar) tree.at(FieldPath.ROOT.append(FieldPath.Part.KEY))).raw());
    assertEquals("7.00", ((DraftNode.Scalar) tree.at(HEIGHT_PATH)).raw());
    var candidate = tree.inspect(DEFINITION, JSON).candidate().orElseThrow();
    assertEquals(
        HEIGHT_PATH,
        candidate.addresses().get(SourcePath.ROOT.property("settings").property("height")));
    assertEquals(
        FieldPath.ROOT.append(FieldPath.Part.KEY),
        candidate.addresses().get(SourcePath.ROOT.property("kind")));
    assertFalse(tree.contains(FieldPath.ROOT.field("kind")));
    assertFalse(tree.contains(CAVERN_PATH));
  }

  @Test
  void switchingVariantsKeepsInactiveInvalidInputRowsAndUndoWithoutSerializingIt()
      throws Exception {
    try (var session = session(LAND_SOURCE)) {
      var pillars = LAND_PATH.field("pillars");
      var row = ((DraftNode.Sequence) session.snapshot().draft().at(pillars)).rows().getFirst();
      session.setRaw(HEIGHT_PATH, "-");
      session.setRaw(pillars.row(row.id()), "-");
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      select(session, "cavern", cavern(false));
      assertEquals(
          new Cavern(false), session.snapshot().inspect().candidate().orElseThrow().value());
      assertFalse(
          session
              .snapshot()
              .inspect()
              .candidate()
              .orElseThrow()
              .authoredJson()
              .contains("pillars"));
      select(session, "land", new DraftNode.Missing());
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertEquals(
          "-", ((DraftNode.Scalar) session.snapshot().draft().at(pillars.row(row.id()))).raw());
      session.undo();
      assertEquals(
          new Cavern(false), session.snapshot().inspect().candidate().orElseThrow().value());
      session.redo();
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
    }
  }

  @Test
  void unknownVariantsAndExtraFieldsRemainRepairableWithoutBorrowingAnotherBranch()
      throws Exception {
    var unknown = draft("{\"kind\":\"future\",\"settings\":{\"raw\":3.00},\"extra\":7}");
    var original = (DraftNode.Alternative) unknown.root();
    assertEquals("{\"raw\":3.00}", ((DraftNode.Opaque) original.selectedValue()).json());
    assertEquals("7", ((DraftNode.Opaque) original.unknownFields().get("extra")).json());
    assertTrue(unknown.inspect(DEFINITION, JSON).candidate().isEmpty());
    var selected =
        unknown.change(
            FieldPath.ROOT, n -> ((DraftNode.Alternative) n).select("cavern", cavern(true)));
    assertTrue(
        selected.inspect(DEFINITION, JSON).candidate().isEmpty(),
        "Selecting a known variant does not erase an extra field");
    var repaired =
        selected.change(
            FieldPath.ROOT, n -> ((DraftNode.Alternative) n).withoutUnknownField("extra"));
    assertEquals(
        new Cavern(true), repaired.inspect(DEFINITION, JSON).candidate().orElseThrow().value());
    assertEquals(
        original.selectedValue(),
        ((DraftNode.Alternative) repaired.root()).branches().get("future"));
    var wrongKind = draft("{\"kind\":7,\"settings\":{\"height\":7,\"pillars\":[]}}");
    assertInstanceOf(DraftNode.Opaque.class, wrongKind.root());
    assertTrue(wrongKind.inspect(DEFINITION, JSON).candidate().isEmpty());
  }

  @Test
  void acknowledgementNormalizesTheSavedBranchWhileKeepingLaterInactiveChanges() throws Exception {
    try (var session = session(LAND_SOURCE)) {
      select(session, "cavern", cavern(false));
      select(session, "land", new DraftNode.Missing());
      var row =
          ((DraftNode.Sequence) session.snapshot().draft().at(LAND_PATH.field("pillars")))
              .rows()
              .getFirst();
      var submission = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submission);
      session.setRaw(CAVERN_PATH.field("open"), "true");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submission, new Land(7, List.of(1, 2)), "two"));
      assertEquals("7", ((DraftNode.Scalar) session.snapshot().draft().at(HEIGHT_PATH)).raw());
      assertEquals(
          "true",
          ((DraftNode.Scalar) session.snapshot().draft().at(CAVERN_PATH.field("open"))).raw());
      assertEquals(
          row.id(),
          ((DraftNode.Sequence) session.snapshot().draft().at(LAND_PATH.field("pillars")))
              .rows()
              .getFirst()
              .id());
      assertFalse(session.dirty());
      select(session, "cavern", cavern(false));
      assertEquals(
          new Cavern(true), session.snapshot().inspect().candidate().orElseThrow().value());
      session.undo();
      assertFalse(session.dirty());
    }
  }

  @Test
  void aLateSelectionKeepsItsOwnPayloadAndStalesOldBranchDiagnostics() throws Exception {
    try (var session = session(LAND_SOURCE)) {
      var submission = session.prepareSubmission().submission().orElseThrow();
      var source = SourcePath.ROOT.property("settings").property("height");
      select(session, "cavern", cavern(true));
      assertEquals(EditSession.TargetState.STALE_VALUE, session.locate(submission, source).state());
      assertEquals(HEIGHT_PATH, session.locate(submission, source).path());
      assertEquals(
          EditSession.TargetState.STALE_VALUE,
          session.locate(submission, SourcePath.ROOT.property("kind")).state());
      session.setRaw(HEIGHT_PATH, "-");
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(submission, new Land(7, List.of(1, 2)), "two"));
      assertEquals(
          new Cavern(true), session.snapshot().inspect().candidate().orElseThrow().value());
      assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(HEIGHT_PATH)).raw());
      assertTrue(session.dirty());
      select(session, "land", new DraftNode.Missing());
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
    }
  }

  @Test
  void inactiveRetentionIsBoundedAndDiscardRequiresTheActualInactiveIdentity() throws Exception {
    var tree = draft(LAND_SOURCE);
    var compact =
        new DraftTree(
            tree.root(), new DraftLimits(16, 64, tree.root().size().characters() + 1, 8, 8192));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            compact.change(
                FieldPath.ROOT,
                n -> ((DraftNode.Alternative) n).select("new", new DraftNode.Opaque("oversized"))));
    assertEquals(
        List.of("land"),
        ((DraftNode.Alternative) compact.root()).branches().keySet().stream().toList());
    var withCavern =
        tree.change(
            FieldPath.ROOT, n -> ((DraftNode.Alternative) n).select("cavern", cavern(false)));
    var discarded =
        withCavern.change(FieldPath.ROOT, n -> ((DraftNode.Alternative) n).discardInactive("land"));
    assertTrue(withCavern.sameValue(discarded));
    assertFalse(discarded.contains(LAND_PATH));
    assertThrows(
        IllegalArgumentException.class,
        () -> ((DraftNode.Alternative) discarded.root()).discardInactive("cavern"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ((DraftNode.Alternative) discarded.root()).discardInactive("land"));
  }

  @Test
  void conflictChoicesDoNotMixASelectorWithAnotherVariantsPayload() throws Exception {
    try (var session = session(LAND_SOURCE)) {
      select(session, "cavern", cavern(true));
      var review =
          session.reviewConflict(
              draft("{\"kind\":\"land\",\"settings\":{\"height\":8,\"pillars\":[]}}"), "two");
      assertEquals(
          List.of(FieldPath.ROOT),
          review.entries().stream().map(ConflictReview.Entry::path).toList());
      session.resolve(review, Map.of(FieldPath.ROOT, ConflictReview.Choice.DRAFT));
      assertEquals(
          new Cavern(true), session.snapshot().inspect().candidate().orElseThrow().value());
      assertEquals("7.00", ((DraftNode.Scalar) session.snapshot().draft().at(HEIGHT_PATH)).raw());
    }
  }
}
