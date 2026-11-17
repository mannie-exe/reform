package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Context-owned effective values. The admitted native adapter supplies actual actor checks and
 * execution; no GUI/client payload may provide its own authorization callback.
 */
public final class RuntimePolicy<T> implements AutoCloseable {
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
      try {
        require(authorized);
        var before = handle.view();
        requireRevision(expected);
        var descriptor = definition;
        var proposed = submitted.value();
        activeThread();
        requireRevision(expected);
        var validated = descriptor.validate(proposed);
        activeThread();
        requireRevision(expected);
        if (validated.value().isEmpty()) throw new PolicyRefusal(validated.report());
        var next = validated.value().orElseThrow();
        var previous = before.value();
        boolean equal =
            previous.isPresent() && descriptor.type().equivalent(previous.orElseThrow(), next);
        // Finish every consumer-supplied copy/equality callback before changing the publication.
        var frozen = descriptor.type().freeze(equal ? previous.orElseThrow() : next);
        var view =
            new SettingsHandle.View<>(
                context,
                equal
                    ? before.revision()
                    : new SettingsHandle.Revision(before.revision().sequence() + 1),
                SettingsHandle.Availability.AVAILABLE,
                descriptor.type(),
                frozen,
                ValidationReport.VALID);
        // Native callbacks can reenter a service. Never overwrite a newer or closed publication.
        require(authorized);
        requireRevision(expected);
        handle.publish(view);
        return view;
      } catch (PolicyRefusal refused) {
        throw refused;
      } catch (RuntimeException unavailable) {
        throw new PolicyRefusal(
            "owner.publication_unavailable", "Publication preparation could not complete", false);
      }
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

  private void activeThread() {
    if (closed) throw new PolicyRefusal("owner.closed", "This runtime context is closed");
    ownerThread.run();
  }

  private void require(BooleanSupplier authorized) {
    activeThread();
    final boolean admitted;
    try {
      admitted = Objects.requireNonNull(authorized).getAsBoolean();
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
    var current = handle.view();
    var type = definition.type();
    var value = current.value();
    var replacement =
        new SettingsHandle.View<>(
            context,
            current.revision(),
            value.isPresent()
                ? SettingsHandle.Availability.AVAILABLE
                : SettingsHandle.Availability.UNAVAILABLE,
            value.isPresent() ? type : null,
            value.orElse(null),
            report);
    activeThread();
    requireRevision(current.revision());
    handle.publish(replacement);
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
