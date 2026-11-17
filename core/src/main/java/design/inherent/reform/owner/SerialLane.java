package design.inherent.reform.owner;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** One bounded logical operation at a time; no physical lock spans an asynchronous callback. */
public final class SerialLane implements AutoCloseable {
  private final class Pending<T> {
    private Supplier<CompletionStage<T>> action;
    private final CompletableFuture<T> result = new CompletableFuture<>();

    Pending(Supplier<CompletionStage<T>> action) {
      this.action = action;
    }

    void start() {
      final Supplier<CompletionStage<T>> work;
      synchronized (gate) {
        work = action;
        action = null;
      }
      if (work == null) return;
      try {
        Objects.requireNonNull(work.get()).whenComplete(this::complete);
      } catch (Throwable failure) {
        complete(null, failure);
      }
    }

    private void complete(T value, Throwable failure) {
      var next = finished(this);
      if (failure == null) result.complete(value);
      else result.completeExceptionally(failure);
      if (next != null) next.start();
    }
  }

  private final Object gate = new Object();
  private final int maximum;
  private final ArrayDeque<Pending<?>> waiting = new ArrayDeque<>();
  private Pending<?> active;
  private boolean open = true;

  public SerialLane(int maximum) {
    if (maximum < 1) throw new IllegalArgumentException("Operation bound must be positive");
    this.maximum = maximum;
  }

  public <T> CompletionStage<T> submit(Supplier<CompletionStage<T>> action) {
    Objects.requireNonNull(action);
    final Pending<T> pending;
    final boolean start;
    synchronized (gate) {
      if (!open) return CompletableFuture.<T>failedFuture(closed()).minimalCompletionStage();
      if (waiting.size() + (active == null ? 0 : 1) >= maximum)
        return CompletableFuture.<T>failedFuture(
                new PolicyRefusal("owner.busy", "Document operation admission is full"))
            .minimalCompletionStage();
      pending = new Pending<>(action);
      start = active == null;
      if (start) active = pending;
      else waiting.addLast(pending);
    }
    if (start) pending.start();
    return pending.result.minimalCompletionStage();
  }

  private Pending<?> finished(Pending<?> previous) {
    synchronized (gate) {
      if (active != previous) return null;
      active = waiting.pollFirst();
      return active;
    }
  }

  @Override
  public void close() {
    final ArrayList<Pending<?>> queued;
    synchronized (gate) {
      if (!open) return;
      open = false;
      queued = new ArrayList<>(waiting);
      waiting.clear();
      if (active != null && active.action != null) {
        queued.add(active);
        active = null;
      }
      for (var pending : queued) pending.action = null;
    }
    for (var pending : queued) pending.result.completeExceptionally(closed());
  }

  private static PolicyRefusal closed() {
    return new PolicyRefusal("owner.closed", "Document closed before operation admission");
  }
}
