package design.inherent.reform.operation;

import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicInputBasis;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.reference.ReferenceAudition;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Explicit ownership of copied audition input. Closing requests cancellation; entered work retains
 * its charge until the actual source operation settles. History retains only the data-only receipt.
 */
public final class AuditionCapture<T> implements AutoCloseable {
  public record Receipt(DraftRevision base, GraphicInputBasis.Audition basis, long copiedBytes) {
    public Receipt {
      Objects.requireNonNull(base);
      Objects.requireNonNull(basis);
      if (copiedBytes < 0) throw new IllegalArgumentException("Negative copied input");
    }
  }

  /** Immutable data borrowed for one entered consumer; close only after that work drains. */
  public static final class Use<T> implements AutoCloseable {
    private final AuditionCapture<T> owner;
    private ReferenceAudition.Overlay<T> value;

    private Use(AuditionCapture<T> owner, ReferenceAudition.Overlay<T> value) {
      this.owner = owner;
      this.value = value;
    }

    public synchronized ReferenceAudition.Overlay<T> value() {
      if (value == null) throw new IllegalStateException("This copied-input use has retired");
      return value;
    }

    @Override
    public void close() {
      synchronized (this) {
        if (value == null) return;
        value = null;
      }
      owner.retireUse();
    }
  }

  private final OwnerContext context;
  private final Runnable requireCurrent, retired;
  private final OwnerContext.CloseHook closeHook;
  private final CompletableFuture<AuditionCapture<T>> completion = new CompletableFuture<>();
  private OperationHandle<Receipt> operation;
  private GraphicLedger.Allocation allocation;
  private ReferenceAudition.Overlay<T> overlay;
  private boolean closed, terminal, ready, released;
  private int uses;

  AuditionCapture(OwnerContext context, Runnable requireCurrent, Runnable retired) {
    this.context = context;
    this.requireCurrent = requireCurrent;
    this.retired = retired;
    closeHook = context.onClose(this::close);
  }

  void bind(OperationHandle<Receipt> operation) {
    synchronized (this) {
      this.operation = operation;
    }
    operation.completion().whenComplete((result, failed) -> finish(result, failed));
  }

  private void finish(OperationSnapshot<Receipt> result, Throwable failed) {
    synchronized (this) {
      terminal = true;
      ready = failed == null && result.state() == OperationSnapshot.State.COMPLETED && !closed;
      if (!ready) {
        closed = true;
        release();
      }
    }
    if (ready) completion.complete(this);
    else
      completion.completeExceptionally(
          failed != null
              ? failed
              : new PolicyRefusal(
                  result != null && !result.report().valid()
                      ? result.report()
                      : new ValidationReport(
                          java.util.List.of(
                              design.inherent.reform.validation.Issue.error(
                                  "operation.cancelled",
                                  design.inherent.reform.definition.FieldPath.ROOT,
                                  "The audition capture was closed")),
                          true)));
  }

  synchronized void checkpoint(OperationScope.Execution execution) {
    execution.checkpoint();
    if (closed) throw new PolicyRefusal("operation.cancelled", "The audition capture was closed");
  }

  synchronized void charge(GraphicLedger.Allocation value) {
    if (allocation != null)
      throw new IllegalStateException("An audition already owns copied input");
    // Even a concurrently closed capture keeps this charge until actual operation completion.
    allocation = value;
  }

  synchronized Receipt offer(ReferenceAudition.Overlay<T> value) {
    if (closed) throw new PolicyRefusal("operation.cancelled", "The audition capture was closed");
    overlay = value;
    return new Receipt(value.base().target(), value.basis(), value.copiedBytes());
  }

  public CompletionStage<AuditionCapture<T>> completion() {
    return completion.minimalCompletionStage();
  }

  public synchronized OperationHandle<Receipt> sourceOperation() {
    return Objects.requireNonNull(operation, "Capture is not submitted");
  }

  /** Recheck actual owner/source admission before borrowing the complete copied candidate. */
  public Use<T> borrow() {
    context.requireOwner();
    requireCurrent.run();
    synchronized (this) {
      if (!ready || closed)
        throw new PolicyRefusal("preview.capture_closed", "The audition input is not available");
      if (uses >= 16)
        throw new PolicyRefusal("preview.busy", "Too many entered uses of this copied input");
      uses++;
      return new Use<>(this, overlay);
    }
  }

  /** Explicit cancellation of this capture only, not cancellation of a detached future copy. */
  public CompletionStage<OperationSnapshot<Receipt>> requestCancellation() {
    context.requireOwner();
    return sourceOperation().requestCancellation();
  }

  /**
   * Thread-safe cleanup: prevent new borrows and request cancellation through the actual owner.
   * Entered consumers retire their own uses; closed contexts never dispatch another owner call.
   */
  @Override
  public void close() {
    OperationHandle<Receipt> active;
    synchronized (this) {
      if (closed) return;
      closed = true;
      active = operation;
      if (terminal) release();
    }
    if (active != null && context.isOpen() && !active.snapshot().terminal())
      active.requestCancellation();
  }

  void submissionFailed(Throwable failure) {
    finish(null, failure);
  }

  private synchronized void retireUse() {
    uses--;
    if (closed && terminal) release();
  }

  private void release() {
    if (released || uses != 0) return;
    released = true;
    overlay = null;
    if (allocation != null) allocation.close();
    allocation = null;
    closeHook.close();
    retired.run();
  }
}
