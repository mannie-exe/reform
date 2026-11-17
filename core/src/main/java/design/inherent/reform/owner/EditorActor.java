package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.edit.Submission;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Serialized worker editing for a bound document session. Widgets/commands read immutable views; no
 * editor validation, snapshot, history or acknowledgement callback runs on their native thread.
 */
public final class EditorActor<T> implements AutoCloseable {
  public enum Availability {
    LOADING,
    READY,
    UNAVAILABLE,
    CLOSED
  }

  public record View<T>(
      Availability availability,
      Optional<EditSession.Snapshot<T>> snapshot,
      boolean dirty,
      boolean canUndo,
      boolean canRedo,
      Optional<EditSession.PendingState> pending,
      Optional<ConflictReview> review,
      ValidationReport validation,
      ValidationReport observationReport,
      ValidationReport operationReport,
      Optional<CommitReceipt<T>> lastReceipt) {}

  /** A receipt remains valid even if the editor closes before its acknowledgement can be shown. */
  public record Save<T>(Optional<CommitReceipt<T>> receipt, ValidationReport editorReport) {}

  private final class Model {
    final EditSession<T> editor;
    OwnerObservation<T> source;
    Submission<T> pending;
    CommitReceipt<T> lastReceipt;
    EditSession.EditGroup typing;
    ValidationReport observationReport = ValidationReport.VALID;
    ValidationReport operationReport = ValidationReport.VALID;

    Model(EditSession<T> editor, OwnerObservation<T> source) {
      this.editor = editor;
      this.source = source;
    }
  }

  private final class SavePlan {
    final Model model;
    final Submission<T> submission;
    final OwnerObservation<T> baseline;
    final AtomicBoolean commitInvoked = new AtomicBoolean();

    SavePlan(Model model, Submission<T> submission) {
      this.model = model;
      this.submission = submission;
      baseline = model.source;
    }
  }

  private final Object lifetime = new Object();
  private final OwnerContext context;
  private final DocumentSession<T> source;
  private final DocumentDefinition<T> definition;
  private final JsonLimits jsonLimits;
  private final DraftLimits draftLimits;
  private final SerialLane edits;
  private final OwnerContext.CloseHook cleanup;
  private final ThreadLocal<Boolean> inside = new ThreadLocal<>();
  private final AtomicReference<View<T>> published =
      new AtomicReference<>(empty(Availability.LOADING, ValidationReport.VALID));
  private volatile boolean open = true;
  private volatile Model model;

  /** Owns the supplied document session, but never the shared document or borrowed executors. */
  public EditorActor(
      OwnerContext context,
      DocumentSession<T> source,
      DocumentDefinition<T> definition,
      JsonLimits jsonLimits,
      DraftLimits draftLimits,
      int maximumEdits) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.source = Objects.requireNonNull(source);
    if (!source.context().equals(context.id()) || source.definition() != definition)
      throw new IllegalArgumentException("Editor requires its actual context and owner definition");
    this.definition = Objects.requireNonNull(definition);
    this.jsonLimits = Objects.requireNonNull(jsonLimits);
    this.draftLimits = Objects.requireNonNull(draftLimits);
    edits = new SerialLane(maximumEdits);
    cleanup = context.onClose(this::close);
  }

  public View<T> view() {
    return published.get();
  }

  private void current() {
    context.requireCurrent();
    if (!open) throw new PolicyRefusal("editor.closed", "This editing workflow is closed");
  }

  private void owningTask() {
    if (!Boolean.TRUE.equals(inside.get()))
      throw new IllegalStateException("Editor requires its serialized worker task");
  }

  private <R> CompletionStage<R> act(Callable<R> action) {
    return edits.submit(
        () ->
            context.onWorker(
                () -> {
                  current();
                  inside.set(true);
                  try {
                    var result = action.call();
                    current();
                    return result;
                  } finally {
                    inside.remove();
                  }
                }));
  }

  private Model ready() {
    current();
    var result = model;
    if (result == null)
      throw new PolicyRefusal("editor.unavailable", "No editing baseline is ready");
    return result;
  }

  private View<T> publish(Model selected) {
    var snapshot = selected.editor.snapshot();
    var next =
        new View<>(
            Availability.READY,
            Optional.of(snapshot),
            selected.editor.dirty(),
            selected.editor.canUndo(),
            selected.editor.canRedo(),
            selected.editor.pendingState(),
            selected.editor.review(),
            snapshot.inspect().report(),
            selected.observationReport,
            selected.operationReport,
            Optional.ofNullable(selected.lastReceipt));
    synchronized (lifetime) {
      current();
      if (model != selected) throw new PolicyRefusal("editor.changed", "Editing context changed");
      published.set(next);
    }
    return next;
  }

  /** Explicit loading; it never initializes a file or silently discards intervening edits. */
  public CompletionStage<View<T>> load() {
    return act(() -> {
          var selected = model;
          return selected == null ? null : selected.editor.snapshot();
        })
        .thenCompose(
            expected ->
                source
                    .read()
                    .thenCompose(
                        observed ->
                            act(
                                () -> {
                                  if (!observed.context().equals(context.id()))
                                    throw new PolicyRefusal(
                                        "owner.context", "Observation belongs to another context");
                                  var selected = model;
                                  if (selected != null
                                      && (expected == null
                                          || selected.editor.snapshot().version()
                                              != expected.version()
                                          || selected.editor.snapshot().generation()
                                              != expected.generation()))
                                    throw new PolicyRefusal(
                                        "editor.changed",
                                        "Draft changed while loading; retained it");
                                  if (observed.document().draft().isEmpty()) {
                                    if (selected != null) {
                                      selected.observationReport = observed.document().report();
                                      return publish(selected);
                                    }
                                    var unavailable =
                                        EditorActor.<T>empty(
                                            Availability.UNAVAILABLE, observed.document().report());
                                    synchronized (lifetime) {
                                      current();
                                      published.set(unavailable);
                                    }
                                    return unavailable;
                                  }
                                  if (selected != null && selected.pending != null) {
                                    var accepted =
                                        selected.editor.acknowledge(
                                            selected.pending,
                                            observed.document().draft().orElseThrow(),
                                            observed.document().revision());
                                    if (accepted == EditSession.Acknowledgement.INVALID_OBSERVATION)
                                      return publish(selected);
                                    selected.pending = null;
                                    selected.source = observed;
                                    selected.observationReport = observed.document().report();
                                    return publish(selected);
                                  }
                                  if (selected != null && selected.editor.dirty()) {
                                    selected.editor.reviewConflict(
                                        observed.document().draft().orElseThrow(),
                                        observed.document().revision());
                                    selected.source = observed;
                                    selected.observationReport = observed.document().report();
                                    return publish(selected);
                                  }
                                  var created =
                                      new Model(
                                          EditSession.fromDraft(
                                              definition,
                                              observed.context(),
                                              observed.document().revision(),
                                              observed.document().draft().orElseThrow(),
                                              jsonLimits,
                                              draftLimits,
                                              this::owningTask),
                                          observed);
                                  synchronized (lifetime) {
                                    current();
                                    model = created;
                                  }
                                  created.observationReport = observed.document().report();
                                  return publish(created);
                                })));
  }

  private CompletionStage<View<T>> change(Consumer<Model> change) {
    return act(
        () -> {
          var selected = ready();
          change.accept(selected);
          return publish(selected);
        });
  }

  public CompletionStage<View<T>> setRaw(FieldPath path, String text) {
    Objects.requireNonNull(path);
    Objects.requireNonNull(text);
    return change(
        selected -> {
          if (selected.typing == null || !selected.typing.path().equals(path))
            selected.typing = selected.editor.beginGroup(path);
          selected.editor.setRaw(path, text, selected.typing);
        });
  }

  public CompletionStage<View<T>> undo() {
    return change(selected -> selected.editor.undo());
  }

  public CompletionStage<View<T>> redo() {
    return change(selected -> selected.editor.redo());
  }

  public CompletionStage<View<T>> revert() {
    return change(selected -> selected.editor.revert());
  }

  public CompletionStage<View<T>> reset() {
    return change(selected -> selected.editor.reset());
  }

  public CompletionStage<View<T>> resolve(
      ConflictReview review, Map<FieldPath, ConflictReview.Choice> choices) {
    var frozen = Map.copyOf(choices);
    return change(selected -> selected.editor.resolve(review, frozen));
  }

  public CompletionStage<Save<T>> save() {
    return act(() -> {
          var selected = ready();
          var prepared = selected.editor.prepareSubmission();
          publish(selected);
          if (prepared.submission().isEmpty()) throw new PolicyRefusal(prepared.report());
          selected.pending = prepared.submission().orElseThrow();
          return new SavePlan(selected, selected.pending);
        })
        .thenCompose(
            plan ->
                source
                    .prepare(plan.baseline, plan.submission.candidate().draft())
                    .thenCompose(
                        prepared ->
                            act(() -> {
                                  if (ready() != plan.model
                                      || !plan.model.editor.markInFlight(plan.submission))
                                    throw new PolicyRefusal(
                                        "editor.changed", "Submission is no longer current");
                                  publish(plan.model);
                                  return prepared;
                                })
                                .thenCompose(
                                    preparedCommit -> {
                                      plan.commitInvoked.set(true);
                                      return source.commit(preparedCommit);
                                    }))
                    .<CompletionStage<Save<T>>>handle(
                        (receipt, failed) ->
                            act(() -> {
                                  var selected = ready();
                                  if (selected != plan.model)
                                    throw new PolicyRefusal(
                                        "editor.changed", "Submission belongs to an older editor");
                                  if (failed != null) {
                                    boolean unknown =
                                        plan.commitInvoked.get() && !knownRefusal(failed);
                                    selected.editor.failed(
                                        plan.submission,
                                        unknown
                                            ? EditSession.Failure.UNKNOWN
                                            : EditSession.Failure.UNATTEMPTED);
                                    if (!unknown) selected.pending = null;
                                    selected.operationReport = report(failed);
                                  } else accept(selected, plan.submission, receipt);
                                  publish(selected);
                                  return new Save<>(
                                      Optional.ofNullable(receipt),
                                      failed == null ? ValidationReport.VALID : report(failed));
                                })
                                .exceptionally(
                                    unavailable ->
                                        new Save<>(
                                            Optional.ofNullable(receipt), report(unavailable))))
                    .thenCompose(stage -> stage));
  }

  private void accept(Model selected, Submission<T> submission, CommitReceipt<T> receipt) {
    selected.lastReceipt = receipt;
    selected.operationReport = receipt.persistenceReport().and(receipt.applicationReport());
    switch (receipt.persistence()) {
      case SAVED, UNCHANGED -> {
        var observed = receipt.observation();
        if (observed.isPresent() && observed.orElseThrow().document().draft().isPresent()) {
          var source = observed.orElseThrow();
          var accepted =
              selected.editor.acknowledge(
                  submission,
                  source.document().draft().orElseThrow(),
                  source.document().revision());
          if (accepted == EditSession.Acknowledgement.ACCEPTED
              || accepted == EditSession.Acknowledgement.REVIEW_REQUIRED) {
            selected.source = source;
            selected.observationReport = source.document().report();
            selected.pending = null;
          }
        } else selected.editor.observationUnavailable(submission);
      }
      case UNKNOWN -> selected.editor.failed(submission, EditSession.Failure.UNKNOWN);
      default -> {
        selected.editor.failed(submission, EditSession.Failure.REFUSED);
        selected.pending = null;
        receipt
            .observation()
            .ifPresent(
                observed -> {
                  if (observed.document().draft().isPresent()) {
                    selected.editor.reviewConflict(
                        observed.document().draft().orElseThrow(), observed.document().revision());
                    selected.source = observed;
                    selected.observationReport = observed.document().report();
                  }
                });
      }
    }
  }

  @Override
  public void close() {
    synchronized (lifetime) {
      if (!open) return;
      open = false;
      model = null;
      published.set(empty(Availability.CLOSED, ValidationReport.VALID));
    }
    source.close();
    edits.close();
    cleanup.close();
  }

  private static ValidationReport report(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return failure instanceof PolicyRefusal refused
        ? refused.report()
        : new ValidationReport(
            List.of(
                Issue.error(
                    "editor.unavailable", FieldPath.ROOT, "Editing operation is unavailable")),
            false);
  }

  private static boolean knownRefusal(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    if (!(failure instanceof PolicyRefusal refused) || refused.report().issues().isEmpty())
      return false;
    return refused.report().issues().stream()
        .allMatch(
            issue ->
                List.of(
                        "owner.busy",
                        "owner.closed",
                        "owner.permission",
                        "owner.revision",
                        "owner.session",
                        "owner.consumed")
                    .contains(issue.code()));
  }

  private static <T> View<T> empty(Availability availability, ValidationReport report) {
    return new View<>(
        availability,
        Optional.empty(),
        false,
        false,
        false,
        Optional.empty(),
        Optional.empty(),
        report,
        ValidationReport.VALID,
        ValidationReport.VALID,
        Optional.empty());
  }
}
