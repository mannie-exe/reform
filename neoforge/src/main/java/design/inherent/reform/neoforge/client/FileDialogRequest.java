package design.inherent.reform.neoforge.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** One worker-entry claim. Abandoning entered native UI does not pretend to close it. */
final class FileDialogRequest {
  private enum State {
    QUEUED,
    ENTERED,
    ABANDONED
  }

  private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);

  String select(Supplier<String> nativeDialog) {
    Objects.requireNonNull(nativeDialog);
    if (!state.compareAndSet(State.QUEUED, State.ENTERED)) return null;
    return nativeDialog.get();
  }

  /** A true result means the worker cannot enter; false also includes already entered UI. */
  boolean abandonQueued() {
    return state.compareAndSet(State.QUEUED, State.ABANDONED);
  }
}
