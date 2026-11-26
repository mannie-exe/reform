package design.inherent.reform.operation;

import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceAudition;
import java.util.Objects;
import java.util.concurrent.*;

/** Explicit copied audition ownership; history retains data-only receipts, never the candidate. */
public final class AuditionCapture<T> implements AutoCloseable {
  public record Receipt(DraftRevision base, GraphicInputBasis.Audition basis, long copiedBytes) {
    public Receipt {
      Objects.requireNonNull(base);
      Objects.requireNonNull(basis);
      if (copiedBytes < 0) throw new IllegalArgumentException("Negative copied input");
    }
  }

  public static final class Use<T> implements AutoCloseable {
    private final CapturedInputLease.Use<ReferenceAudition.Overlay<T>> use;

    private Use(CapturedInputLease.Use<ReferenceAudition.Overlay<T>> use) {
      this.use = use;
    }

    public ReferenceAudition.Overlay<T> value() {
      return use.value();
    }

    @Override
    public void close() {
      use.close();
    }
  }

  private final CapturedInputLease<ReferenceAudition.Overlay<T>, Receipt> state;
  private final CompletableFuture<AuditionCapture<T>> completion = new CompletableFuture<>();

  AuditionCapture(OwnerContext context, Runnable current, Runnable retired) {
    state = new CapturedInputLease<>(context, current, retired);
    state
        .completion()
        .whenComplete(
            (ignored, failed) -> {
              if (failed == null) completion.complete(this);
              else completion.completeExceptionally(failed);
            });
  }

  void bind(OperationHandle<Receipt> operation) {
    state.bind(operation);
  }

  void checkpoint(OperationScope.Execution execution) {
    state.checkpoint(execution);
  }

  void charge(GraphicLedger.Allocation allocation) {
    state.charge(allocation);
  }

  GraphicLedger.Allocation allocation() {
    return state.allocation();
  }

  Receipt offer(ReferenceAudition.Overlay<T> value) {
    return state.offer(
        value, new Receipt(value.base().target(), value.basis(), value.copiedBytes()));
  }

  public CompletionStage<AuditionCapture<T>> completion() {
    return completion.minimalCompletionStage();
  }

  public OperationHandle<Receipt> sourceOperation() {
    return state.operation();
  }

  /** Owner-thread admission; entered consumers close their counted use only after actual drain. */
  public Use<T> borrow() {
    return new Use<>(state.borrow());
  }

  public CompletionStage<OperationSnapshot<Receipt>> requestCancellation() {
    return state.requestCancellation();
  }

  /** Thread-safe cleanup; cannot refund an entered operation/use before its actual drain. */
  @Override
  public void close() {
    state.close();
  }

  void submissionFailed(Throwable failed) {
    state.submissionFailed(failed);
  }
}
