package design.inherent.reform.operation;

import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** A context/actor-bound local handle. Cancelling a future copy does not request cancellation. */
public final class OperationHandle<O> {
  private final Supplier<OperationSnapshot<O>> snapshot;
  private final Supplier<CompletionStage<OperationSnapshot<O>>> cancel;
  private final CompletionStage<OperationSnapshot<O>> completion;

  OperationHandle(
      Supplier<OperationSnapshot<O>> snapshot,
      Supplier<CompletionStage<OperationSnapshot<O>>> cancel,
      CompletionStage<OperationSnapshot<O>> completion) {
    this.snapshot = snapshot;
    this.cancel = cancel;
    this.completion = completion;
  }

  public OperationSnapshot<O> snapshot() {
    return snapshot.get();
  }

  public CompletionStage<OperationSnapshot<O>> completion() {
    return completion;
  }

  public CompletionStage<OperationSnapshot<O>> requestCancellation() {
    return cancel.get();
  }
}
