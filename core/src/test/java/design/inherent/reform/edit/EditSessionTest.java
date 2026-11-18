package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class EditSessionTest {
  private static final FieldPath NAME = FieldPath.ROOT.field("name");
  private static final FieldPath COUNT = FieldPath.ROOT.field("count");
  private static final FieldPath ENTRIES = FieldPath.ROOT.field("entries");

  static EditSession<DraftTreeTest.Profile> session() {
    return new EditSession<>(
        DraftTreeTest.DEFINITION,
        UUID.randomUUID(),
        "saved-1",
        DraftTreeTest.DEFINITION.defaults(),
        DraftTreeTest.JSON,
        DraftTreeTest.DRAFT);
  }

  static DraftTreeTest.Profile value(EditSession<DraftTreeTest.Profile> session) {
    return session.snapshot().inspect().candidate().orElseThrow().value();
  }

  static Submission<DraftTreeTest.Profile> submit(EditSession<DraftTreeTest.Profile> session) {
    var ready = session.prepareSubmission();
    assertEquals(EditSession.PreparationState.READY, ready.state());
    return ready.submission().orElseThrow();
  }

  @Test
  void typingCoalescesOnlyWithinItsExplicitFieldGroup() {
    var session = session();
    var group = session.beginGroup(COUNT);
    session.setRaw(COUNT, "1", group);
    session.setRaw(COUNT, "12", group);
    session.setRaw(COUNT, "-", group);
    assertEquals(1, session.historySize());
    assertEquals(EditSession.PreparationState.INVALID, session.prepareSubmission().state());
    assertTrue(session.undo());
    assertEquals(2, value(session).count());
    assertTrue(session.redo());
    assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(COUNT)).raw());
    assertThrows(IllegalArgumentException.class, () -> session.setRaw(NAME, "wrong group", group));
    assertEquals("Initial", ((DraftNode.Scalar) session.snapshot().draft().at(NAME)).raw());
  }

  @Test
  void compoundImportIsOneUndoableDraftChange() {
    var session = session();
    var imported =
        session
            .snapshot()
            .draft()
            .change(NAME, ignored -> new DraftNode.Scalar("Imported"))
            .change(COUNT, ignored -> new DraftNode.Scalar("-"));
    session.stage(imported, "Import profile");
    assertEquals(1, session.historySize());
    assertTrue(session.snapshot().inspect().candidate().isEmpty());
    session.undo();
    assertEquals("Initial", value(session).name());
    assertEquals(2, value(session).count());
    session.redo();
    assertTrue(session.snapshot().inspect().candidate().isEmpty());
  }

  @Test
  void reviewedImportUsesExactRootRetiresOldInputAndPreservesUnchangedRowIdentity() {
    var session = session();
    session.setRaw(NAME, "Earlier work");
    var before = session.snapshot();
    var imported =
        DraftTree.from(
            DraftTreeTest.DEFINITION.type(),
            new DraftTreeTest.Profile("Imported", 7, value(session).entries()),
            DraftTreeTest.DRAFT);
    session.stage(before.target(FieldPath.ROOT), imported, "Import reviewed profile");
    var after = session.snapshot();
    assertEquals(before.generation() + 1, after.generation());
    assertEquals(before.draft().at(ENTRIES), after.draft().at(ENTRIES));
    assertEquals(7, value(session).count());
    assertThrows(
        DraftEditRefusal.class,
        () ->
            session.execute(
                new DraftEdit(before.target(NAME).typing(), new DraftEdit.SetRaw("late"))));
    assertTrue(session.undo());
    assertEquals("Earlier work", value(session).name());
    assertEquals(2, value(session).count());
    assertTrue(session.redo());
    assertEquals("Imported", value(session).name());
  }

  @Test
  void reviewedImportRefusesStaleForeignVersionlessAndNonRootTargetsWithoutChanges() {
    var session = session();
    var original = session.snapshot();
    var imported = original.draft().change(NAME, ignored -> new DraftNode.Scalar("Imported"));
    for (var target :
        List.of(
            original.target(NAME),
            original.target(FieldPath.ROOT).typing(),
            new DraftTarget(
                UUID.randomUUID(),
                original.generation(),
                java.util.Optional.of(original.version()),
                FieldPath.ROOT))) {
      assertThrows(DraftEditRefusal.class, () -> session.stage(target, imported, "Import"));
      assertEquals(original.version(), session.snapshot().version());
      assertEquals(0, session.historySize());
    }
    session.setRaw(NAME, "Newer work");
    assertThrows(
        DraftEditRefusal.class,
        () -> session.stage(original.target(FieldPath.ROOT), imported, "Import"));
    assertEquals("Newer work", value(session).name());
  }

  @Test
  void reviewedImportNoopPreservesRedoAndPendingSaveRemainsAcknowledgable() {
    var session = session();
    session.setRaw(NAME, "Other");
    session.undo();
    var before = session.snapshot();
    var equal =
        DraftTree.from(DraftTreeTest.DEFINITION.type(), value(session), DraftTreeTest.DRAFT);
    session.stage(before.target(FieldPath.ROOT), equal, "No change");
    assertEquals(before.generation(), session.snapshot().generation());
    assertEquals(before.version(), session.snapshot().version());
    assertTrue(session.canRedo());
    var pending = submit(session);
    var replacement = equal.change(NAME, ignored -> new DraftNode.Scalar("Imported"));
    assertThrows(
        DraftEditRefusal.class,
        () -> session.stage(session.snapshot().target(FieldPath.ROOT), replacement, "Import"));
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(pending, pending.candidate().draft(), "saved-2"));
    assertTrue(session.canRedo());
  }

  @Test
  void reviewedImportRetainsInvalidSourceAsDraftButRefusesBudgetLabelAndConflictBoundaries() {
    var session = session();
    var before = session.snapshot();
    var oversized =
        new DraftTree(
            new DraftNode.Scalar("x".repeat(DraftTreeTest.DRAFT.maximumCharacters() + 1)),
            new DraftLimits(128, 16384, 262144, 128, 1048576));
    assertThrows(
        DraftEditRefusal.class,
        () -> session.stage(before.target(FieldPath.ROOT), oversized, "Import"));
    assertThrows(
        DraftEditRefusal.class,
        () -> session.stage(before.target(FieldPath.ROOT), before.draft(), ""));
    assertEquals(before.version(), session.snapshot().version());
    assertEquals(0, session.historySize());
    var invalid = before.draft().change(COUNT, ignored -> new DraftNode.Scalar("-"));
    session.stage(before.target(FieldPath.ROOT), invalid, "Import repairable input");
    assertTrue(session.snapshot().inspect().candidate().isEmpty());
    assertEquals(EditSession.PreparationState.INVALID, session.prepareSubmission().state());
    var current = session.snapshot();
    var review = session.reviewConflict(before.draft(), "saved-2");
    assertThrows(
        DraftEditRefusal.class,
        () -> session.stage(current.target(FieldPath.ROOT), before.draft(), "Import"));
    assertSame(review, session.review().orElseThrow());
    assertEquals(current.version(), session.snapshot().version());
    assertEquals(current.draft(), session.snapshot().draft());
  }

  @Test
  void reviewedReplacementDoesNotDiscardSuppliedInactiveInputAsAnAuthoredNoop() {
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:inactive_import"),
            1,
            ValueTypes.optional(ValueTypes.text(32), ValueShape.OptionalEncoding.NULL_OR_VALUE),
            java.util.Optional::<String>empty);
    var session =
        new EditSession<>(
            definition,
            UUID.randomUUID(),
            "saved-1",
            java.util.Optional.empty(),
            DraftTreeTest.JSON,
            DraftTreeTest.DRAFT);
    var earlier =
        new DraftTree(
            new DraftNode.OptionalValue(false, new DraftNode.Scalar("Earlier inactive")),
            DraftTreeTest.DRAFT);
    session.stage(earlier, "Previous input");
    var incoming =
        new DraftTree(
            new DraftNode.OptionalValue(false, new DraftNode.Scalar("Imported inactive")),
            DraftTreeTest.DRAFT);
    var before = session.snapshot();
    session.stage(before.target(FieldPath.ROOT), incoming, "Import inactive input");
    assertEquals(incoming.root(), session.snapshot().draft().root());
    assertEquals(before.generation() + 1, session.snapshot().generation());
    assertFalse(session.dirty());
    assertTrue(session.undo());
    assertEquals(earlier.root(), session.snapshot().draft().root());
  }

  @Test
  void acknowledgementRetainsLaterEditsAndExplicitRevert() {
    var session = session();
    session.setRaw(COUNT, "3");
    var submission = submit(session);
    session.markInFlight(submission);
    session.setRaw(NAME, "Later");
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(
            submission, new DraftTreeTest.Profile("Initial", 3, List.of(10, 20)), "saved-2"));
    assertEquals("Later", value(session).name());
    assertEquals(3, value(session).count());
    assertTrue(session.dirty());
    var second = session();
    second.setRaw(COUNT, "3");
    var submitted = submit(second);
    second.markInFlight(submitted);
    second.revert();
    second.acknowledge(
        submitted, new DraftTreeTest.Profile("Initial", 3, List.of(10, 20)), "saved-2");
    assertEquals(2, value(second).count());
    assertTrue(second.dirty());
  }

  @Test
  void lateInvalidInputIsNotReplacedByAValidAcknowledgement() {
    var session = session();
    session.setRaw(COUNT, "3");
    var submitted = submit(session);
    session.markInFlight(submitted);
    session.setRaw(COUNT, "1e+");
    session.acknowledge(
        submitted, new DraftTreeTest.Profile("Initial", 3, List.of(10, 20)), "saved-2");
    assertEquals("1e+", ((DraftNode.Scalar) session.snapshot().draft().at(COUNT)).raw());
    assertEquals(EditSession.PreparationState.INVALID, session.prepareSubmission().state());
  }

  @Test
  void duplicateAndUnknownSubmissionsRequireCompletionRatherThanBlindRetry() {
    var session = session();
    session.setRaw(COUNT, "3");
    var submitted = submit(session);
    assertEquals(EditSession.PreparationState.PENDING, session.prepareSubmission().state());
    assertTrue(session.markInFlight(submitted));
    assertFalse(session.discardPrepared(submitted));
    session.failed(submitted, EditSession.Failure.UNKNOWN);
    assertEquals(EditSession.PreparationState.UNKNOWN, session.prepareSubmission().state());
    assertFalse(session.discardPrepared(submitted));
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(
            submitted, new DraftTreeTest.Profile("Initial", 3, List.of(10, 20)), "reconciled"));
    assertFalse(session.dirty());
  }

  @Test
  void reloadAndClosedContextsRefuseOldAcknowledgements() {
    var session = session();
    session.setRaw(COUNT, "3");
    var submitted = submit(session);
    session.reload(new DraftTreeTest.Profile("Reloaded", 7, List.of()), "saved-7");
    assertEquals(
        EditSession.Acknowledgement.STALE,
        session.acknowledge(
            submitted, new DraftTreeTest.Profile("Initial", 3, List.of(10, 20)), "late"));
    assertEquals(7, value(session).count());
    assertFalse(session.canUndo());
    var fresh = submit(session);
    session.close();
    assertEquals(
        EditSession.Acknowledgement.STALE,
        session.acknowledge(fresh, new DraftTreeTest.Profile("Ignored", 1, List.of()), "closed"));
    assertEquals(EditSession.PreparationState.CLOSED, session.prepareSubmission().state());
    assertThrows(IllegalStateException.class, () -> session.setRaw(COUNT, "1"));
  }

  @Test
  void submissionTokensDoNotCrossSessionsEvenWithMatchingDefinitions() {
    var first = session();
    var foreign = submit(first);
    var second = session();
    var own = submit(second);
    assertFalse(second.markInFlight(foreign));
    assertFalse(second.failed(foreign, EditSession.Failure.REFUSED));
    assertEquals(
        EditSession.Acknowledgement.STALE,
        second.acknowledge(foreign, foreign.candidate().value(), "foreign"));
    assertEquals(
        EditSession.TargetState.FOREIGN,
        second.locate(foreign, SourcePath.ROOT.property("name")).state());
    assertTrue(second.markInFlight(own));
    assertEquals("saved-1", second.snapshot().sourceRevision());
  }

  @Test
  void oldIndexedDiagnosticsFollowRowsAndNeverRedirectAfterReplacement() {
    var session = session();
    var submitted = submit(session);
    var source = SourcePath.ROOT.property("entries").index(0);
    UUID row = ((DraftNode.Sequence) session.snapshot().draft().at(ENTRIES)).rows().getFirst().id();
    session.edit(ENTRIES, node -> ((DraftNode.Sequence) node).move(row, 1), "Move row");
    assertEquals(ENTRIES.row(row), session.locate(submitted, source).path());
    assertEquals(EditSession.TargetState.CURRENT, session.locate(submitted, source).state());
    session.setRaw(ENTRIES.row(row), "11");
    assertEquals(EditSession.TargetState.STALE_VALUE, session.locate(submitted, source).state());
    session.edit(
        ENTRIES,
        node ->
            ((DraftNode.Sequence) node)
                .remove(row)
                .insert(0, DraftNode.Row.create(new DraftNode.Scalar("10"))),
        "Replace row");
    assertEquals(EditSession.TargetState.REMOVED, session.locate(submitted, source).state());
  }

  @Test
  void normalizedAcknowledgementRebasesUndoWithoutErasingLaterIntent() {
    var session = session();
    session.setRaw(COUNT, "3.00");
    var submitted = submit(session);
    session.markInFlight(submitted);
    session.setRaw(NAME, "Later");
    session.acknowledge(
        submitted, new DraftTreeTest.Profile("Initial", 3, List.of(10, 20)), "normalized");
    assertEquals("3", ((DraftNode.Scalar) session.snapshot().draft().at(COUNT)).raw());
    assertTrue(session.undo());
    assertFalse(session.dirty());
    assertEquals("Initial", value(session).name());
    assertEquals(3, value(session).count());
    assertTrue(session.undo());
    assertEquals(2, value(session).count());
    assertTrue(session.dirty());
  }

  @Test
  void manualReviewUsesExplicitFieldChoicesAndFreshSavedRevision() {
    var session = session();
    session.setRaw(NAME, "Local");
    var observed =
        DraftTree.from(
            DraftTreeTest.TYPE,
            new DraftTreeTest.Profile("Remote", 5, List.of(10, 20)),
            DraftTreeTest.DRAFT);
    var review = session.reviewConflict(observed, "saved-2");
    assertEquals(EditSession.PreparationState.REVIEW_REQUIRED, session.prepareSubmission().state());
    assertEquals(2, review.entries().size());
    assertEquals(ConflictReview.Basis.OBSERVED_BASELINE, review.basis());
    assertEquals("saved-1", review.baselineRevision());
    assertEquals("saved-2", review.observedRevision());
    assertThrows(
        IllegalArgumentException.class,
        () -> session.resolve(review, Map.of(NAME, ConflictReview.Choice.DRAFT)));
    session.resolve(
        review, Map.of(NAME, ConflictReview.Choice.DRAFT, COUNT, ConflictReview.Choice.OBSERVED));
    assertEquals("Local", value(session).name());
    assertEquals(5, value(session).count());
    assertEquals("saved-2", submit(session).sourceRevision());
  }

  @Test
  void changedReviewAndStructuralCollectionsCannotBeMergedByGuessing() {
    var session = session();
    session.edit(
        ENTRIES,
        node ->
            ((DraftNode.Sequence) node).move(((DraftNode.Sequence) node).rows().getFirst().id(), 1),
        "Move");
    var observed =
        DraftTree.from(
            DraftTreeTest.TYPE,
            new DraftTreeTest.Profile("Initial", 2, List.of(10, 20, 30)),
            DraftTreeTest.DRAFT);
    var review = session.reviewConflict(observed, "saved-2");
    assertEquals(
        List.of(ENTRIES), review.entries().stream().map(ConflictReview.Entry::path).toList());
    session.setRaw(NAME, "Later");
    assertThrows(
        IllegalStateException.class,
        () -> session.resolve(review, Map.of(ENTRIES, ConflictReview.Choice.DRAFT)));
    assertEquals("Later", value(session).name());
  }

  @Test
  void differentSavedValuesWithLateEditsRequireReview() {
    var session = session();
    session.setRaw(COUNT, "3");
    var submitted = submit(session);
    session.markInFlight(submitted);
    session.setRaw(NAME, "Later");
    assertEquals(
        EditSession.Acknowledgement.REVIEW_REQUIRED,
        session.acknowledge(
            submitted, new DraftTreeTest.Profile("Initial", 4, List.of(10, 20)), "saved-2"));
    assertEquals("Later", value(session).name());
    assertEquals(3, value(session).count());
    var review = session.review().orElseThrow();
    var choices = new HashMap<FieldPath, ConflictReview.Choice>();
    assertEquals(ConflictReview.Basis.SUBMITTED_DRAFT, review.basis());
    assertEquals(submitted.sourceRevision(), review.baselineRevision());
    assertEquals("saved-2", review.observedRevision());
    review
        .entries()
        .forEach(
            entry ->
                choices.put(
                    entry.path(),
                    entry.path().equals(COUNT)
                        ? ConflictReview.Choice.OBSERVED
                        : ConflictReview.Choice.DRAFT));
    session.resolve(review, choices);
    assertEquals(4, value(session).count());
    assertEquals("Later", value(session).name());
  }

  @Test
  void historyAndRefusedLargeEditsHaveBoundedRetention() {
    var limits = new DraftLimits(32, 2048, 256, 3, 10_000);
    var session =
        new EditSession<>(
            DraftTreeTest.DEFINITION,
            UUID.randomUUID(),
            "saved-1",
            DraftTreeTest.DEFINITION.defaults(),
            DraftTreeTest.JSON,
            limits);
    for (int i = 0; i < 20; i++) session.setRaw(COUNT, String.valueOf(i));
    assertTrue(session.historySize() <= 3);
    assertTrue(session.historyCost() <= 10_000);
    var before = session.snapshot().draft().root();
    assertThrows(IllegalArgumentException.class, () -> session.setRaw(NAME, "large".repeat(100)));
    assertEquals(before, session.snapshot().draft().root());
  }

  @Test
  void oversizedRebasePreservesBothJournalsAndThePendingObservation() {
    var limits = new DraftLimits(32, 2048, 40, 128, 100_000);
    var session =
        new EditSession<>(
            DraftTreeTest.DEFINITION,
            UUID.randomUUID(),
            "saved-1",
            DraftTreeTest.DEFINITION.defaults(),
            DraftTreeTest.JSON,
            limits);
    session.setRaw(COUNT, "1e3");
    var submitted = submit(session);
    session.markInFlight(submitted);
    session.setRaw(NAME, "x".repeat(17));
    session.setRaw(NAME, "Small");
    session.undo();
    session.undo();
    var before = session.snapshot();
    var oldBaseline = session.baseline();
    long oldCost = session.historyCost();
    assertEquals(
        EditSession.Acknowledgement.INVALID_OBSERVATION,
        session.acknowledge(
            submitted, new DraftTreeTest.Profile("Initial", 1000, List.of(10, 20)), "saved-2"));
    assertEquals(before, session.snapshot());
    assertSame(oldBaseline, session.baseline());
    assertEquals(oldCost, session.historyCost());
    assertEquals(
        EditSession.PendingState.OBSERVATION_UNAVAILABLE, session.pendingState().orElseThrow());
    assertEquals(EditSession.PreparationState.PENDING, session.prepareSubmission().state());
    assertTrue(session.redo());
    assertEquals("x".repeat(17), value(session).name());
    session.undo();
    var retained = session.snapshot();
    session.discardHistory();
    assertEquals(retained, session.snapshot());
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(
            submitted, new DraftTreeTest.Profile("Initial", 1000, List.of(10, 20)), "saved-2"));
    assertEquals(1000, value(session).count());
    assertFalse(session.canUndo());
  }

  @Test
  void oversizedLateDraftCanBeAdjustedBeforeExplicitReconciliation() {
    var limits = new DraftLimits(32, 2048, 40, 128, 100_000);
    var session =
        new EditSession<>(
            DraftTreeTest.DEFINITION,
            UUID.randomUUID(),
            "saved-1",
            DraftTreeTest.DEFINITION.defaults(),
            DraftTreeTest.JSON,
            limits);
    session.setRaw(COUNT, "1e3");
    var submitted = submit(session);
    session.markInFlight(submitted);
    session.setRaw(NAME, "x".repeat(17));
    var before = session.snapshot();
    var saved = new DraftTreeTest.Profile("Initial", 1000, List.of(10, 20));
    assertEquals(
        EditSession.Acknowledgement.INVALID_OBSERVATION,
        session.acknowledge(submitted, saved, "saved-2"));
    assertEquals(before, session.snapshot());
    session.discardHistory();
    assertEquals(
        EditSession.Acknowledgement.INVALID_OBSERVATION,
        session.acknowledge(submitted, saved, "saved-2"));
    session.setRaw(NAME, "Small");
    session.discardHistory();
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submitted, saved, "saved-2"));
    assertEquals("Small", value(session).name());
    assertEquals(1000, value(session).count());
    assertTrue(session.dirty());
  }

  @Test
  void changedSavedValueWithoutLaterEditsRebasesRedoOntoTheObservation() {
    var session = session();
    session.setRaw(COUNT, "3");
    var submitted = submit(session);
    session.markInFlight(submitted);
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(
            submitted, new DraftTreeTest.Profile("Initial", 4, List.of(10, 20)), "saved-2"));
    assertEquals(4, value(session).count());
    assertTrue(session.undo());
    assertEquals(2, value(session).count());
    assertTrue(session.redo());
    assertEquals(4, value(session).count());
    assertFalse(session.dirty());
  }

  @Test
  void conflictChoicesAreCheckedAsOneCompleteBoundedChange() {
    var limits = new DraftLimits(32, 2048, 40, 128, 100_000);
    var session =
        new EditSession<>(
            DraftTreeTest.DEFINITION,
            UUID.randomUUID(),
            "saved-1",
            new DraftTreeTest.Profile("x".repeat(14), 1000, List.of(10, 20)),
            DraftTreeTest.JSON,
            limits);
    var observed =
        DraftTree.from(
            DraftTreeTest.TYPE,
            new DraftTreeTest.Profile("x".repeat(17), 1, List.of(10, 20)),
            limits);
    var review = session.reviewConflict(observed, "saved-2");
    session.resolve(
        review,
        Map.of(NAME, ConflictReview.Choice.OBSERVED, COUNT, ConflictReview.Choice.OBSERVED));
    assertEquals("x".repeat(17), value(session).name());
    assertEquals(1, value(session).count());
    assertFalse(session.dirty());
  }

  @Test
  void onlyImmutableViewsCrossTheThreadBoundary() throws Exception {
    var session = session();
    var snapshot = session.snapshot();
    assertTrue(
        CompletableFuture.supplyAsync(() -> snapshot.inspect().candidate().isPresent()).get());
    assertTrue(
        CompletableFuture.supplyAsync(
                () -> {
                  try {
                    session.setRaw(COUNT, "99");
                    return false;
                  } catch (IllegalStateException expected) {
                    return true;
                  }
                })
            .get());
    assertEquals(2, value(session).count());
  }
}
