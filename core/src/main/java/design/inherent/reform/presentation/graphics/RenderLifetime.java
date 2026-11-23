package design.inherent.reform.presentation.graphics;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;
import java.util.Optional;

/**
 * A view's release is distinct from actual native-use retirement. Only the native adapter owns
 * uses; editor closure or elapsed time is not permission to retire one.
 */
public final class RenderLifetime implements AutoCloseable {
  public record State(
      boolean closing,
      int outstandingUses,
      boolean releaseAttempted,
      boolean releaseCompleted,
      Optional<Throwable> releaseFailure) {
    public boolean released() {
      return releaseCompleted && releaseFailure.isEmpty();
    }
  }

  public final class Use {
    private boolean retired;

    private Use() {}

    /** Called by the native adapter only after consumption, or its actual native teardown. */
    public void retire() {
      requireNativeThread.run();
      synchronized (RenderLifetime.this) {
        if (retired) return;
        retired = true;
        outstanding--;
        releaseIfReady();
      }
    }
  }

  private final int maximumUses;
  private final Runnable requireNativeThread, release;
  private int outstanding;
  private boolean closing, releaseAttempted, releaseCompleted;
  private Throwable releaseFailure;
  private final java.util.concurrent.CompletableFuture<State> releaseResult =
      new java.util.concurrent.CompletableFuture<>();

  /** Settles after actual release entry completes or fails; inspect the returned state. */
  public java.util.concurrent.CompletionStage<State> releaseCompletion() {
    return releaseResult.minimalCompletionStage();
  }

  public RenderLifetime(int maximumUses, Runnable requireNativeThread, Runnable release) {
    if (maximumUses < 1) throw new IllegalArgumentException("Native-use retention must be bounded");
    this.maximumUses = maximumUses;
    this.requireNativeThread = Objects.requireNonNull(requireNativeThread);
    this.release = Objects.requireNonNull(release);
  }

  public Use acquire() {
    requireNativeThread.run();
    synchronized (this) {
      if (closing)
        throw new PolicyRefusal("preview.closed", "The graphic is closed to new native uses");
      if (outstanding >= maximumUses)
        throw new PolicyRefusal("preview.budget", "Earlier native uses have not retired");
      outstanding++;
      return new Use();
    }
  }

  public synchronized State state() {
    return new State(
        closing,
        outstanding,
        releaseAttempted,
        releaseCompleted,
        Optional.ofNullable(releaseFailure));
  }

  /** Revoke new uses. Existing native consumers keep ownership until they actually retire. */
  @Override
  public void close() {
    requireNativeThread.run();
    synchronized (this) {
      closing = true;
      releaseIfReady();
    }
  }

  private void releaseIfReady() {
    if (!closing || outstanding != 0 || releaseAttempted) return;
    releaseAttempted = true;
    try {
      release.run();
      releaseCompleted = true;
    } catch (RuntimeException | Error failed) {
      releaseFailure = failed;
      throw failed;
    } finally {
      releaseResult.complete(state());
    }
  }
}
