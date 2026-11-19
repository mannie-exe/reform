package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.CommitReceipt.Application;
import design.inherent.reform.owner.CommitReceipt.Persistence;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.TextStore;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A context-owned text document. All actor sessions share one bounded persistence/publication lane.
 * Native gateways supply actual actor access; a snapshot or proposal is never an authority grant.
 */
public final class FileOwner<T> {
  private static final String STARTUP_EXPLANATION =
      "Read at the next game or dedicated-server process start. Saved choices do not change this"
          + " process's active selection.";
  private static final List<CommitReceipt.Requirement> STARTUP_REQUIREMENTS =
      List.of(
          new CommitReceipt.Requirement(
              FieldPath.ROOT, CommitReceipt.RequirementKind.GAME_RESTART, STARTUP_EXPLANATION));

  /** An authorized observation, bound to this document and this particular actor session. */
  public static final class Observation<T> implements OwnerObservation<T> {
    private final Object owner;
    private final UUID context, session;
    private final TextStore.Read<T> saved;
    private final SettingsHandle.View<T> effective;
    private final SourceInfo provenance;
    private final ApplicationEffects applicationEffects;

    private Observation(
        Object owner,
        UUID context,
        UUID session,
        TextStore.Read<T> saved,
        SettingsHandle.View<T> effective,
        SourceInfo provenance,
        ApplicationEffects applicationEffects) {
      this.owner = owner;
      this.context = context;
      this.session = session;
      this.saved = saved;
      this.effective = effective;
      this.provenance = provenance;
      this.applicationEffects = applicationEffects;
    }

    public UUID context() {
      return context;
    }

    public UUID session() {
      return session;
    }

    @Override
    public DocumentRead<T> document() {
      return saved;
    }

    public TextStore.Read<T> saved() {
      return saved;
    }

    public SettingsHandle.View<T> effective() {
      return effective;
    }

    @Override
    public SourceInfo provenance() {
      return provenance;
    }

    @Override
    public ApplicationEffects applicationEffects() {
      return applicationEffects;
    }

    @Override
    public Optional<SettingsHandle.View<T>> effectiveView() {
      return Optional.of(effective);
    }
  }

  private record Proposal<T>(Observation<T> baseline, Candidate<T> candidate) {}

  private record Initialization<T>(TextStore<T> storage, SettingsHandle.Revision expected) {}

  private record Applied(Application state, ValidationReport report) {}

  /** A one-shot complete candidate; persistence and actual authorization still have to succeed. */
  public static final class Prepared<T> implements PreparedEdit<T> {
    private final Object owner;
    private final UUID session, context;
    private final DocumentId document;
    private Proposal<T> proposal;

    private Prepared(
        Object owner, UUID session, UUID context, DocumentId document, Proposal<T> proposal) {
      this.owner = owner;
      this.session = session;
      this.context = context;
      this.document = document;
      this.proposal = proposal;
    }

    @Override
    public UUID context() {
      return context;
    }

    @Override
    public UUID session() {
      return session;
    }

    @Override
    public DocumentId document() {
      return document;
    }

    private synchronized Proposal<T> take(Object expectedOwner, UUID expectedSession) {
      if (owner != expectedOwner || !session.equals(expectedSession))
        throw new PolicyRefusal("owner.session", "Proposal belongs to another document or session");
      if (proposal == null)
        throw new PolicyRefusal("owner.consumed", "Commit proposal was already submitted");
      var result = proposal;
      proposal = null;
      return result;
    }
  }

  /**
   * Independent disk/application facts. A revoked reader receives no newly observed file contents.
   * NOT_ATTEMPTED and UNKNOWN are distinct; neither means that an earlier save was rolled back.
   */
  public record Receipt<T>(
      Persistence persistence,
      Application application,
      Optional<String> savedRevision,
      Optional<Observation<T>> observation,
      ValidationReport persistenceReport,
      ValidationReport applicationReport,
      List<Requirement> requirements)
      implements CommitReceipt<T> {
    public Receipt {
      Objects.requireNonNull(persistence);
      Objects.requireNonNull(application);
      Objects.requireNonNull(savedRevision);
      Objects.requireNonNull(observation);
      Objects.requireNonNull(persistenceReport);
      Objects.requireNonNull(applicationReport);
      requirements = List.copyOf(requirements);
    }
  }

  public final class Session implements DocumentSession<T> {
    private final UUID id = UUID.randomUUID();
    private final OwnerContext.Access access;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private Session(OwnerContext.Access access) {
      this.access = access;
    }

    @Override
    public DocumentId document() {
      return runtime.handle().document();
    }

    @Override
    public DocumentDefinition<T> definition() {
      current();
      return storage.definition();
    }

    @Override
    public UUID context() {
      return context.id();
    }

    @Override
    public Set<Operation> operations() {
      return startup
          ? Set.of(Operation.READ, Operation.COMMIT)
          : Set.of(Operation.READ, Operation.COMMIT, Operation.APPLY_SAVED);
    }

    public UUID id() {
      return id;
    }

    public boolean isOpen() {
      return open.get() && access.isOpen();
    }

    private void current() {
      access.requireCurrent();
      if (!open.get()) throw new PolicyRefusal("owner.closed", "This document session is closed");
    }

    private void require(OwnerContext.Right right) {
      current();
      access.require(right);
      current();
    }

    @SuppressWarnings(
        "unchecked") // Identity and session are checked before the backend-specific cast.
    private Observation<T> ownObservation(OwnerObservation<T> input) {
      if (!(input instanceof Observation<?> observed)
          || observed.owner != identity
          || !observed.session.equals(id))
        throw new PolicyRefusal(
            "owner.session", "Observation belongs to another document or session");
      return (Observation<T>) observed;
    }

    private void baseline(Observation<T> observed) {
      Objects.requireNonNull(observed);
      if (observed.owner != identity || !observed.session.equals(id))
        throw new PolicyRefusal(
            "owner.session", "Observation belongs to another document or session");
      current();
    }

    private void revision(SettingsHandle.Revision expected) {
      revision(expected, OwnerContext.Right.WRITE);
    }

    private void revision(SettingsHandle.Revision expected, OwnerContext.Right right) {
      require(right);
      if (expected != runtime.handle().view().revision())
        throw new PolicyRefusal("owner.revision", "Effective values changed; observe them again");
    }

    /** No default initialization or effective publication occurs when browsing saved data. */
    public CompletionStage<Observation<T>> read() {
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.READ);
                        return storage;
                      })
                  .thenCompose(source -> context.onWorker(source::read))
                  .thenCompose(
                      saved ->
                          context.onOwner(
                              () -> {
                                require(OwnerContext.Right.READ);
                                return observation(saved);
                              })));
    }

    /** Complete pure preparation runs on the explicit worker, not on the widget/native thread. */
    public CompletionStage<Prepared<T>> prepare(OwnerObservation<T> input, DraftTree draft) {
      final Observation<T> observed;
      try {
        observed = ownObservation(input);
      } catch (PolicyRefusal refused) {
        return CompletableFuture.<Prepared<T>>failedFuture(refused).minimalCompletionStage();
      }
      Objects.requireNonNull(draft);
      return context
          .onOwner(
              () -> {
                baseline(observed);
                revision(observed.effective.revision(), OwnerContext.Right.READ);
                return storage;
              })
          .thenCompose(
              source ->
                  context.onWorker(
                      () -> {
                        current();
                        var inspected = source.inspectDraft(draft);
                        if (inspected.candidate().isEmpty())
                          throw new PolicyRefusal(inspected.report());
                        return inspected.candidate().orElseThrow();
                      }))
          .thenCompose(
              candidate ->
                  context.onOwner(
                      () -> {
                        baseline(observed);
                        revision(observed.effective.revision(), OwnerContext.Right.READ);
                        return new Prepared<>(
                            identity,
                            id,
                            context.id(),
                            document(),
                            new Proposal<>(observed, candidate));
                      }));
    }

    /** Commit admission checks actual WRITE permission after earlier document operations finish. */
    public CompletionStage<Receipt<T>> commit(PreparedEdit<T> input) {
      if (!(Objects.requireNonNull(input) instanceof Prepared<?> prepared))
        throw new PolicyRefusal("owner.session", "Preparation belongs to another backend");
      @SuppressWarnings(
          "unchecked") // Exact owner/session is verified by take before returning the proposal.
      var proposal = ((Prepared<T>) prepared).take(identity, id);
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        baseline(proposal.baseline());
                        revision(proposal.baseline().effective.revision());
                        return storage;
                      })
                  .<CompletionStage<Receipt<T>>>handle(
                      (source, failure) ->
                          failure != null
                              ? CompletableFuture.completedStage(unattempted(failure))
                              : write(
                                  () ->
                                      source.write(
                                          proposal.baseline().saved,
                                          proposal.candidate(),
                                          this::current),
                                  proposal.baseline().effective.revision()))
                  .thenCompose(stage -> stage));
    }

    /**
     * Explicit admitted initialization. Opening a form or reading a missing file never does this.
     */
    public CompletionStage<Receipt<T>> initialize() {
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.WRITE);
                        return new Initialization<>(storage, runtime.handle().view().revision());
                      })
                  .<CompletionStage<Receipt<T>>>handle(
                      (admitted, failure) ->
                          failure != null
                              ? CompletableFuture.completedStage(unattempted(failure))
                              : write(
                                  () ->
                                      admitted.storage().initializeDefaultsIfMissing(this::current),
                                  admitted.expected()))
                  .thenCompose(stage -> stage));
    }

    /**
     * Read saved bytes again and apply only that unchanged observation. This operation performs no
     * file write, even when a previous save succeeded but its application was refused.
     */
    public CompletionStage<Receipt<T>> applySaved(OwnerObservation<T> input) {
      if (startup)
        return CompletableFuture.<Receipt<T>>failedFuture(
                new PolicyRefusal(
                    "owner.unsupported", "Startup choices cannot be applied to this process"))
            .minimalCompletionStage();
      final Observation<T> observed;
      try {
        observed = ownObservation(input);
      } catch (PolicyRefusal refused) {
        return CompletableFuture.<Receipt<T>>failedFuture(refused).minimalCompletionStage();
      }
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        baseline(observed);
                        revision(observed.effective.revision());
                        return storage;
                      })
                  .thenCompose(source -> context.onWorker(source::read))
                  .thenCompose(
                      actual -> {
                        if (!actual.source().revision().equals(observed.saved.source().revision()))
                          return finish(
                              Persistence.NOT_ATTEMPTED,
                              Optional.empty(),
                              actual,
                              ValidationReport.VALID,
                              observed.effective.revision(),
                              problem(
                                  "owner.saved_revision",
                                  "Saved bytes changed; review them again",
                                  true));
                        return finish(
                            Persistence.NOT_ATTEMPTED,
                            Optional.empty(),
                            actual,
                            ValidationReport.VALID,
                            observed.effective.revision(),
                            null);
                      })
                  .exceptionally(this::unattempted));
    }

    private CompletionStage<Receipt<T>> write(
        Callable<TextStore.Write<T>> writer, SettingsHandle.Revision expected) {
      var started = new AtomicBoolean();
      return context
          .onWorker(
              () -> {
                current();
                started.set(true);
                return writer.call();
              })
          .<CompletionStage<Receipt<T>>>handle(
              (written, failure) -> {
                if (failure != null)
                  return CompletableFuture.completedStage(
                      started.get()
                          ? receipt(
                              Persistence.UNKNOWN,
                              Application.UNAVAILABLE,
                              Optional.empty(),
                              Optional.empty(),
                              problem(
                                  "storage.unknown",
                                  "Persistence returned no reliable receipt; reconcile saved data",
                                  false),
                              report(failure))
                          : unattempted(failure));
                var persisted = written.persistence();
                boolean ready =
                    persisted.state() == OwnedFile.Persistence.SAVED
                        || persisted.state() == OwnedFile.Persistence.UNCHANGED;
                return finish(
                    Persistence.valueOf(persisted.state().name()),
                    persisted.observed().map(OwnedFile.Snapshot::revision),
                    written.observation().orElse(null),
                    persistenceReport(persisted),
                    expected,
                    ready
                        ? null
                        : problem(
                            "owner.not_applied",
                            "Persistence did not confirm the submitted values",
                            false));
              })
          .thenCompose(stage -> stage);
    }

    private CompletionStage<Receipt<T>> finish(
        Persistence persistence,
        Optional<String> savedRevision,
        TextStore.Read<T> saved,
        ValidationReport persistenceReport,
        SettingsHandle.Revision expected,
        ValidationReport prevented) {
      return application(saved, expected, prevented)
          .thenCompose(
              applied ->
                  context
                      .onOwner(
                          () -> {
                            Optional<Observation<T>> observed = Optional.empty();
                            if (saved != null)
                              try {
                                require(OwnerContext.Right.READ);
                                observed = Optional.of(observation(saved));
                              } catch (PolicyRefusal hidden) {
                                // Retain the receipt without disclosing newly observed private
                                // data.
                              }
                            return receipt(
                                persistence,
                                applied.state(),
                                savedRevision,
                                observed,
                                persistenceReport,
                                applied.report());
                          })
                      .exceptionally(
                          failure ->
                              receipt(
                                  persistence,
                                  applied.state(),
                                  savedRevision,
                                  Optional.empty(),
                                  persistenceReport,
                                  applied.report())));
    }

    private CompletionStage<Applied> application(
        TextStore.Read<T> saved, SettingsHandle.Revision expected, ValidationReport prevented) {
      if (prevented != null) return CompletableFuture.completedStage(applicationFailure(prevented));
      if (saved == null)
        return CompletableFuture.completedStage(
            applicationFailure(
                problem("owner.observation", "Saved values are unavailable", false)));
      if (saved.candidate().isEmpty()) {
        if (startup) return CompletableFuture.completedStage(applicationFailure(saved.report()));
        return context
            .onOwner(
                () -> {
                  require(OwnerContext.Right.WRITE);
                  runtime.observationFailed(saved.report());
                  return applicationFailure(saved.report());
                })
            .exceptionally(failure -> applicationFailure(report(failure)));
      }
      if (startup)
        return CompletableFuture.completedStage(
            new Applied(Application.STAGED, ValidationReport.VALID));
      return runtime
          .publishAsync(context, expected, saved.candidate().orElseThrow(), this::authorizedWrite)
          .handle(
              (view, failure) ->
                  failure == null
                      ? new Applied(Application.APPLIED, ValidationReport.VALID)
                      : applicationFailure(report(failure)));
    }

    private boolean authorizedWrite() {
      require(OwnerContext.Right.WRITE);
      return true;
    }

    private Observation<T> observation(TextStore.Read<T> saved) {
      return new Observation<>(
          identity,
          context.id(),
          id,
          saved,
          runtime.handle().view(),
          storage.provenance(),
          applicationEffects);
    }

    private Receipt<T> unattempted(Throwable failure) {
      return receipt(
          Persistence.NOT_ATTEMPTED,
          Application.NOT_ATTEMPTED,
          Optional.empty(),
          Optional.empty(),
          report(failure),
          ValidationReport.VALID);
    }

    @Override
    public void close() {
      open.set(false);
    }
  }

  private final Object identity = new Object();
  private final OwnerContext context;
  private final RuntimePolicy<T> runtime;
  private final SerialLane lane;
  private final ApplicationEffects applicationEffects;
  private final boolean startup;
  private volatile TextStore<T> storage;

  public FileOwner(OwnerContext context, TextStore<T> storage, int maximumOperations) {
    this(context, storage, maximumOperations, ApplicationEffects.OWNER_PUBLICATION);
  }

  public FileOwner(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      ApplicationEffects applicationEffects) {
    this(
        context,
        storage,
        maximumOperations,
        applicationEffects,
        new RuntimePolicy<>(storage.definition(), context::checkOwnerThread),
        false);
  }

  private FileOwner(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      ApplicationEffects applicationEffects,
      RuntimePolicy<T> runtime,
      boolean startup) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.storage = Objects.requireNonNull(storage);
    this.applicationEffects = Objects.requireNonNull(applicationEffects);
    this.startup = startup;
    lane = new SerialLane(maximumOperations);
    this.runtime = runtime;
    context.onClose(
        () -> {
          try {
            lane.close();
          } finally {
            this.storage = null;
            runtime.close();
          }
        });
  }

  /**
   * An immutable process-start selection, independently supplied by its trusted loader owner.
   * Registration does not read or initialize the source. The optional actual active candidate is
   * validated/copied on the worker before the owner is exposed; absence never substitutes defaults.
   * Saves only stage the next process's choices and offer no application-only operation.
   */
  public static <T> CompletionStage<FileOwner<T>> startup(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      Optional<Candidate<T>> active) {
    Objects.requireNonNull(context).requireOwner();
    Objects.requireNonNull(storage);
    Objects.requireNonNull(active);
    if (maximumOperations < 1)
      throw new IllegalArgumentException("Document operation limit must be positive");
    if (active.isPresent() && active.orElseThrow().definition() != storage.definition())
      throw new IllegalArgumentException("Active startup selection belongs to another definition");
    var runtime = new RuntimePolicy<>(storage.definition(), context::checkOwnerThread);
    CompletionStage<?> prepared =
        active.isEmpty()
            ? CompletableFuture.completedStage(null)
            : runtime.publishAsync(
                context,
                runtime.handle().view().revision(),
                active.orElseThrow(),
                () -> {
                  context.requireCurrent();
                  return true;
                });
    return prepared
        .thenCompose(
            ignored ->
                context.onOwner(
                    () ->
                        new FileOwner<>(
                            context,
                            storage,
                            maximumOperations,
                            ApplicationEffects.root(
                                ApplicationEffects.Timing.GAME_RESTART, STARTUP_EXPLANATION),
                            runtime,
                            true)))
        .toCompletableFuture()
        .minimalCompletionStage();
  }

  /**
   * Trusted runtime reads only. Administrative reads use a bound session and current permission.
   */
  public SettingsHandle<T> handle() {
    return runtime.handle();
  }

  public Session open(OwnerContext.Access access) {
    context.requireOwner();
    if (!Objects.requireNonNull(access).context().equals(context.id()))
      throw new PolicyRefusal("owner.context", "Access belongs to another owner context");
    access.require(OwnerContext.Right.READ);
    return new Session(access);
  }

  private static ValidationReport report(Throwable failure) {
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    return failure instanceof PolicyRefusal refused
        ? refused.report()
        : problem("owner.unavailable", "The owner operation could not complete", false);
  }

  private static Applied applicationFailure(ValidationReport report) {
    boolean unavailable =
        !report.complete()
            || report.issues().stream().anyMatch(issue -> issue.code().equals("owner.closed"));
    return new Applied(unavailable ? Application.UNAVAILABLE : Application.REFUSED, report);
  }

  private static ValidationReport persistenceReport(OwnedFile.Result result) {
    if (result.failure().isEmpty()) return ValidationReport.VALID;
    Throwable failure = result.failure().orElseThrow();
    for (int i = 0; i < 8 && failure != null; i++) {
      if (failure instanceof PolicyRefusal refused) return refused.report();
      failure = failure.getCause();
    }
    return problem(
        "storage.failure", "The file backend reported a persistence or cleanup failure", false);
  }

  private static ValidationReport problem(String code, String message, boolean complete) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, message)), complete);
  }

  private Receipt<T> receipt(
      Persistence persistence,
      Application application,
      Optional<String> savedRevision,
      Optional<Observation<T>> observation,
      ValidationReport persistenceReport,
      ValidationReport applicationReport) {
    return new Receipt<>(
        persistence,
        application,
        savedRevision,
        observation,
        persistenceReport,
        applicationReport,
        application == Application.STAGED ? STARTUP_REQUIREMENTS : List.of());
  }
}
