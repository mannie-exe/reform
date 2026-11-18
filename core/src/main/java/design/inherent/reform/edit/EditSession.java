package design.inherent.reform.edit;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Thread-confined editing. Immutable snapshots/candidates may be prepared on an admitted worker.
 */
public final class EditSession<T> implements AutoCloseable {
  public enum PreparationState {
    READY,
    INVALID,
    PENDING,
    UNKNOWN,
    REVIEW_REQUIRED,
    CLOSED
  }

  public enum PendingState {
    PREPARED,
    IN_FLIGHT,
    UNKNOWN,
    OBSERVATION_UNAVAILABLE
  }

  public enum Failure {
    UNATTEMPTED,
    REFUSED,
    FAILED,
    UNKNOWN
  }

  /** Local editor acknowledgement only; never a runtime-publication receipt. */
  public enum Acknowledgement {
    ACCEPTED,
    REVIEW_REQUIRED,
    STALE,
    INVALID_OBSERVATION
  }

  public enum TargetState {
    CURRENT,
    STALE_VALUE,
    REMOVED,
    STALE_BASELINE,
    FOREIGN
  }

  public record DiagnosticTarget(FieldPath path, TargetState state) {}

  public record EditGroup(FieldPath path, UUID id) {
    public EditGroup {
      Objects.requireNonNull(path);
      Objects.requireNonNull(id);
    }
  }

  public record Preparation<T>(
      PreparationState state, Optional<Submission<T>> submission, ValidationReport report) {}

  public record Snapshot<T>(
      DocumentDefinition<T> definition,
      UUID context,
      UUID session,
      long generation,
      long version,
      String sourceRevision,
      DraftTree draft,
      JsonLimits jsonLimits) {
    public DraftTarget target(FieldPath path) {
      DraftTypes.at(definition.type(), draft, path);
      return new DraftTarget(session, generation, Optional.of(version), path);
    }

    public DraftTree.Inspection<T> inspect() {
      return draft.inspect(definition, jsonLimits);
    }
  }

  private record History(DraftTree before, DraftTree after, String label, EditGroup group) {
    long cost() {
      return before.root().size().historyCost()
          + after.root().size().historyCost()
          + label.length()
          + 128;
    }
  }

  private final Runnable ownerThread;
  private final UUID id = UUID.randomUUID();
  private final UUID context;
  private final DocumentDefinition<T> definition;
  private final JsonLimits jsonLimits;
  private final DraftLimits limits;
  private final Deque<History> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private DraftTree baseline, current;
  private String sourceRevision;
  private long generation, version;
  private boolean closed;
  private Submission<T> pending;
  private PendingState pendingState;
  private ConflictReview review;
  private EditGroup lastGroup;

  public EditSession(
      DocumentDefinition<T> definition,
      UUID context,
      String sourceRevision,
      T saved,
      JsonLimits jsonLimits,
      DraftLimits limits) {
    this(
        definition,
        context,
        sourceRevision,
        DraftTree.from(definition.type(), saved, limits),
        jsonLimits,
        limits);
  }

  public static <T> EditSession<T> fromSource(
      DocumentDefinition<T> definition,
      UUID context,
      String sourceRevision,
      JsonInput.Parsed source,
      JsonLimits jsonLimits,
      DraftLimits limits) {
    return new EditSession<>(
        definition,
        context,
        sourceRevision,
        DraftTree.fromParsed(definition.type(), source, limits),
        jsonLimits,
        limits);
  }

  public static <T> EditSession<T> fromDraft(
      DocumentDefinition<T> definition,
      UUID context,
      String sourceRevision,
      DraftTree saved,
      JsonLimits jsonLimits,
      DraftLimits limits) {
    return new EditSession<>(
        definition,
        context,
        sourceRevision,
        new DraftTree(saved.root(), limits),
        jsonLimits,
        limits);
  }

  /** Advanced serialized-worker adapter; ordinary direct sessions retain their creating thread. */
  public static <T> EditSession<T> fromDraft(
      DocumentDefinition<T> definition,
      UUID context,
      String sourceRevision,
      DraftTree saved,
      JsonLimits jsonLimits,
      DraftLimits limits,
      Runnable ownerThread) {
    return new EditSession<>(
        definition,
        context,
        sourceRevision,
        new DraftTree(saved.root(), limits),
        jsonLimits,
        limits,
        ownerThread);
  }

  private EditSession(
      DocumentDefinition<T> definition,
      UUID context,
      String sourceRevision,
      DraftTree saved,
      JsonLimits jsonLimits,
      DraftLimits limits) {
    this(definition, context, sourceRevision, saved, jsonLimits, limits, currentThread());
  }

  private EditSession(
      DocumentDefinition<T> definition,
      UUID context,
      String sourceRevision,
      DraftTree saved,
      JsonLimits jsonLimits,
      DraftLimits limits,
      Runnable ownerThread) {
    this.definition = Objects.requireNonNull(definition);
    this.context = Objects.requireNonNull(context);
    this.sourceRevision = revision(sourceRevision);
    this.jsonLimits = Objects.requireNonNull(jsonLimits);
    this.limits = Objects.requireNonNull(limits);
    this.ownerThread = Objects.requireNonNull(ownerThread);
    baseline = saved;
    current = saved;
  }

  private void thread() {
    ownerThread.run();
  }

  private static Runnable currentThread() {
    long expected = Thread.currentThread().threadId();
    return () -> {
      if (Thread.currentThread().threadId() != expected)
        throw new IllegalStateException("Edit session requires its owning thread");
    };
  }

  private void active() {
    thread();
    if (closed) throw new IllegalStateException("Edit session is closed");
  }

  private static String revision(String revision) {
    if (Objects.requireNonNull(revision).isBlank())
      throw new IllegalArgumentException("Missing observed saved revision");
    return revision;
  }

  public Snapshot<T> snapshot() {
    thread();
    return new Snapshot<>(
        definition, context, id, generation, version, sourceRevision, current, jsonLimits);
  }

  public DraftTree baseline() {
    thread();
    return baseline;
  }

  public boolean dirty() {
    thread();
    return !current.sameValue(baseline);
  }

  public boolean canUndo() {
    thread();
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    thread();
    return !redo.isEmpty();
  }

  public int historySize() {
    thread();
    return undo.size() + redo.size();
  }

  /** Explicitly discard undo/redo without replacing the draft, baseline or pending operation. */
  public void discardHistory() {
    active();
    undo.clear();
    redo.clear();
    lastGroup = null;
  }

  public long historyCost() {
    thread();
    return java.util.stream.Stream.concat(undo.stream(), redo.stream())
        .mapToLong(History::cost)
        .sum();
  }

  public Optional<PendingState> pendingState() {
    thread();
    return Optional.ofNullable(pendingState);
  }

  public Optional<ConflictReview> review() {
    thread();
    return Optional.ofNullable(review);
  }

  public EditGroup beginGroup(FieldPath path) {
    active();
    current.at(path);
    return new EditGroup(path, UUID.randomUUID());
  }

  public void edit(
      FieldPath path, UnaryOperator<DraftNode> operation, String label, EditGroup group) {
    active();
    if (group != null && !group.path().equals(path))
      throw new IllegalArgumentException("Edit group belongs to another field");
    apply(current.change(path, operation), label, group);
  }

  public void edit(FieldPath path, UnaryOperator<DraftNode> operation, String label) {
    edit(path, operation, label, null);
  }

  public void setRaw(FieldPath path, String raw, EditGroup group) {
    edit(path, ignored -> new DraftNode.Scalar(raw), "Edit value", group);
  }

  public void setRaw(FieldPath path, String raw) {
    setRaw(path, raw, null);
  }

  /** Apply an explicit shape-checked action against its actual editor lifetime/draft. */
  public void execute(DraftEdit edit) {
    execute(edit, null);
  }

  public void execute(DraftEdit edit, EditGroup group) {
    active();
    var target = Objects.requireNonNull(edit).target();
    if (!target.session().equals(id)
        || target.generation() != generation
        || target.version().filter(expected -> expected != version).isPresent())
      throw new DraftEditRefusal(
          "edit.stale_target", target.path(), "Edit target belongs to a stale or foreign draft");
    if (group != null
        && (!(edit.action() instanceof DraftEdit.SetRaw
                || edit.action() instanceof DraftEdit.SetUnitRaw)
            || !group.path().equals(target.path())))
      throw new IllegalArgumentException("Only matching scalar typing may share an edit group");
    try {
      apply(DraftEdits.apply(definition.type(), current, edit), "Edit structure", group);
    } catch (IllegalArgumentException refusal) {
      throw new DraftEditRefusal("edit.invalid_action", target.path(), refusal.getMessage());
    }
  }

  public void stage(DraftTree imported, String label) {
    active();
    apply(new DraftTree(imported.root(), limits), label, null);
  }

  /** Reviewed whole-draft replacement; pending writes and unresolved conflicts remain separate. */
  public void stage(DraftTarget target, DraftTree imported, String label) {
    active();
    Objects.requireNonNull(target);
    if (!target.path().equals(FieldPath.ROOT) || target.version().isEmpty())
      throw new DraftEditRefusal(
          "import.target", target.path(), "Import requires an exact root target");
    if (!target.session().equals(id)
        || target.generation() != generation
        || target.version().orElseThrow() != version)
      throw new DraftEditRefusal(
          "import.stale_target",
          FieldPath.ROOT,
          "The reviewed draft changed; compare the import again");
    if (pending != null || review != null)
      throw new DraftEditRefusal(
          "import.pending",
          FieldPath.ROOT,
          "Complete the pending submission or conflict review before replacing this draft");
    Objects.requireNonNull(imported);
    long before = version;
    try {
      var next = new DraftTree(imported.root(), limits).reuseIdenticalInputFrom(current);
      apply(next, label, null);
    } catch (IllegalArgumentException refusal) {
      throw new DraftEditRefusal("import.invalid_draft", FieldPath.ROOT, refusal.getMessage());
    }
    // A reviewed replacement retires old input streams, without invalidating an in-flight save.
    // Pending submissions were refused above; ordinary low-level stage remains caller-managed.
    if (version != before) generation++;
  }

  public void revert() {
    active();
    apply(baseline, "Revert to observed", null);
  }

  public void reset() {
    active();
    apply(
        DraftTree.from(definition.type(), definition.defaults(), limits)
            .reuseUnchangedFrom(current),
        "Reset to defaults",
        null);
  }

  private void apply(DraftTree next, String label, EditGroup group) {
    if (Objects.requireNonNull(label).isBlank() || label.length() > 256)
      throw new IllegalArgumentException("Invalid history label");
    if (next.root().equals(current.root())) return;
    var before = current;
    var previous = undo.peekLast();
    if (group != null
        && group.equals(lastGroup)
        && previous != null
        && previous.after().root().equals(before.root())) {
      undo.removeLast();
      undo.addLast(new History(previous.before(), next, label, group));
    } else undo.addLast(new History(before, next, label, group));
    current = next;
    redo.clear();
    version++;
    lastGroup = group;
    trimHistory();
  }

  private void trimHistory() {
    while (historySize() > limits.maximumHistoryEntries()
        || historyCost() > limits.maximumHistoryCost()) {
      if (!undo.isEmpty()) undo.removeFirst();
      else if (!redo.isEmpty()) redo.removeFirst();
      else break;
    }
  }

  public boolean undo() {
    active();
    if (undo.isEmpty()) return false;
    var change = undo.removeLast();
    current = change.before();
    redo.addLast(change);
    version++;
    lastGroup = null;
    return true;
  }

  public boolean redo() {
    active();
    if (redo.isEmpty()) return false;
    var change = redo.removeLast();
    current = change.after();
    undo.addLast(change);
    version++;
    lastGroup = null;
    return true;
  }

  public Preparation<T> prepareSubmission() {
    thread();
    if (closed) return blocked(PreparationState.CLOSED, "session.closed", "This context is closed");
    if (review != null)
      return blocked(
          PreparationState.REVIEW_REQUIRED,
          "session.review",
          "Review the observed and draft values first");
    if (pending != null)
      return blocked(
          pendingState == PendingState.UNKNOWN
              ? PreparationState.UNKNOWN
              : PreparationState.PENDING,
          "session.pending",
          "The previous submission needs completion or reconciliation");
    var inspection = current.inspect(definition, jsonLimits);
    if (inspection.candidate().isEmpty())
      return new Preparation<>(PreparationState.INVALID, Optional.empty(), inspection.report());
    pending =
        new Submission<>(
            id, context, generation, version, sourceRevision, inspection.candidate().orElseThrow());
    pendingState = PendingState.PREPARED;
    lastGroup = null;
    return new Preparation<>(PreparationState.READY, Optional.of(pending), inspection.report());
  }

  private Preparation<T> blocked(PreparationState state, String code, String message) {
    return new Preparation<>(
        state,
        Optional.empty(),
        new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, message)), false));
  }

  private boolean current(Submission<T> submission) {
    return !closed
        && pending == submission
        && submission.session().equals(id)
        && submission.context().equals(context)
        && submission.generation() == generation
        && submission.candidate().definition() == definition;
  }

  public boolean markInFlight(Submission<T> submission) {
    active();
    if (!current(submission) || pendingState != PendingState.PREPARED) return false;
    pendingState = PendingState.IN_FLIGHT;
    return true;
  }

  public boolean discardPrepared(Submission<T> submission) {
    active();
    if (!current(submission) || pendingState != PendingState.PREPARED) return false;
    pending = null;
    pendingState = null;
    return true;
  }

  public boolean failed(Submission<T> submission, Failure failure) {
    active();
    if (!current(submission)) return false;
    if (Objects.requireNonNull(failure) == Failure.UNKNOWN) pendingState = PendingState.UNKNOWN;
    else {
      pending = null;
      pendingState = null;
    }
    return true;
  }

  /** Supply only an actual observed backend result; this model performs no write or rollback. */
  public Acknowledgement acknowledge(
      Submission<T> submission, DraftTree observed, String newRevision) {
    thread();
    if (!current(submission)) return Acknowledgement.STALE;
    revision(newRevision);
    final DraftTree actualTree, next;
    final List<History> nextUndo, nextRedo;
    final boolean needsReview;
    final ConflictReview nextReview;
    try {
      var observation = observed.inspect(definition, jsonLimits);
      if (observation.candidate().isEmpty()) return unavailableObservation();
      T actual = observation.candidate().orElseThrow().value();
      boolean equal = definition.type().equivalent(actual, submission.candidate().value());
      var normalized = new DraftTree(observed.root(), limits);
      var submitted = submission.candidate().draft();
      actualTree =
          equal ? normalized.alignRows(submitted, definition.type(), jsonLimits) : normalized;
      needsReview = !equal && !current.root().equals(submitted.root());
      next =
          equal
              ? current.retainLaterEdits(submitted, actualTree)
              : needsReview ? current : actualTree;
      // Compute both journals and the review before advancing any local state. In particular, a
      // normalized observation can fit alone but exceed the budget when combined with a late edit.
      nextUndo = needsReview ? List.copyOf(undo) : rebaseHistory(undo, submitted, actualTree);
      nextRedo = needsReview ? List.copyOf(redo) : rebaseHistory(redo, submitted, actualTree);
      nextReview =
          needsReview
              ? new ConflictReview(
                  id,
                  context,
                  generation + 1,
                  version + 1,
                  newRevision,
                  submitted,
                  actualTree,
                  next,
                  definition.type())
              : null;
    } catch (RuntimeException unavailable) {
      return unavailableObservation();
    }
    baseline = actualTree;
    current = next;
    sourceRevision = newRevision;
    generation++;
    version++;
    pending = null;
    pendingState = null;
    lastGroup = null;
    review = nextReview;
    undo.clear();
    undo.addAll(nextUndo);
    redo.clear();
    redo.addAll(nextRedo);
    trimHistory();
    return needsReview ? Acknowledgement.REVIEW_REQUIRED : Acknowledgement.ACCEPTED;
  }

  public Acknowledgement acknowledge(Submission<T> submission, T actual, String newRevision) {
    thread();
    if (!current(submission)) return Acknowledgement.STALE;
    final DraftTree observed;
    try {
      observed = DraftTree.from(definition.type(), actual, limits);
    } catch (RuntimeException unavailable) {
      return unavailableObservation();
    }
    return acknowledge(submission, observed, newRevision);
  }

  private Acknowledgement unavailableObservation() {
    pendingState = PendingState.OBSERVATION_UNAVAILABLE;
    return Acknowledgement.INVALID_OBSERVATION;
  }

  /** A save receipt can be known even when a qualified authorized readback is unavailable. */
  public boolean observationUnavailable(Submission<T> submission) {
    thread();
    if (!current(submission)) return false;
    unavailableObservation();
    return true;
  }

  private static List<History> rebaseHistory(
      Deque<History> journal, DraftTree submitted, DraftTree actual) {
    var changed = new ArrayList<History>();
    for (var entry : journal)
      changed.add(
          new History(
              entry.before().retainLaterEdits(submitted, actual),
              entry.after().retainLaterEdits(submitted, actual),
              entry.label(),
              entry.group()));
    return List.copyOf(changed);
  }

  public ConflictReview reviewConflict(DraftTree observed, String observedRevision) {
    active();
    if (pending != null)
      throw new IllegalStateException("Complete the pending operation before reviewing a conflict");
    limits.check(observed.root());
    revision(observedRevision);
    review =
        new ConflictReview(
            id,
            context,
            generation,
            version,
            observedRevision,
            baseline,
            observed,
            current,
            definition.type());
    return review;
  }

  public void resolve(ConflictReview selection, Map<FieldPath, ConflictReview.Choice> choices) {
    active();
    if (selection != review || !selection.matches(id, context, generation, version))
      throw new IllegalStateException("Review is stale; keep the draft and review current values");
    var resolved = selection.resolve(current, choices);
    baseline = selection.observed();
    sourceRevision = selection.observedRevision();
    current = resolved;
    review = null;
    generation++;
    version++;
    undo.clear();
    redo.clear();
    lastGroup = null;
  }

  public DiagnosticTarget locate(Submission<T> submission, SourcePath source) {
    thread();
    var path = submission.candidate().addresses().get(source);
    if (!submission.session().equals(id) || !submission.context().equals(context) || path == null)
      return new DiagnosticTarget(path, TargetState.FOREIGN);
    if (closed || submission.generation() != generation)
      return new DiagnosticTarget(path, TargetState.STALE_BASELINE);
    if (!current.contains(path)) return new DiagnosticTarget(path, TargetState.REMOVED);
    return new DiagnosticTarget(
        path,
        current.activeAt(path)
                && DraftNode.sameValue(current.at(path), submission.candidate().draft().at(path))
            ? TargetState.CURRENT
            : TargetState.STALE_VALUE);
  }

  public void reload(T saved, String observedRevision) {
    reload(DraftTree.from(definition.type(), saved, limits), observedRevision);
  }

  public void reload(DraftTree saved, String observedRevision) {
    active();
    limits.check(saved.root());
    revision(observedRevision);
    baseline = new DraftTree(saved.root(), limits);
    current = baseline;
    sourceRevision = observedRevision;
    generation++;
    version++;
    pending = null;
    pendingState = null;
    review = null;
    undo.clear();
    redo.clear();
    lastGroup = null;
  }

  @Override
  public void close() {
    thread();
    closed = true;
    generation++;
    undo.clear();
    redo.clear();
    pending = null;
    pendingState = null;
    review = null;
    lastGroup = null;
  }
}
