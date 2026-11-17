package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Context-owned effective values. The admitted native adapter supplies actual actor checks and
 * execution; no GUI/client payload may provide its own authorization callback.
 */
public final class RuntimePolicy<T> implements AutoCloseable {
  private record PublicationInput<T>(
      DocumentDefinition<T> definition, Candidate<T> candidate, SettingsHandle.View<T> before) {}

  private record Ready<T>(SettingsHandle.View<T> before, SettingsHandle.View<T> view) {}

  public final class Prepared {
    private final SettingsHandle.Revision expected;
    private Candidate<T> candidate;
    private boolean consumed;

    private Prepared(SettingsHandle.Revision expected, Candidate<T> candidate) {
      this.expected = expected;
      this.candidate = candidate;
    }

    public SettingsHandle.View<T> publish(BooleanSupplier authorized) {
      activeThread();
      if (consumed) throw new PolicyRefusal("owner.consumed", "Publication was already attempted");
      consumed = true;
      var submitted = candidate;
      candidate = null;
      var input = capture(expected, submitted, authorized);
      var ready =
          compute(
              input,
              () -> {
                activeThread();
                requireRevision(expected);
              });
      return publishReady(expected, ready, authorized);
    }
  }

  private final UUID context = UUID.randomUUID();
  private DocumentDefinition<T> definition;
  private Runnable ownerThread;
  private final SettingsHandle<T> handle;
  private boolean closed;

  /** No factory defaults are presented as loaded or effective values. */
  public RuntimePolicy(DocumentDefinition<T> definition, Runnable ownerThread) {
    this.definition = Objects.requireNonNull(definition);
    this.ownerThread = Objects.requireNonNull(ownerThread);
    handle =
        new SettingsHandle<>(
            definition.id(),
            new SettingsHandle.View<>(
                context,
                new SettingsHandle.Revision(0),
                SettingsHandle.Availability.UNINITIALIZED,
                null,
                null,
                ValidationReport.VALID));
  }

  public SettingsHandle<T> handle() {
    return handle;
  }

  /**
   * Capture/gate and publish on the native executor; all pure value callbacks run on the explicit
   * worker. This is a publication operation, not file persistence or domain compilation.
   */
  public CompletionStage<SettingsHandle.View<T>> publishAsync(
      OwnerContext execution,
      SettingsHandle.Revision expected,
      Candidate<T> candidate,
      BooleanSupplier authorized) {
    Objects.requireNonNull(execution);
    return execution
        .onOwner(() -> capture(expected, candidate, authorized))
        .thenCompose(input -> execution.onWorker(() -> compute(input, execution::requireCurrent)))
        .thenCompose(ready -> execution.onOwner(() -> publishReady(expected, ready, authorized)))
        .toCompletableFuture()
        .minimalCompletionStage();
  }

  private PublicationInput<T> capture(
      SettingsHandle.Revision expected, Candidate<T> candidate, BooleanSupplier authorized) {
    require(authorized);
    requireRevision(expected);
    if (Objects.requireNonNull(candidate).definition() != definition)
      throw new PolicyRefusal("owner.definition", "Candidate belongs to another definition");
    return new PublicationInput<>(definition, candidate, handle.view());
  }

  private SettingsHandle.View<T> publishReady(
      SettingsHandle.Revision expected, Ready<T> ready, BooleanSupplier authorized) {
    require(authorized);
    requireRevision(expected);
    if (handle.view() != ready.before())
      throw new PolicyRefusal(
          "owner.observation_changed", "Runtime observation changed while preparing publication");
    handle.publish(ready.view());
    return ready.view();
  }

  private static <T> Ready<T> compute(PublicationInput<T> input, Runnable current) {
    try {
      current.run();
      var descriptor = input.definition();
      var proposed = input.candidate().value();
      current.run();
      var validated = descriptor.validate(proposed);
      current.run();
      if (validated.value().isEmpty()) throw new PolicyRefusal(validated.report());
      var next = validated.value().orElseThrow();
      var before = input.before();
      var previous = before.value();
      current.run();
      boolean equal =
          previous.isPresent() && descriptor.type().equivalent(previous.orElseThrow(), next);
      var frozen = descriptor.type().freeze(equal ? previous.orElseThrow() : next);
      current.run();
      return new Ready<>(
          before,
          new SettingsHandle.View<>(
              before.context(),
              equal
                  ? before.revision()
                  : new SettingsHandle.Revision(before.revision().sequence() + 1),
              SettingsHandle.Availability.AVAILABLE,
              descriptor.type(),
              frozen,
              ValidationReport.VALID));
    } catch (PolicyRefusal refused) {
      throw refused;
    } catch (RuntimeException unavailable) {
      throw new PolicyRefusal(
          "owner.publication_unavailable", "Publication preparation could not complete", false);
    }
  }

  private void activeThread() {
    if (closed) throw new PolicyRefusal("owner.closed", "This runtime context is closed");
    ownerThread.run();
  }

  private void require(BooleanSupplier authorized) {
    activeThread();
    final boolean admitted;
    try {
      admitted = Objects.requireNonNull(authorized).getAsBoolean();
    } catch (PolicyRefusal refused) {
      throw refused;
    } catch (RuntimeException unavailable) {
      throw new PolicyRefusal(
          "owner.authorization_unavailable",
          "Actual authorization could not be established",
          false);
    }
    activeThread();
    if (!admitted) throw new PolicyRefusal("owner.permission", "Current permission is required");
  }

  public SettingsHandle.View<T> read(BooleanSupplier authorized) {
    require(authorized);
    return handle.view();
  }

  public Prepared prepare(
      SettingsHandle.Revision expected, Candidate<T> candidate, BooleanSupplier authorized) {
    require(authorized);
    requireRevision(expected);
    if (Objects.requireNonNull(candidate).definition() != definition)
      throw new PolicyRefusal("owner.definition", "Candidate belongs to another definition");
    var descriptor = definition;
    var proposed = candidate.value();
    activeThread();
    requireRevision(expected);
    var validated = descriptor.validate(proposed);
    if (validated.value().isEmpty()) throw new PolicyRefusal(validated.report());
    activeThread();
    requireRevision(expected);
    return new Prepared(expected, candidate);
  }

  private void requireRevision(SettingsHandle.Revision expected) {
    if (expected != handle.view().revision())
      throw new PolicyRefusal(
          "owner.revision", "Effective values changed or belong to another context");
  }

  /** Trusted lifecycle failure. Retains a qualified previous publication only in this context. */
  public void observationFailed(ValidationReport report) {
    activeThread();
    if (Objects.requireNonNull(report).valid())
      throw new IllegalArgumentException("Expected an unsuccessful observation report");
    handle.publish(handle.view().withObservation(report));
  }

  @Override
  public void close() {
    if (closed) return;
    activeThread();
    var view = handle.view();
    handle.publish(
        new SettingsHandle.View<>(
            context,
            new SettingsHandle.Revision(view.revision().sequence() + 1),
            SettingsHandle.Availability.CLOSED,
            null,
            null,
            ValidationReport.VALID));
    closed = true;
    ownerThread = null;
    definition = null;
  }
}
