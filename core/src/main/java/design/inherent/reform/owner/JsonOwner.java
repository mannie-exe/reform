package design.inherent.reform.owner;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A context-owned JSON document. All actor sessions share one bounded persistence/publication lane.
 * Native gateways supply actual actor access; a snapshot or proposal is never an authority grant.
 */
public final class JsonOwner<T> {
  public enum Persistence {
    NOT_ATTEMPTED,
    UNCHANGED,
    SAVED,
    REFUSED,
    FAILED,
    UNKNOWN
  }

  public enum Application {
    NOT_ATTEMPTED,
    APPLIED,
    REFUSED,
    UNAVAILABLE
  }

  /** An authorized observation, bound to this document and this particular actor session. */
  public static final class Observation<T> {
    private final Object owner;
    private final UUID context, session;
    private final JsonStore.Read<T> saved;
    private final SettingsHandle.View<T> effective;

    private Observation(
        Object owner,
        UUID context,
        UUID session,
        JsonStore.Read<T> saved,
        SettingsHandle.View<T> effective) {
      this.owner = owner;
      this.context = context;
      this.session = session;
      this.saved = saved;
      this.effective = effective;
    }

    public UUID context() {
      return context;
    }

    public UUID session() {
      return session;
    }

    public JsonStore.Read<T> saved() {
      return saved;
    }

    public SettingsHandle.View<T> effective() {
      return effective;
    }
  }

  private record Proposal<T>(Observation<T> baseline, Candidate<T> candidate) {}

  private record Initialization<T>(JsonStore<T> storage, SettingsHandle.Revision expected) {}

  /** A one-shot complete candidate; persistence and actual authorization still have to succeed. */
  public static final class Prepared<T> {
    private final Object owner;
    private final UUID session;
    private Proposal<T> proposal;

    private Prepared(Object owner, UUID session, Proposal<T> proposal) {
      this.owner = owner;
      this.session = session;
      this.proposal = proposal;
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
      ValidationReport applicationReport) {
    public Receipt {
      Objects.requireNonNull(persistence);
      Objects.requireNonNull(application);
      Objects.requireNonNull(savedRevision);
      Objects.requireNonNull(observation);
      Objects.requireNonNull(persistenceReport);
      Objects.requireNonNull(applicationReport);
    }
  }

  public final class Session implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final OwnerContext.Access access;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private Session(OwnerContext.Access access) {
      this.access = access;
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

    private void baseline(Observation<T> observed) {
      Objects.requireNonNull(observed);
      if (observed.owner != identity || !observed.session.equals(id))
        throw new PolicyRefusal(
            "owner.session", "Observation belongs to another document or session");
      current();
    }

    private void revision(SettingsHandle.Revision expected) {
      require(OwnerContext.Right.WRITE);
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
    public CompletionStage<Prepared<T>> prepare(Observation<T> observed, DraftTree draft) {
      Objects.requireNonNull(draft);
      return context
          .onOwner(
              () -> {
                baseline(observed);
                revision(observed.effective.revision());
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
                        revision(observed.effective.revision());
                        return new Prepared<>(identity, id, new Proposal<>(observed, candidate));
                      }));
    }

    /** Commit admission checks actual WRITE permission after earlier document operations finish. */
    public CompletionStage<Receipt<T>> commit(Prepared<T> prepared) {
      var proposal = Objects.requireNonNull(prepared).take(identity, id);
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
    public CompletionStage<Receipt<T>> applySaved(Observation<T> observed) {
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
        Callable<JsonStore.Write<T>> writer, SettingsHandle.Revision expected) {
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
        JsonStore.Read<T> saved,
        ValidationReport persistenceReport,
        SettingsHandle.Revision expected,
        ValidationReport prevented) {
      return context
          .onOwner(
              () -> {
                Application application;
                ValidationReport applicationReport;
                try {
                  if (prevented != null) throw new PolicyRefusal(prevented);
                  require(OwnerContext.Right.WRITE);
                  if (saved == null)
                    throw new PolicyRefusal(
                        "owner.observation", "Saved values are unavailable", false);
                  if (saved.candidate().isEmpty()) {
                    runtime.observationFailed(saved.report());
                    throw new PolicyRefusal(saved.report());
                  }
                  runtime
                      .prepare(expected, saved.candidate().orElseThrow(), this::authorizedWrite)
                      .publish(this::authorizedWrite);
                  application = Application.APPLIED;
                  applicationReport = ValidationReport.VALID;
                } catch (RuntimeException failure) {
                  applicationReport = report(failure);
                  application =
                      applicationReport.complete() ? Application.REFUSED : Application.UNAVAILABLE;
                }
                Optional<Observation<T>> observed = Optional.empty();
                if (saved != null)
                  try {
                    require(OwnerContext.Right.READ);
                    observed = Optional.of(observation(saved));
                  } catch (PolicyRefusal hidden) {
                    // The receipt remains useful without disclosing newly observed private data.
                  }
                return receipt(
                    persistence,
                    application,
                    savedRevision,
                    observed,
                    persistenceReport,
                    applicationReport);
              })
          .exceptionally(
              failure ->
                  receipt(
                      persistence,
                      Application.UNAVAILABLE,
                      savedRevision,
                      Optional.empty(),
                      persistenceReport,
                      report(failure)));
    }

    private boolean authorizedWrite() {
      require(OwnerContext.Right.WRITE);
      return true;
    }

    private Observation<T> observation(JsonStore.Read<T> saved) {
      return new Observation<>(identity, context.id(), id, saved, runtime.handle().view());
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
  private volatile JsonStore<T> storage;

  public JsonOwner(OwnerContext context, JsonStore<T> storage, int maximumOperations) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.storage = Objects.requireNonNull(storage);
    lane = new SerialLane(maximumOperations);
    runtime = new RuntimePolicy<>(storage.definition(), context::checkOwnerThread);
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

  private static <T> Receipt<T> receipt(
      Persistence persistence,
      Application application,
      Optional<String> savedRevision,
      Optional<Observation<T>> observation,
      ValidationReport persistenceReport,
      ValidationReport applicationReport) {
    return new Receipt<>(
        persistence, application, savedRevision, observation, persistenceReport, applicationReport);
  }
}
