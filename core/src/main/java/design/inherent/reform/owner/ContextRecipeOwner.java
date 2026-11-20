package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftBasis;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An unsaved creation recipe, bound to its actual immutable native context. No file or existing
 * world is opened. Domain compilation and admission remain the consumer's implementations.
 */
public final class ContextRecipeOwner<T, C, P> implements AutoCloseable {
  private static final int MAXIMUM_BASES = 8;

  public interface Domain<T, C, P> {
    /** Native executor: actual current creation context, never a seed/name/UUID surrogate. */
    C current();

    /** Native executor: capture immutable authored values from that exact context. */
    T read(C context);

    /** Worker: pure bounded compilation from immutable context and validated owned values. */
    P compile(C context, T candidate);

    /**
     * Native executor: admit prepared inputs to this creation context; return its actual
     * replacement. Do not mutate the old context or an existing world. Throwing after entry has
     * unknown outcome.
     */
    C admit(C expected, P prepared);
  }

  /**
   * An explicitly read full default source. Its bounded immutable inputs stay behind the compiler.
   * The source revision identifies the complete source, including values outside the projection.
   */
  public record Defaults<T, C, P>(
      String revision, SourceInfo source, T projection, BiFunction<C, T, P> compile) {
    public Defaults {
      Objects.requireNonNull(revision);
      Objects.requireNonNull(source);
      Objects.requireNonNull(projection);
      Objects.requireNonNull(compile);
    }
  }

  private record Read<T>(
      String revision,
      Optional<DraftTree> draft,
      Optional<Candidate<T>> candidate,
      ValidationReport report)
      implements DocumentRead<T> {}

  public static final class Observation<T, C> implements OwnerObservation<T> {
    private final Object owner;
    private final UUID context, session;
    private final C nativeContext;
    private final long version;
    private final DocumentRead<T> document;

    private Observation(
        Object owner,
        UUID context,
        UUID session,
        C nativeContext,
        long version,
        DocumentRead<T> document) {
      this.owner = owner;
      this.context = context;
      this.session = session;
      this.nativeContext = nativeContext;
      this.version = version;
      this.document = document;
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
    public DocumentRead<T> document() {
      return document;
    }

    @Override
    public SourceInfo provenance() {
      return new SourceInfo("creation-context", "Unsaved world-creation recipe", Optional.empty());
    }

    @Override
    public ApplicationEffects applicationEffects() {
      return CREATION_EFFECTS;
    }
  }

  private record Proposal<T, C, P>(
      Observation<T, C> baseline, Candidate<T> candidate, P compiled) {}

  public static final class Prepared<T, C, P> implements PreparedEdit<T> {
    private final Object owner;
    private final UUID context, session;
    private final DocumentId document;
    private final AtomicReference<Proposal<T, C, P>> proposal;

    private Prepared(
        Object owner, UUID context, UUID session, DocumentId document, Proposal<T, C, P> proposal) {
      this.owner = owner;
      this.context = context;
      this.session = session;
      this.document = document;
      this.proposal = new AtomicReference<>(proposal);
    }

    @Override
    public DocumentId document() {
      return document;
    }

    @Override
    public UUID context() {
      return context;
    }

    @Override
    public UUID session() {
      return session;
    }

    private Proposal<T, C, P> take(Object actual, UUID actualSession) {
      if (owner != actual || !session.equals(actualSession))
        throw refusal("owner.session", "Recipe preparation belongs to another owner/session");
      var issued = proposal.getAndSet(null);
      if (issued == null)
        throw refusal("owner.consumed", "Recipe preparation has already been consumed");
      return issued;
    }
  }

  public record Receipt<T, C>(
      Application application,
      Optional<Observation<T, C>> observation,
      ValidationReport applicationReport,
      boolean admissionEntered,
      boolean identityConfirmed)
      implements CommitReceipt<T> {
    public Receipt {
      Objects.requireNonNull(application);
      Objects.requireNonNull(observation);
      Objects.requireNonNull(applicationReport);
      if (identityConfirmed && !admissionEntered
          || application == Application.APPLIED != identityConfirmed)
        throw new IllegalArgumentException("Inconsistent creation admission result");
    }

    @Override
    public Persistence persistence() {
      return Persistence.NOT_ATTEMPTED;
    }

    @Override
    public Optional<String> savedRevision() {
      return Optional.empty();
    }

    @Override
    public ValidationReport persistenceReport() {
      return ValidationReport.VALID;
    }

    @Override
    public boolean acknowledgesBaseline() {
      return identityConfirmed;
    }

    @Override
    public boolean baselineOutcomeUnknown() {
      return admissionEntered && !identityConfirmed;
    }

    @Override
    public List<Requirement> requirements() {
      return List.of(
          new Requirement(
              FieldPath.ROOT,
              RequirementKind.NEW_WORLD,
              "Applies only to this unsaved creation context"));
    }
  }

  private static final ApplicationEffects CREATION_EFFECTS =
      ApplicationEffects.root(
          ApplicationEffects.Timing.NEW_WORLD,
          "Admits inputs only to the original unsaved creation context; existing worlds do not change.");

  private record Capture<T, C>(C nativeContext, long version, T values) {}

  private record DefaultDraft<T, C, P>(
      DraftBasis basis, DraftTree draft, BiFunction<C, T, P> compile) {}

  private record BasisBinding<T, C, P>(UUID session, BiFunction<C, T, P> compile) {}

  private record Attempt<T, C>(
      CommitReceipt.Application application,
      Optional<Capture<T, C>> capture,
      ValidationReport report,
      boolean entered,
      boolean confirmed) {}

  public final class Session implements DocumentSession<T> {
    private final UUID id = UUID.randomUUID();
    private final OwnerContext.Access access;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private Session(OwnerContext.Access access) {
      this.access = access;
    }

    @Override
    public DocumentId document() {
      return definition.id();
    }

    @Override
    public DocumentDefinition<T> definition() {
      return definition;
    }

    @Override
    public UUID context() {
      return context.id();
    }

    @Override
    public UUID id() {
      return id;
    }

    @Override
    public boolean isOpen() {
      return open.get() && !closed && access.isOpen();
    }

    @Override
    public Set<Operation> operations() {
      return defaultsSource == null
          ? Set.of(Operation.READ, Operation.COMMIT)
          : Set.of(Operation.READ, Operation.COMMIT, Operation.LOAD_DEFAULTS);
    }

    @Override
    public CommitKind commitKind() {
      return CommitKind.CONTEXT;
    }

    private void current() {
      access.requireCurrent();
      if (!isOpen()) throw refusal("owner.closed", "This creation-recipe session is closed");
      if (broken)
        throw refusal(
            "owner.reconcile", "Creation admission outcome needs a fresh native context review");
    }

    private void require(OwnerContext.Right right) {
      current();
      access.require(right);
      current();
    }

    private void nativeCurrent(C expected, long expectedVersion) {
      require(OwnerContext.Right.READ);
      requireIdentity(expected, expectedVersion);
    }

    private void requireIdentity(C expected, long expectedVersion) {
      current();
      if (nativeContext != expected || version != expectedVersion || domain.current() != expected)
        throw refusal("owner.revision", "The original world-creation context changed");
      current();
    }

    @SuppressWarnings("unchecked") // The private issuing identity/session precede the cast.
    private Observation<T, C> own(OwnerObservation<T> value) {
      if (!(value instanceof Observation<?, ?> observed)
          || observed.owner != identity
          || !observed.session.equals(id))
        throw refusal("owner.session", "Recipe observation belongs to another owner/session");
      return (Observation<T, C>) observed;
    }

    @Override
    public CompletionStage<Observation<T, C>> read() {
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        nativeCurrent(nativeContext, version);
                        return new Capture<>(nativeContext, version, domain.read(nativeContext));
                      })
                  .thenCompose(capture -> observation(capture)));
    }

    @Override
    public CompletionStage<DraftTree> loadDefaults(String revision, Runnable checkpoint) {
      Objects.requireNonNull(checkpoint);
      return lane.submit(
          () ->
              context
                  .onOwner(
                      () -> {
                        requireSource(revision);
                        checkpoint.run();
                        var loader = defaultsSource;
                        if (loader == null)
                          throw refusal("owner.unsupported", "This recipe has no defaults source");
                        var original = nativeContext;
                        return context.onWorker(
                            () -> {
                              current();
                              checkpoint.run();
                              var loaded = Objects.requireNonNull(loader.apply(original));
                              var draft =
                                  DraftTree.from(
                                      definition.type(), loaded.projection(), draftLimits);
                              var inspected = draft.inspect(definition, jsonLimits);
                              if (!inspected.report().valid())
                                throw new PolicyRefusal(inspected.report());
                              return new DefaultDraft<>(
                                  new DraftBasis(loaded.revision(), loaded.source()),
                                  draft,
                                  loaded.compile());
                            });
                      })
                  .thenCompose(stage -> stage)
                  .thenCompose(
                      loaded ->
                          context.onOwner(
                              () -> {
                                requireSource(revision);
                                checkpoint.run();
                                return loaded.draft().withBasis(retainBasis(this, loaded));
                              })));
    }

    private CompletionStage<Observation<T, C>> observation(Capture<T, C> capture) {
      return context
          .onWorker(
              () -> {
                current();
                var draft = DraftTree.from(definition.type(), capture.values(), draftLimits);
                var inspected = draft.inspect(definition, jsonLimits);
                return new Read<T>(
                    sourceRevision(capture.version()),
                    Optional.of(draft),
                    inspected.candidate(),
                    inspected.report());
              })
          .thenCompose(
              read ->
                  context.onOwner(
                      () -> {
                        nativeCurrent(capture.nativeContext(), capture.version());
                        return new Observation<>(
                            identity,
                            context.id(),
                            id,
                            capture.nativeContext(),
                            capture.version(),
                            read);
                      }));
    }

    @Override
    public CompletionStage<Prepared<T, C, P>> prepare(OwnerObservation<T> input, DraftTree draft) {
      return prepare(input, draft, () -> {});
    }

    private CompletionStage<Prepared<T, C, P>> prepare(
        OwnerObservation<T> input, DraftTree draft, Runnable checkpoint) {
      final Observation<T, C> baseline;
      try {
        baseline = own(Objects.requireNonNull(input));
      } catch (RuntimeException failed) {
        return CompletableFuture.failedFuture(failed);
      }
      Objects.requireNonNull(draft);
      return context
          .onOwner(
              () -> {
                checkpoint.run();
                nativeCurrent(baseline.nativeContext, baseline.version);
                if (!baseline.document.report().valid())
                  throw new PolicyRefusal(baseline.document.report());
                BiFunction<C, T, P> compile = domain::compile;
                return draft.basis().map(basis -> requireBasis(id, basis)).orElse(compile);
              })
          .thenCompose(
              binding ->
                  context.onWorker(
                      () -> {
                        current();
                        checkpoint.run();
                        var inspected = draft.inspect(definition, jsonLimits);
                        var candidate =
                            inspected
                                .candidate()
                                .orElseThrow(() -> new PolicyRefusal(inspected.report()));
                        checkpoint.run();
                        P compiled =
                            Objects.requireNonNull(
                                binding.apply(baseline.nativeContext, candidate.value()));
                        var snapshots = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
                        P frozen =
                            snapshots.copy(
                                compiled, () -> compiledSnapshots.freeze(compiled, snapshots));
                        return new Proposal<>(baseline, candidate, Objects.requireNonNull(frozen));
                      }))
          .thenCompose(
              proposal ->
                  context.onOwner(
                      () -> {
                        checkpoint.run();
                        nativeCurrent(baseline.nativeContext, baseline.version);
                        return new Prepared<>(identity, context.id(), id, document(), proposal);
                      }));
    }

    /** Check the actual native source before disclosing an already captured preview. */
    public void requireSource(String revision) {
      nativeCurrent(nativeContext, version);
      if (!sourceRevision(version).equals(Objects.requireNonNull(revision)))
        throw refusal("owner.revision", "The recipe source changed; start a new preview");
    }

    /** Compile and inspect owned immutable inputs without admitting a creation context. */
    public <O> CompletionStage<O> preview(
        String revision, DraftTree draft, Runnable checkpoint, Function<P, O> inspect) {
      Objects.requireNonNull(revision);
      Objects.requireNonNull(draft);
      Objects.requireNonNull(checkpoint);
      Objects.requireNonNull(inspect);
      checkpoint.run();
      return read()
          .thenCompose(
              observed -> {
                if (!observed.document().revision().equals(revision))
                  throw refusal("owner.revision", "The recipe source changed; start a new preview");
                return prepare(observed, draft, checkpoint);
              })
          .thenCompose(
              prepared -> {
                var proposal = prepared.take(identity, id);
                return context
                    .onWorker(
                        () -> {
                          current();
                          checkpoint.run();
                          var result = Objects.requireNonNull(inspect.apply(proposal.compiled()));
                          checkpoint.run();
                          return result;
                        })
                    .thenCompose(
                        result ->
                            context.onOwner(
                                () -> {
                                  checkpoint.run();
                                  nativeCurrent(
                                      proposal.baseline().nativeContext,
                                      proposal.baseline().version);
                                  return result;
                                }));
              });
    }

    @Override
    public CompletionStage<Receipt<T, C>> commit(PreparedEdit<T> value) {
      final Proposal<T, C, P> proposal;
      try {
        if (!(value instanceof Prepared<?, ?, ?> prepared))
          throw refusal("owner.session", "Preparation was not issued by this recipe owner");
        @SuppressWarnings("unchecked")
        var typed = (Prepared<T, C, P>) prepared;
        proposal = typed.take(identity, id);
      } catch (RuntimeException refused) {
        return CompletableFuture.failedFuture(refused);
      }
      return lane.submit(
          () ->
              context
                  .onOwner(() -> attempt(proposal))
                  .handle(
                      (attempt, failed) ->
                          failed == null
                              ? settle(attempt)
                              : CompletableFuture.completedStage(
                                  new Receipt<T, C>(
                                      CommitReceipt.Application.REFUSED,
                                      Optional.empty(),
                                      report(failed),
                                      false,
                                      false)))
                  .thenCompose(stage -> stage));
    }

    private Attempt<T, C> attempt(Proposal<T, C, P> proposal) {
      require(OwnerContext.Right.WRITE);
      // Authorization may be reentrant. Check the exact original context again immediately before
      // entry.
      requireIdentity(proposal.baseline.nativeContext, proposal.baseline.version);
      var binding = domain;
      final C admitted;
      try {
        admitted =
            Objects.requireNonNull(
                binding.admit(proposal.baseline.nativeContext, proposal.compiled));
      } catch (RuntimeException failed) {
        broken = true;
        return new Attempt<>(
            CommitReceipt.Application.UNAVAILABLE, Optional.empty(), report(failed), true, false);
      }
      final boolean confirmed;
      try {
        confirmed = binding.current() == admitted;
      } catch (RuntimeException unavailable) {
        broken = true;
        return new Attempt<>(
            CommitReceipt.Application.UNAVAILABLE,
            Optional.empty(),
            report(unavailable),
            true,
            false);
      }
      if (!confirmed) {
        broken = true;
        return new Attempt<>(
            CommitReceipt.Application.PARTIAL,
            Optional.empty(),
            report(
                refusal("owner.revision", "The admitted creation context could not be confirmed")),
            true,
            false);
      }
      if (!closed) {
        nativeContext = admitted;
        version++;
        clearBases();
      }
      try {
        require(OwnerContext.Right.READ);
        nativeCurrent(admitted, version);
        return new Attempt<>(
            CommitReceipt.Application.APPLIED,
            Optional.of(new Capture<>(admitted, version, binding.read(admitted))),
            ValidationReport.VALID,
            true,
            true);
      } catch (RuntimeException unavailable) {
        return new Attempt<>(
            CommitReceipt.Application.APPLIED, Optional.empty(), report(unavailable), true, true);
      }
    }

    private CompletionStage<Receipt<T, C>> settle(Attempt<T, C> attempt) {
      if (attempt.capture().isEmpty())
        return CompletableFuture.completedStage(
            receipt(attempt, Optional.empty(), ValidationReport.VALID));
      return observation(attempt.capture().orElseThrow())
          .handle(
              (observed, failed) ->
                  receipt(
                      attempt,
                      Optional.ofNullable(observed),
                      failed == null ? ValidationReport.VALID : report(failed)));
    }

    private Receipt<T, C> receipt(
        Attempt<T, C> attempt, Optional<Observation<T, C>> observation, ValidationReport readback) {
      return new Receipt<>(
          attempt.application(),
          observation,
          attempt.report().and(readback),
          attempt.entered(),
          attempt.confirmed());
    }

    @Override
    public void close() {
      open.set(false);
      synchronized (bases) {
        bases.values().removeIf(binding -> binding.session().equals(id));
      }
    }
  }

  private final Object identity = new Object();
  private final UUID identityId = UUID.randomUUID();
  private final OwnerContext context;
  private final DocumentDefinition<T> definition;
  private final SnapshotPolicy<P> compiledSnapshots;
  private final JsonLimits jsonLimits;
  private final DraftLimits draftLimits;
  private final SerialLane lane;
  private final OwnerContext.CloseHook cleanup;
  private final Map<DraftBasis, BasisBinding<T, C, P>> bases = new LinkedHashMap<>();
  private C nativeContext;
  private Domain<T, C, P> domain;
  private volatile Function<C, Defaults<T, C, P>> defaultsSource;
  private long version;
  private volatile boolean closed, broken;

  public ContextRecipeOwner(
      OwnerContext context,
      DocumentDefinition<T> definition,
      C original,
      Domain<T, C, P> domain,
      SnapshotPolicy<P> compiledSnapshots,
      JsonLimits jsonLimits,
      DraftLimits draftLimits,
      int maximumOperations) {
    this(
        context,
        definition,
        original,
        domain,
        compiledSnapshots,
        jsonLimits,
        draftLimits,
        maximumOperations,
        null);
  }

  /**
   * The optional loader runs on the worker, using immutable data from the actual creation context.
   */
  public ContextRecipeOwner(
      OwnerContext context,
      DocumentDefinition<T> definition,
      C original,
      Domain<T, C, P> domain,
      SnapshotPolicy<P> compiledSnapshots,
      JsonLimits jsonLimits,
      DraftLimits draftLimits,
      int maximumOperations,
      Function<C, Defaults<T, C, P>> defaultsSource) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.definition = Objects.requireNonNull(definition);
    this.nativeContext = Objects.requireNonNull(original);
    this.domain = Objects.requireNonNull(domain);
    this.compiledSnapshots = Objects.requireNonNull(compiledSnapshots);
    this.jsonLimits = Objects.requireNonNull(jsonLimits);
    this.draftLimits = Objects.requireNonNull(draftLimits);
    this.defaultsSource = defaultsSource;
    if (domain.current() != original)
      throw refusal("owner.revision", "Use the actual original world-creation context");
    lane = new SerialLane(maximumOperations);
    cleanup = context.onClose(this::close);
  }

  private DraftBasis retainBasis(Session session, DefaultDraft<T, C, P> loaded) {
    synchronized (bases) {
      if (!session.isOpen()) throw refusal("owner.closed", "This recipe session closed");
      for (var known : bases.entrySet())
        if (known.getValue().session().equals(session.id())
            && known.getKey().revision().equals(loaded.basis().revision())
            && known.getKey().source().equals(loaded.basis().source())) return known.getKey();
      if (bases.size() >= MAXIMUM_BASES)
        throw refusal(
            "owner.defaults_budget", "Reopen this recipe before loading another defaults revision");
      bases.put(loaded.basis(), new BasisBinding<>(session.id(), loaded.compile()));
      return loaded.basis();
    }
  }

  private BiFunction<C, T, P> requireBasis(UUID session, DraftBasis selected) {
    synchronized (bases) {
      var binding = bases.get(selected);
      if (binding == null || !binding.session().equals(session))
        throw refusal(
            "owner.defaults_revision", "Load defaults again for this actual recipe source");
      return binding.compile();
    }
  }

  private void clearBases() {
    synchronized (bases) {
      bases.clear();
    }
  }

  public Session open(OwnerContext.Access access) {
    context.requireOwner();
    if (closed || broken)
      throw refusal("owner.closed", "This recipe owner cannot open another session");
    if (!Objects.requireNonNull(access).context().equals(context.id()) || !access.isOpen())
      throw refusal("owner.session", "Recipe session needs its actual context access");
    return new Session(access);
  }

  private static PolicyRefusal refusal(String code, String message) {
    return new PolicyRefusal(code, message);
  }

  private String sourceRevision(long selected) {
    return identityId + "/" + selected;
  }

  private static ValidationReport report(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return failed instanceof PolicyRefusal refused
        ? refused.report()
        : new ValidationReport(
            List.of(
                Issue.error(
                    "context.admission",
                    FieldPath.ROOT,
                    "Creation recipe admission or observation is unavailable")),
            false);
  }

  @Override
  public void close() {
    if (closed) return;
    context.checkOwnerThread();
    closed = true;
    nativeContext = null;
    domain = null;
    defaultsSource = null;
    clearBases();
    lane.close();
    cleanup.close();
  }
}
