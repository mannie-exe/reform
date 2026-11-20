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
  public enum Initialization {
    CREATE_DEFAULTS,
    DEFER_MISSING
  }

  private enum Mode {
    LIVE,
    STARTUP,
    NEW_WORLD_DEFAULTS,
    STAGED_FILE
  }

  private static final String DEFAULTS_EXPLANATION =
      "Saved defaults are available for later new worlds. They do not change an open recipe or a running world.";
  private static final List<CommitReceipt.Requirement> DEFAULTS_REQUIREMENTS =
      List.of(
          new CommitReceipt.Requirement(
              FieldPath.ROOT, CommitReceipt.RequirementKind.NEW_WORLD, DEFAULTS_EXPLANATION));
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
    private final boolean hasEffectiveView;

    private Observation(
        Object owner,
        UUID context,
        UUID session,
        TextStore.Read<T> saved,
        SettingsHandle.View<T> effective,
        SourceInfo provenance,
        ApplicationEffects applicationEffects,
        boolean hasEffectiveView) {
      this.owner = owner;
      this.context = context;
      this.session = session;
      this.saved = saved;
      this.effective = effective;
      this.provenance = provenance;
      this.applicationEffects = applicationEffects;
      this.hasEffectiveView = hasEffectiveView;
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
      return hasEffectiveView ? Optional.of(effective) : Optional.empty();
    }
  }

  private record Proposal<T>(Observation<T> baseline, TextStore.PreparedWrite<T> output) {}

  private record InitializationRead<T>(TextStore<T> storage, SettingsHandle.Revision expected) {}

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
      return mode == Mode.LIVE
          ? Set.of(Operation.READ, Operation.COMMIT, Operation.APPLY_SAVED)
          : Set.of(Operation.READ, Operation.COMMIT);
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
                  .thenCompose(source -> context.onWorker(() -> readForEditor(source)))
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
                        return source.prepareWrite(observed.saved, draft);
                      }))
          .thenCompose(
              output ->
                  context.onOwner(
                      () -> {
                        baseline(observed);
                        revision(observed.effective.revision(), OwnerContext.Right.READ);
                        return new Prepared<>(
                            identity,
                            id,
                            context.id(),
                            document(),
                            new Proposal<>(observed, output));
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
                                  () -> source.write(proposal.output(), this::current),
                                  proposal.baseline().effective.revision()))
                  .thenCompose(stage -> stage));
    }

    /**
     * Explicit admitted initialization. Opening a form or reading a missing file never does this.
     */
    public CompletionStage<Receipt<T>> initialize() {
      if (initialization == Initialization.DEFER_MISSING)
        return lane.submit(
            () ->
                context
                    .onOwner(
                        () -> {
                          require(OwnerContext.Right.READ);
                          return new InitializationRead<>(
                              storage, runtime.handle().view().revision());
                        })
                    .thenCompose(
                        admitted ->
                            context
                                .onWorker(() -> readForEditor(admitted.storage()))
                                .thenCompose(
                                    saved ->
                                        finish(
                                            Persistence.NOT_ATTEMPTED,
                                            Optional.empty(),
                                            saved,
                                            ValidationReport.VALID,
                                            admitted.expected(),
                                            null)))
                    .exceptionally(this::unattempted));
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        require(OwnerContext.Right.WRITE);
                        return new InitializationRead<>(
                            storage, runtime.handle().view().revision());
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
      if (mode != Mode.LIVE)
        return CompletableFuture.<Receipt<T>>failedFuture(
                new PolicyRefusal(
                    "owner.unsupported",
                    "Future-use choices cannot be applied to a current process or world"))
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
        if (mode == Mode.STARTUP || mode == Mode.STAGED_FILE)
          return CompletableFuture.completedStage(applicationFailure(saved.report()));
        return context
            .onOwner(
                () -> {
                  require(OwnerContext.Right.WRITE);
                  runtime.observationFailed(saved.report());
                  return applicationFailure(saved.report());
                })
            .exceptionally(failure -> applicationFailure(report(failure)));
      }
      if (mode == Mode.STARTUP
          || mode == Mode.STAGED_FILE
          || !saved.source().present() && initialization == Initialization.DEFER_MISSING)
        return CompletableFuture.completedStage(
            new Applied(Application.STAGED, ValidationReport.VALID));
      return runtime
          .publishAsync(
              context,
              expected,
              saved.candidate().orElseThrow(),
              this::authorizedWrite,
              liveApplication)
          .handle(
              (view, failure) ->
                  failure == null
                      ? new Applied(
                          mode == Mode.NEW_WORLD_DEFAULTS
                              ? Application.STAGED
                              : Application.APPLIED,
                          ValidationReport.VALID)
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
          saved.state() == TextStore.State.MISSING && saved.candidate().isPresent()
              ? new SourceInfo(
                  "unsaved-defaults",
                  "Declared defaults; no saved document exists",
                  storage.provenance().location())
              : storage.provenance(),
          applicationEffects,
          mode != Mode.STAGED_FILE);
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
  private final List<CommitReceipt.Requirement> stagedRequirements;
  private final Mode mode;
  private final Initialization initialization;
  private volatile TextStore<T> storage;
  private volatile LiveApplication<T> liveApplication;

  public FileOwner(OwnerContext context, TextStore<T> storage, int maximumOperations) {
    this(context, storage, maximumOperations, ApplicationEffects.OWNER_PUBLICATION);
  }

  public FileOwner(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      ApplicationEffects applicationEffects) {
    this(context, storage, maximumOperations, applicationEffects, Optional.empty());
  }

  /** Bind a real external live effect to this file's ordinary publication lane. */
  public FileOwner(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      ApplicationEffects applicationEffects,
      LiveApplication<T> liveApplication) {
    this(context, storage, maximumOperations, applicationEffects, Optional.of(liveApplication));
  }

  private FileOwner(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      ApplicationEffects applicationEffects,
      Optional<LiveApplication<T>> liveApplication) {
    this(
        context,
        storage,
        maximumOperations,
        applicationEffects,
        new RuntimePolicy<>(storage.definition(), context::checkOwnerThread),
        Mode.LIVE,
        Initialization.CREATE_DEFAULTS,
        liveApplication.orElse(null));
  }

  private FileOwner(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      ApplicationEffects applicationEffects,
      RuntimePolicy<T> runtime,
      Mode mode,
      Initialization initialization,
      LiveApplication<T> liveApplication) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.storage = Objects.requireNonNull(storage);
    this.applicationEffects = Objects.requireNonNull(applicationEffects);
    this.mode = Objects.requireNonNull(mode);
    stagedRequirements =
        switch (mode) {
          case STARTUP -> STARTUP_REQUIREMENTS;
          case NEW_WORLD_DEFAULTS -> DEFAULTS_REQUIREMENTS;
          case STAGED_FILE ->
              applicationEffects.rules().stream()
                  .flatMap(rule -> rule.requirements().stream())
                  .toList();
          case LIVE -> List.of();
        };
    this.initialization = Objects.requireNonNull(initialization);
    this.liveApplication = liveApplication;
    lane = new SerialLane(maximumOperations);
    this.runtime = runtime;
    context.onClose(
        () -> {
          try {
            lane.close();
          } finally {
            this.storage = null;
            this.liveApplication = null;
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
                            Mode.STARTUP,
                            Initialization.CREATE_DEFAULTS,
                            null)))
        .toCompletableFuture()
        .minimalCompletionStage();
  }

  /** Saved defaults belong to later new worlds; the handle never represents an active recipe. */
  public static <T> FileOwner<T> newWorldDefaults(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      Initialization initialization) {
    return new FileOwner<>(
        context,
        storage,
        maximumOperations,
        ApplicationEffects.root(ApplicationEffects.Timing.NEW_WORLD, DEFAULTS_EXPLANATION),
        new RuntimePolicy<>(storage.definition(), context::checkOwnerThread),
        Mode.NEW_WORLD_DEFAULTS,
        initialization,
        null);
  }

  /** Persist a future-use source whose effective values are owned and applied elsewhere. */
  public static <T> FileOwner<T> stagedFile(
      OwnerContext context,
      TextStore<T> storage,
      int maximumOperations,
      Initialization initialization,
      ApplicationEffects effects) {
    if (Objects.requireNonNull(effects).rules().isEmpty())
      throw new IllegalArgumentException("A staged source needs an owner application declaration");
    return new FileOwner<>(
        context,
        storage,
        maximumOperations,
        effects,
        new RuntimePolicy<>(storage.definition(), context::checkOwnerThread),
        Mode.STAGED_FILE,
        initialization,
        null);
  }

  private TextStore.Read<T> readForEditor(TextStore<T> source) throws java.io.IOException {
    var observed = source.read();
    return initialization == Initialization.DEFER_MISSING
        ? source.withMissingDefaults(observed)
        : observed;
  }

  /**
   * Trusted runtime reads only. Administrative reads use a bound session and current permission.
   */
  public SettingsHandle<T> handle() {
    if (mode == Mode.STAGED_FILE)
      throw new IllegalStateException("Staged files do not publish an effective handle");
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
        application == Application.STAGED ? stagedRequirements : List.of());
  }
}
