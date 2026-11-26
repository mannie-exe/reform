package design.inherent.reform.operation;

import design.inherent.reform.edit.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.graphics.*;
import java.util.Objects;
import java.util.concurrent.*;

/** Budgeted graphics draft capture; the existing full inspection receipt API remains separate. */
public final class DraftGraphicCapture<T> implements AutoCloseable {
  public record Receipt(DraftRevision base, GraphicInputBasis.Content content, long copiedBytes) {
    public Receipt {
      Objects.requireNonNull(base);
      Objects.requireNonNull(content);
      if (copiedBytes < content.bytes())
        throw new IllegalArgumentException("Copied input does not cover its candidate");
    }
  }

  public static final class Data<T> {
    private final EditorActor.Preflight<T> base;
    private final GraphicInputBasis.Content content;
    private final long copiedBytes;

    Data(EditorActor.Preflight<T> base, GraphicInputBasis.Content content, long copiedBytes) {
      this.base = Objects.requireNonNull(base);
      this.content = Objects.requireNonNull(content);
      this.copiedBytes = copiedBytes;
      if (!base.report().valid() || base.candidate().isEmpty() || copiedBytes < content.bytes())
        throw new IllegalArgumentException(
            "A graphics draft needs complete validated input and its charge");
    }

    public EditorActor.Preflight<T> base() {
      return base;
    }

    public Candidate<T> candidate() {
      return base.candidate().orElseThrow();
    }

    public GraphicInputBasis.Content content() {
      return content;
    }

    public long copiedBytes() {
      return copiedBytes;
    }
  }

  public static final class Use<T> implements AutoCloseable {
    private final CapturedInputLease.Use<Data<T>> use;

    private Use(CapturedInputLease.Use<Data<T>> use) {
      this.use = use;
    }

    public Data<T> value() {
      return use.value();
    }

    @Override
    public void close() {
      use.close();
    }
  }

  private final CapturedInputLease<Data<T>, Receipt> state;
  private final CompletableFuture<DraftGraphicCapture<T>> completion = new CompletableFuture<>();

  DraftGraphicCapture(OwnerContext context, Runnable current, Runnable retired) {
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

  void charge(GraphicLedger.Allocation value) {
    state.charge(value);
  }

  Receipt offer(Data<T> data) {
    return state.offer(data, new Receipt(data.base().target(), data.content(), data.copiedBytes()));
  }

  public CompletionStage<DraftGraphicCapture<T>> completion() {
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

  /** Thread-safe cleanup; cannot refund entered work/use before actual drain. */
  @Override
  public void close() {
    state.close();
  }

  void submissionFailed(Throwable failed) {
    state.submissionFailed(failed);
  }
}
