package design.inherent.reform.operation;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Complete preflight and explicitly ordered submission over borrowed real editors. All bindings
 * share an actual owner thread; their contexts, sources, callbacks and receipts remain separate.
 * This service is not a cross-file transaction, rollback journal or remote administration protocol.
 */
public final class DocumentBatch implements AutoCloseable {
  public enum Intent {
    CHECK,
    COMMIT
  }

  public record Limits(
      int maximumDocuments, int retainedReviews, int maximumCharacters, int maximumIssues) {
    public static final Limits STANDARD =
        new Limits(
            32,
            2,
            32 * DocumentReview.Limits.STANDARD.maximumCharacters(),
            32 * DocumentReview.Limits.STANDARD.maximumFields());

    public Limits {
      if (maximumDocuments < 1 || retainedReviews < 1 || maximumCharacters < 1 || maximumIssues < 1)
        throw new IllegalArgumentException("Batch limits must be positive");
    }
  }

  /**
   * Identity-bound typed candidate key. Creating a second key with the same name grants nothing.
   */
  public static final class Member<T> {
    private final DocumentActions<T> actions;
    private final Intent intent;

    private Member(DocumentActions<T> actions, Intent intent) {
      this.actions = Objects.requireNonNull(actions);
      this.intent = Objects.requireNonNull(intent);
    }

    public DocumentId document() {
      return actions.definition().id();
    }

    public Intent intent() {
      return intent;
    }
  }

  public static <T> Member<T> member(DocumentActions<T> actions, Intent intent) {
    return new Member<>(actions, intent);
  }

  private record Slot<T>(
      Member<T> member, EditorActor.Preflight<T> preflight, DocumentReview review) {}

  /** Immutable complete candidate values, defensively copied through their actual definitions. */
  public static final class Values {
    private final Map<Member<?>, Slot<?>> slots;

    private Values(List<Slot<?>> values) {
      var mapped = new LinkedHashMap<Member<?>, Slot<?>>();
      values.forEach(slot -> mapped.put(slot.member(), slot));
      slots = Collections.unmodifiableMap(mapped);
    }

    public <T> Optional<T> candidate(Member<T> member) {
      var slot = slots.get(Objects.requireNonNull(member));
      if (slot == null || slot.member() != member)
        throw new IllegalArgumentException("This candidate key is not in this batch capture");
      // Slot construction captures the same Member<T> and its own DocumentActions<T> result.
      @SuppressWarnings("unchecked")
      var typed = (EditorActor.Preflight<T>) slot.preflight();
      return typed.candidate().map(Candidate::value);
    }

    public List<DocumentId> documents() {
      return slots.keySet().stream().map(Member::document).toList();
    }

    /** An exact registered definition is an alternative typed key for composed frontends. */
    public <T> Optional<T> candidate(DocumentDefinition<T> definition) {
      Objects.requireNonNull(definition);
      for (var slot : slots.values()) {
        if (slot.member().actions.definition() != definition) continue;
        // The actual definition instance carries this slot's T, not merely a matching name.
        @SuppressWarnings("unchecked")
        var typed = (EditorActor.Preflight<T>) slot.preflight();
        return typed.candidate().map(Candidate::value);
      }
      throw new IllegalArgumentException("This definition is not in this batch capture");
    }
  }

  /** Per-document field diagnostics; incomplete required checks never pass. */
  public record Check(Map<DocumentId, ValidationReport> documents, boolean complete) {
    public static final Check VALID = new Check(Map.of(), true);

    public Check {
      documents = Collections.unmodifiableMap(new LinkedHashMap<>(documents));
      documents.forEach(
          (key, value) -> {
            Objects.requireNonNull(key);
            Objects.requireNonNull(value);
          });
    }

    public boolean valid() {
      return complete && documents.values().stream().allMatch(ValidationReport::valid);
    }
  }

  @FunctionalInterface
  public interface Validator {
    /**
     * Worker-only bounded pure cross-document checks; no native queries, source writes or widgets.
     */
    Check validate(Values values);
  }

  public record Entry(
      DocumentId document, Intent intent, DocumentReview review, ValidationReport preflight) {}

  /** Issued immutable review data. The private consumption state never grants source authority. */
  public static final class Review {
    private final Object issuer;
    private final UUID id = UUID.randomUUID();
    private final List<Slot<?>> slots;
    private final Values values;
    private final Check check;
    private final boolean checksAttempted;
    private volatile boolean consumed;

    private Review(Object issuer, List<Slot<?>> slots, Check check, boolean checksAttempted) {
      this.issuer = issuer;
      this.slots = List.copyOf(slots);
      values = new Values(slots);
      this.check = Objects.requireNonNull(check);
      this.checksAttempted = checksAttempted;
    }

    public UUID id() {
      return id;
    }

    public Values values() {
      return values;
    }

    public Check check() {
      return check;
    }

    public boolean checksAttempted() {
      return checksAttempted;
    }

    public boolean consumed() {
      return consumed;
    }

    public List<Entry> entries() {
      return slots.stream()
          .map(
              slot ->
                  new Entry(
                      slot.member().document(),
                      slot.member().intent(),
                      slot.review(),
                      slot.preflight().report()))
          .toList();
    }

    public boolean valid() {
      return checksAttempted
          && check.valid()
          && slots.stream()
              .allMatch(
                  slot ->
                      slot.preflight().report().valid()
                          && slot.preflight().candidate().isPresent());
    }
  }

  /** A member's actual operation is retained even if its effect is partial, unknown or refused. */
  public record Outcome(
      DocumentId document,
      Intent intent,
      Optional<OperationSnapshot<?>> operation,
      ValidationReport report) {
    public Outcome {
      Objects.requireNonNull(document);
      Objects.requireNonNull(intent);
      Objects.requireNonNull(operation);
      Objects.requireNonNull(report);
      if (operation.isPresent()
          && !operation.orElseThrow().target().filter(document::equals).isPresent())
        throw new IllegalArgumentException("Outcome operation belongs to another document");
    }
  }

  /** Completion covers requested owner operations, not runtime activation of staged settings. */
  public record Result(
      UUID review,
      List<Outcome> outcomes,
      boolean completed,
      ValidationReport report,
      Optional<Review> currentPreflight) {
    public Result {
      Objects.requireNonNull(review);
      outcomes = List.copyOf(outcomes);
      Objects.requireNonNull(report);
      Objects.requireNonNull(currentPreflight);
      if (completed && !report.valid())
        throw new IllegalArgumentException("Completed batch has a failed report");
    }
  }

  private static final RecordType<DocumentActions.Empty> EMPTY =
      RecordType.<DocumentActions.Empty>builder(ignored -> DocumentActions.Empty.VALUE).build();
  private final OwnerContext context;
  private final OwnerContext.Access access;
  private final List<Member<?>> members;
  private final Validator validator;
  private final Limits limits;
  private final OperationScope scope;
  private final OperationDescriptor<DocumentActions.Empty, Review> review;
  private final OperationDescriptor<UUID, Result> commit;
  private final Map<UUID, Review> reviews = new LinkedHashMap<>();
  private final OwnerContext.CloseHook cleanup;
  private boolean closed;
  private volatile boolean committing;

  public DocumentBatch(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentId id,
      List<Member<?>> members,
      Validator validator,
      Limits limits) {
    this.context = Objects.requireNonNull(context);
    this.access = Objects.requireNonNull(access);
    context.requireOwner();
    if (!context.id().equals(access.context()))
      throw new IllegalArgumentException("Batch access must belong to its actual context");
    Objects.requireNonNull(id);
    this.limits = Objects.requireNonNull(limits);
    this.members = List.copyOf(members);
    this.validator = Objects.requireNonNull(validator);
    if (this.members.isEmpty() || this.members.size() > limits.maximumDocuments())
      throw new IllegalArgumentException("Batch document count is outside its bound");
    var documents = new java.util.HashSet<DocumentId>();
    for (var member : this.members) {
      member.actions.requireBatchThread();
      if (!documents.add(member.document()))
        throw new IllegalArgumentException("Duplicate batch document");
    }
    scope =
        new OperationScope(
            context,
            access,
            new OperationScope.Limits(2, 2, 8, OperationScope.Limits.STANDARD.input()));
    var entries =
        Set.of(
            OperationDescriptor.EntryPoint.GUI,
            OperationDescriptor.EntryPoint.COMMAND,
            OperationDescriptor.EntryPoint.INTERNAL);
    review =
        new OperationDescriptor<>(
            new DocumentId(id.namespace(), id.path() + "/review"),
            EMPTY,
            OwnerContext.Right.READ,
            Set.of(OperationDescriptor.Effect.INSPECT),
            entries,
            Optional.of(id));
    commit =
        new OperationDescriptor<>(
            new DocumentId(id.namespace(), id.path() + "/commit"),
            ValueTypes.uuid(),
            OwnerContext.Right.WRITE,
            Set.of(OperationDescriptor.Effect.PERSIST, OperationDescriptor.Effect.APPLY),
            entries,
            Optional.of(id));
    try {
      scope.register(review, (execution, ignored) -> capture(execution));
      scope.register(commit, this::submit);
      scope.freeze();
      cleanup = context.onClose(this::close);
    } catch (RuntimeException | Error failed) {
      scope.close();
      throw failed;
    }
  }

  public DocumentBatch(
      OwnerContext context, OwnerContext.Access access, DocumentId id, List<Member<?>> members) {
    this(context, access, id, members, ignored -> Check.VALID, Limits.STANDARD);
  }

  public OperationHandle<Review> review(OperationDescriptor.EntryPoint entry) {
    requireOpen();
    return scope.submit(review, entry, DocumentActions.Empty.VALUE);
  }

  public OperationHandle<Result> commit(OperationDescriptor.EntryPoint entry, Review confirmed) {
    requireOpen();
    if (confirmed.issuer != this || reviews.get(confirmed.id()) != confirmed)
      throw new PolicyRefusal("batch.review", "Use this batch's retained review");
    return scope.submit(commit, entry, confirmed.id());
  }

  public Optional<OperationHandle<?>> operation(UUID id) {
    return scope.find(id);
  }

  public boolean hasActiveOperations() {
    return scope.hasActiveOperations();
  }

  /** Actual READ disclosure admission, including when a prior save requires reconciliation. */
  public void requireRead() {
    requireOpen();
    access.require(OwnerContext.Right.READ);
    for (var member : members) member.actions.requireBatchRead();
  }

  /** Cached identity/authority check only. Submission still repeats backend preflight. */
  public ValidationReport submissionReadiness(Review confirmed) {
    requireOpen();
    try {
      access.require(OwnerContext.Right.WRITE);
      if (confirmed.issuer != this
          || reviews.get(confirmed.id()) != confirmed
          || confirmed.consumed)
        throw new PolicyRefusal("batch.review", "Use this batch's unconsumed retained review");
      if (!confirmed.valid())
        throw new PolicyRefusal("batch.invalid", "Complete batch preflight did not pass");
      if (committing)
        throw new PolicyRefusal("batch.busy", "Another ordered submission is still active");
      requireTargets(confirmed, Set.of(), true);
      return ValidationReport.VALID;
    } catch (PolicyRefusal refused) {
      return refused.report();
    }
  }

  private void requireOpen() {
    context.requireOwner();
    if (closed) throw new PolicyRefusal("batch.closed", "This batch service is closed");
  }

  private <T> CompletionStage<Slot<T>> capture(Member<T> member) {
    member.actions.requireBatchThread();
    return DocumentActions.result(member.actions.preflight(OperationDescriptor.EntryPoint.INTERNAL))
        .thenCompose(
            prepared ->
                context.onWorker(
                    () -> new Slot<>(member, prepared, member.actions.reviewPreflight(prepared))));
  }

  private CompletionStage<Review> capture(OperationScope.Execution execution) {
    var slots = new ArrayList<Slot<?>>();
    CompletionStage<Void> sequence = CompletableFuture.completedStage(null);
    for (var member : members)
      sequence =
          sequence.thenCompose(
              ignored ->
                  context
                      .onOwner(
                          () -> {
                            requireOpen();
                            execution.checkpoint();
                            return capture(member);
                          })
                      .thenCompose(stage -> stage)
                      .thenAccept(slots::add)
                      .exceptionally(
                          failed -> {
                            throw targeted(member.document(), failed);
                          }));
    return sequence
        .thenCompose(
            ignored ->
                context.onWorker(
                    () -> {
                      execution.checkpoint();
                      var budget = new Budget();
                      for (var slot : slots) budget.slot(slot);
                      boolean ready =
                          slots.stream()
                              .allMatch(
                                  slot ->
                                      slot.preflight().report().valid()
                                          && slot.preflight().candidate().isPresent());
                      var check =
                          ready
                              ? Objects.requireNonNull(validator.validate(new Values(slots)))
                              : new Check(Map.of(), false);
                      budget.check(check);
                      return new Review(this, slots, check, ready);
                    }))
        .thenCompose(
            captured ->
                context.onOwner(
                    () -> {
                      requireOpen();
                      access.require(OwnerContext.Right.READ);
                      execution.checkpoint();
                      requireTargets(captured, Set.of(), false);
                      while (reviews.size() >= limits.retainedReviews())
                        reviews.remove(reviews.keySet().iterator().next());
                      reviews.put(captured.id(), captured);
                      return captured;
                    }));
  }

  private void requireTargets(Review plan, Set<DocumentId> completed, boolean writes) {
    for (var slot : plan.slots) {
      if (completed.contains(slot.member().document())) continue;
      try {
        slot.member()
            .actions
            .requireBatch(
                writes && slot.member().intent() == Intent.COMMIT
                    ? OwnerContext.Right.WRITE
                    : OwnerContext.Right.READ,
                Optional.of(slot.preflight().target()));
        if (slot.member().intent() == Intent.CHECK && slot.member().actions.view().dirty())
          throw new PolicyRefusal(
              "batch.guard_dirty", "A check-only document has an unsaved draft");
      } catch (RuntimeException failed) {
        throw targeted(slot.member().document(), failed);
      }
    }
  }

  private CompletionStage<Result> submit(OperationScope.Execution execution, UUID id) {
    requireOpen();
    var confirmed = reviews.get(id);
    if (confirmed == null || confirmed.issuer != this || confirmed.consumed)
      throw new PolicyRefusal(
          "batch.review", "This review is expired, foreign or already consumed");
    if (committing)
      throw new PolicyRefusal("batch.busy", "Another ordered submission is still active");
    confirmed.consumed = true;
    if (!confirmed.valid())
      throw new PolicyRefusal("batch.invalid", "Complete batch preflight did not pass");
    committing = true;
    var run = new Run(confirmed, execution);
    // Repeat every backend check before any effect. All original reviewed identities must survive.
    return capture(execution)
        .thenCompose(
            fresh ->
                context
                    .onOwner(
                        () -> {
                          run.currentPreflight = fresh;
                          requireOpen();
                          access.require(OwnerContext.Right.WRITE);
                          execution.checkpoint();
                          if (!fresh.valid())
                            throw new PolicyRefusal(
                                "batch.invalid", "Current complete batch preflight did not pass");
                          for (int i = 0; i < confirmed.slots.size(); i++)
                            if (!confirmed
                                .slots
                                .get(i)
                                .preflight()
                                .target()
                                .equals(fresh.slots.get(i).preflight().target()))
                              throw targeted(
                                  confirmed.slots.get(i).member().document(),
                                  new PolicyRefusal(
                                      "editor.review_changed",
                                      "A reviewed candidate changed before submission"));
                          requireTargets(confirmed, Set.of(), true);
                          return run.next(0);
                        })
                    .thenCompose(stage -> stage))
        .handle(
            (result, failed) -> {
              committing = false;
              return failed == null ? result : run.stopped(report(failed));
            });
  }

  private final class Run {
    final Review confirmed;
    final OperationScope.Execution execution;
    final List<Outcome> outcomes = new ArrayList<>();
    final Set<DocumentId> completed = new java.util.HashSet<>();
    Review currentPreflight;

    Run(Review confirmed, OperationScope.Execution execution) {
      this.confirmed = confirmed;
      this.execution = execution;
    }

    CompletionStage<Result> next(int index) {
      if (index == confirmed.slots.size()) {
        execution.progress(
            new OperationSnapshot.Progress(
                "saving",
                completed.size(),
                OptionalLong.of(
                    members.stream().filter(member -> member.intent() == Intent.COMMIT).count()),
                "documents"));
        return CompletableFuture.completedStage(
            new Result(
                confirmed.id(),
                outcomes,
                true,
                ValidationReport.VALID,
                Optional.ofNullable(currentPreflight)));
      }
      return context
          .onOwner(
              () -> {
                requireOpen();
                access.require(OwnerContext.Right.WRITE);
                execution.checkpoint();
                requireTargets(confirmed, completed, true);
                var slot = confirmed.slots.get(index);
                if (slot.member().intent() == Intent.CHECK) {
                  outcomes.add(
                      new Outcome(
                          slot.member().document(),
                          Intent.CHECK,
                          Optional.empty(),
                          ValidationReport.VALID));
                  // Guard-only documents stay in future target checks until the ordered run
                  // completes.
                  return next(index + 1);
                }
                execution.progress(
                    new OperationSnapshot.Progress(
                        "saving",
                        completed.size(),
                        OptionalLong.of(
                            members.stream()
                                .filter(member -> member.intent() == Intent.COMMIT)
                                .count()),
                        "documents"));
                var operation =
                    slot.member()
                        .actions
                        .commit(OperationDescriptor.EntryPoint.INTERNAL, slot.preflight().target());
                return operation
                    .completion()
                    .thenCompose(
                        observed -> {
                          ValidationReport status;
                          try {
                            status = outcomeReport(observed);
                          } catch (RuntimeException malformedReceipt) {
                            status = report(malformedReceipt);
                          }
                          outcomes.add(
                              new Outcome(
                                  slot.member().document(),
                                  Intent.COMMIT,
                                  Optional.of(observed),
                                  status));
                          if (!status.valid())
                            return CompletableFuture.completedStage(stopped(status));
                          completed.add(slot.member().document());
                          return next(index + 1);
                        });
              })
          .thenCompose(stage -> stage);
    }

    Result stopped(ValidationReport failure) {
      var known = new ArrayList<>(outcomes);
      var already =
          known.stream().map(Outcome::document).collect(java.util.stream.Collectors.toSet());
      for (var member : members)
        if (!already.contains(member.document()))
          known.add(
              new Outcome(
                  member.document(),
                  member.intent(),
                  Optional.empty(),
                  new ValidationReport(
                      List.of(
                          Issue.error(
                              "batch.not_attempted",
                              FieldPath.ROOT,
                              "No batch operation was submitted for this document")),
                      false)));
      return new Result(
          confirmed.id(), known, false, failure, Optional.ofNullable(currentPreflight));
    }
  }

  private static ValidationReport outcomeReport(OperationSnapshot<?> observed) {
    if (observed.result().orElse(null) instanceof EditorActor.Commit<?> result) {
      var report = result.editorReport();
      if (result.receipt().isEmpty())
        return report.valid()
            ? failure("batch.no_receipt", "No owner receipt was produced")
            : report;
      var receipt = result.receipt().orElseThrow();
      report = report.and(receipt.persistenceReport()).and(receipt.applicationReport());
      boolean admitted = receipt.acknowledgesBaseline() && !receipt.baselineOutcomeUnknown();
      boolean finished =
          receipt.application() == CommitReceipt.Application.APPLIED
              || receipt.application() == CommitReceipt.Application.STAGED
              || receipt.application() == CommitReceipt.Application.NOT_ATTEMPTED
                  && (receipt.persistence() == CommitReceipt.Persistence.SAVED
                      || receipt.persistence() == CommitReceipt.Persistence.UNCHANGED);
      return admitted && finished
          ? report
          : report.and(
              failure("batch.owner_result", "The owner result requires review or reconciliation"));
    }
    return observed.report().valid()
        ? failure("batch.operation", "The owner operation did not produce a commit result")
        : observed.report();
  }

  private static ValidationReport failure(String code, String message) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, message)), false);
  }

  private static ValidationReport report(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return failed instanceof PolicyRefusal refusal
        ? refusal.report()
        : failure("batch.unavailable", "The batch operation could not complete");
  }

  private static PolicyRefusal targeted(DocumentId document, Throwable failed) {
    var problem = report(failed);
    return new PolicyRefusal(
        new ValidationReport(
            problem.issues().stream()
                .map(
                    issue -> {
                      var arguments = new LinkedHashMap<>(issue.arguments());
                      arguments.put("document", document.toString());
                      return new Issue(
                          issue.code(),
                          issue.severity(),
                          issue.path(),
                          issue.related(),
                          arguments,
                          issue.fallback());
                    })
                .toList(),
            problem.complete()));
  }

  private final class Budget {
    long characters;
    int issues;

    void text(String value) {
      characters += value.length();
      if (characters > limits.maximumCharacters())
        throw new PolicyRefusal("batch.size", "Complete batch review exceeds its character bound");
    }

    void report(ValidationReport report) {
      if (report.issues().size() > limits.maximumIssues() - issues)
        throw new PolicyRefusal("batch.issues", "Complete batch diagnostics exceed their bound");
      issues += report.issues().size();
      for (var issue : report.issues()) {
        text(issue.code());
        text(issue.fallback());
        text(issue.path().toString());
        issue.related().forEach(path -> text(path.toString()));
        issue
            .arguments()
            .forEach(
                (key, value) -> {
                  text(key);
                  text(value);
                });
      }
    }

    void slot(Slot<?> slot) {
      text(slot.member().document().toString());
      slot.preflight().candidate().ifPresent(value -> text(value.authoredJson()));
      for (var entry : slot.review().entries()) {
        text(entry.selector());
        text(entry.baseline());
        text(entry.draft());
        entry.effective().ifPresent(this::text);
        entry.application().ifPresent(rule -> text(rule.explanation()));
      }
      report(slot.preflight().report());
      report(slot.review().report());
    }

    void check(Check check) {
      for (var entry : check.documents().entrySet()) {
        if (members.stream().noneMatch(member -> member.document().equals(entry.getKey())))
          throw new PolicyRefusal(
              "batch.diagnostic_target",
              "Cross-document diagnostics require a member of this batch");
        text(entry.getKey().toString());
        report(entry.getValue());
      }
    }
  }

  @Override
  public void close() {
    if (closed) return;
    if (context.isOpen()) context.checkOwnerThread();
    closed = true;
    reviews.clear();
    try {
      scope.close();
    } finally {
      cleanup.close();
    }
  }
}
