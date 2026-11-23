package design.inherent.reform.minecraft.client;

import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.graphics.GraphicLedger;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/** Borrowed stages of one actual operation. No new job pool or second cancellation backend. */
public final class ClientGraphicWork {
  private final OwnerContext context;
  private final OperationScope.Execution execution;
  private final GraphicLedger ledger;
  private final Runnable current;

  ClientGraphicWork(
      OwnerContext context,
      OperationScope.Execution execution,
      GraphicLedger ledger,
      Runnable current) {
    this.context = context;
    this.execution = execution;
    this.ledger = ledger;
    this.current = current;
  }

  public GraphicLedger ledger() {
    return ledger;
  }

  public void checkpoint() {
    execution.checkpoint();
  }

  /** Return detached bounded data. Close work-owned transient native resources inside the stage. */
  public <V> CompletionStage<V> onWorker(Callable<V> work) {
    return context.onWorker(
        () -> {
          checkpoint();
          var value = work.call();
          checkpoint();
          return value;
        });
  }

  /** Explicit native CPU preparation only. GPU upload and release belong to the shared host. */
  public <V> CompletionStage<V> onClient(Callable<V> work) {
    return context.onOwner(
        () -> {
          checkpoint();
          current.run();
          var value = work.call();
          current.run();
          checkpoint();
          return value;
        });
  }
}
