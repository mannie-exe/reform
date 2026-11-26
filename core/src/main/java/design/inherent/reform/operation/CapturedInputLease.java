package design.inherent.reform.operation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import design.inherent.reform.validation.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared copied-input lifetime, not an executor, capture backend, or transferable authority. */
final class CapturedInputLease<V, R> implements AutoCloseable {
  static final class Use<V> implements AutoCloseable {
    private final CapturedInputLease<V, ?> owner;
    private V value;

    Use(CapturedInputLease<V, ?> owner, V value) {
      this.owner = owner;
      this.value = value;
    }

    synchronized V value() {
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
  private Runnable requireCurrent, retired;
  private final OwnerContext.CloseHook closeHook;
  private final CompletableFuture<Void> completion = new CompletableFuture<>();
  private OperationHandle<R> operation;
  private GraphicLedger.Allocation allocation;
  private V value;
  private boolean closed, terminal, ready, released;
  private int uses;

  CapturedInputLease(OwnerContext context, Runnable requireCurrent, Runnable retired) {
    this.context = Objects.requireNonNull(context);
    this.requireCurrent = Objects.requireNonNull(requireCurrent);
    this.retired = Objects.requireNonNull(retired);
    closeHook = context.onClose(this::close);
  }

  void bind(OperationHandle<R> operation) {
    synchronized (this) {
      if (this.operation != null || !context.id().equals(operation.snapshot().context()))
        throw new IllegalArgumentException("Bind this capture once to its actual owner operation");
      this.operation = operation;
    }
    operation.completion().whenComplete(this::finish);
  }

  private void finish(OperationSnapshot<R> result, Throwable failed) {
    boolean delivered;
    synchronized (this) {
      terminal = true;
      ready = failed == null && result.state() == OperationSnapshot.State.COMPLETED && !closed;
      delivered = ready;
      if (!ready) {
        closed = true;
        release();
      }
    }
    if (delivered) completion.complete(null);
    else
      completion.completeExceptionally(
          failed != null
              ? failed
              : new PolicyRefusal(
                  result != null && !result.report().valid()
                      ? result.report()
                      : new ValidationReport(
                          List.of(
                              Issue.error(
                                  "operation.cancelled",
                                  FieldPath.ROOT,
                                  "The captured preview input was closed")),
                          true)));
  }

  CompletionStage<Void> completion() {
    return completion.minimalCompletionStage();
  }

  synchronized OperationHandle<R> operation() {
    return Objects.requireNonNull(operation, "Capture is not submitted");
  }

  synchronized void checkpoint(OperationScope.Execution execution) {
    execution.checkpoint();
    if (closed)
      throw new PolicyRefusal("operation.cancelled", "The captured preview input was closed");
  }

  synchronized void charge(GraphicLedger.Allocation value) {
    if (allocation != null) throw new IllegalStateException("A capture already owns copied input");
    allocation = Objects.requireNonNull(value);
  }

  synchronized GraphicLedger.Allocation allocation() {
    return Objects.requireNonNull(allocation, "Input has not been admitted");
  }

  synchronized R offer(V value, R receipt) {
    if (closed)
      throw new PolicyRefusal("operation.cancelled", "The captured preview input was closed");
    this.value = Objects.requireNonNull(value);
    return Objects.requireNonNull(receipt);
  }

  Use<V> borrow() {
    context.requireOwner();
    final Runnable current;
    synchronized (this) {
      requireReady();
      current = requireCurrent;
    }
    current.run();
    synchronized (this) {
      requireReady();
      if (uses >= 16)
        throw new PolicyRefusal("preview.busy", "Too many entered uses of this copied input");
      uses++;
      return new Use<>(this, value);
    }
  }

  private void requireReady() {
    if (!ready || closed)
      throw new PolicyRefusal(
          "preview.capture_closed", "The captured preview input is not available");
  }

  CompletionStage<OperationSnapshot<R>> requestCancellation() {
    context.requireOwner();
    return operation().requestCancellation();
  }

  @Override
  public void close() {
    final OperationHandle<R> active;
    synchronized (this) {
      if (closed) return;
      closed = true;
      active = operation;
      if (terminal) release();
    }
    if (active != null && context.isOpen() && !active.snapshot().terminal())
      active.requestCancellation();
  }

  void submissionFailed(Throwable failed) {
    finish(null, Objects.requireNonNull(failed));
  }

  private synchronized void retireUse() {
    uses--;
    if (closed && terminal) release();
  }

  private void release() {
    if (released || uses != 0) return;
    released = true;
    value = null;
    requireCurrent = null;
    if (allocation != null) allocation.close();
    allocation = null;
    closeHook.close();
    var callback = retired;
    retired = null;
    callback.run();
  }
}
