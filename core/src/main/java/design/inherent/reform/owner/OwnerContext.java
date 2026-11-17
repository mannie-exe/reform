package design.inherent.reform.owner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Explicit executors and lifetime. Native adapters bind access to actual actors, not payload IDs.
 */
public final class OwnerContext implements AutoCloseable {
  public record Limits(int maximumCalls, int maximumAccesses, int maximumCloseHooks) {
    public static final Limits STANDARD = new Limits(64, 64, 128);

    public Limits {
      if (maximumCalls < 1 || maximumAccesses < 1 || maximumCloseHooks < 1)
        throw new IllegalArgumentException("Owner context limits must be positive");
    }
  }

  public enum Right {
    READ,
    WRITE
  }

  private record Authorization(BooleanSupplier read, BooleanSupplier write) {}

  public final class Access implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final AtomicReference<Authorization> authorization;

    private Access(BooleanSupplier read, BooleanSupplier write) {
      authorization = new AtomicReference<>(new Authorization(read, write));
    }

    public UUID id() {
      return id;
    }

    public UUID context() {
      return OwnerContext.this.id;
    }

    public boolean isOpen() {
      return open && authorization.get() != null;
    }

    public void require(Right right) {
      Objects.requireNonNull(right);
      requireOwner();
      var checks = authorization.get();
      if (checks == null) throw closed("This access context is closed");
      final boolean permitted;
      try {
        permitted = (right == Right.READ ? checks.read() : checks.write()).getAsBoolean();
      } catch (PolicyRefusal refused) {
        throw refused;
      } catch (RuntimeException unavailable) {
        throw new PolicyRefusal(
            "owner.authorization_unavailable", "Actual authorization is unavailable", false);
      }
      requireOwner();
      if (authorization.get() != checks) throw closed("This access context changed");
      if (!permitted) throw new PolicyRefusal("owner.permission", "Current permission is required");
    }

    /** Worker-side lifetime guard only; native actor authorization runs on the owner executor. */
    public void requireCurrent() {
      OwnerContext.this.requireCurrent();
      if (authorization.get() == null) throw closed("This access context is closed");
    }

    @Override
    public void close() {
      authorization.set(null);
      synchronized (gate) {
        accesses.remove(this);
      }
    }
  }

  private final class Call<T> implements Runnable {
    private Callable<T> action;
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private final boolean nativeCall;
    private boolean claimed;

    Call(Callable<T> action, boolean nativeCall) {
      this.action = action;
      this.nativeCall = nativeCall;
    }

    @Override
    public void run() {
      final Callable<T> work;
      synchronized (gate) {
        if (claimed) return;
        claimed = true;
        waiting.remove(this);
        work = action;
        action = null;
      }
      T value = null;
      Throwable failure = null;
      try {
        if (nativeCall) requireOwner();
        else requireCurrent();
        value = work.call();
      } catch (Throwable failed) {
        failure = failed;
      }
      synchronized (gate) {
        outstanding--;
      }
      if (failure == null) result.complete(value);
      else result.completeExceptionally(failure);
    }

    boolean detach() {
      synchronized (gate) {
        if (claimed) return false;
        claimed = true;
        action = null;
        waiting.remove(this);
        outstanding--;
        return true;
      }
    }

    void refuse(Throwable reason) {
      if (detach()) result.completeExceptionally(reason);
    }
  }

  private final Object gate = new Object();
  private final UUID id = UUID.randomUUID();
  private final Limits limits;
  private Executor ownerExecutor, workerExecutor;
  private Runnable ownerThread;
  private volatile boolean open = true;
  private final Set<Call<?>> waiting = new HashSet<>();
  private final Set<Access> accesses = new HashSet<>();
  private final ArrayList<Runnable> closeHooks = new ArrayList<>();
  private int outstanding;

  /** Borrowed executors: the factory/runtime owns and bounds their actual queues and shutdown. */
  public OwnerContext(
      Executor ownerExecutor, Executor workerExecutor, Runnable ownerThread, Limits limits) {
    this.ownerExecutor = Objects.requireNonNull(ownerExecutor);
    this.workerExecutor = Objects.requireNonNull(workerExecutor);
    this.ownerThread = Objects.requireNonNull(ownerThread);
    this.limits = Objects.requireNonNull(limits);
  }

  public UUID id() {
    return id;
  }

  public boolean isOpen() {
    return open;
  }

  public int outstandingCalls() {
    synchronized (gate) {
      return outstanding;
    }
  }

  public void requireCurrent() {
    if (!open) throw closed("This owner context is closed");
  }

  /** Thread verification remains usable during owner-thread close hooks. */
  public void checkOwnerThread() {
    final Runnable check;
    synchronized (gate) {
      check = ownerThread;
    }
    if (check == null) throw closed("This owner executor has stopped");
    check.run();
  }

  public void requireOwner() {
    requireCurrent();
    checkOwnerThread();
    requireCurrent();
  }

  public Access access(BooleanSupplier read, BooleanSupplier write) {
    requireOwner();
    Objects.requireNonNull(read);
    Objects.requireNonNull(write);
    synchronized (gate) {
      requireCurrent();
      if (accesses.size() >= limits.maximumAccesses()) throw busy();
      var access = new Access(read, write);
      accesses.add(access);
      return access;
    }
  }

  public void onClose(Runnable hook) {
    requireOwner();
    synchronized (gate) {
      if (closeHooks.size() >= limits.maximumCloseHooks()) throw busy();
      closeHooks.add(Objects.requireNonNull(hook));
    }
  }

  public <T> CompletionStage<T> onOwner(Callable<T> work) {
    return dispatch(work, true);
  }

  public <T> CompletionStage<T> onWorker(Callable<T> work) {
    return dispatch(work, false);
  }

  private <T> CompletionStage<T> dispatch(Callable<T> work, boolean nativeCall) {
    Objects.requireNonNull(work);
    final Call<T> call;
    final Executor executor;
    synchronized (gate) {
      if (!open)
        return CompletableFuture.<T>failedFuture(closed("This owner context is closed"))
            .minimalCompletionStage();
      if (outstanding >= limits.maximumCalls())
        return CompletableFuture.<T>failedFuture(busy()).minimalCompletionStage();
      call = new Call<>(work, nativeCall);
      executor = nativeCall ? ownerExecutor : workerExecutor;
      waiting.add(call);
      outstanding++;
    }
    try {
      executor.execute(call);
    } catch (RuntimeException rejected) {
      call.refuse(rejected);
    }
    return call.result.minimalCompletionStage();
  }

  @Override
  public void close() {
    if (!open) return;
    checkOwnerThread();
    final ArrayList<Call<?>> queued;
    final ArrayList<Access> actors;
    final ArrayList<Runnable> hooks;
    synchronized (gate) {
      if (!open) return;
      open = false;
      queued = new ArrayList<>(waiting);
      for (var call : queued) call.detach();
      actors = new ArrayList<>(accesses);
      hooks = new ArrayList<>(closeHooks);
      closeHooks.clear();
    }
    for (var access : actors) access.close();
    for (var call : queued)
      call.result.completeExceptionally(closed("Context closed before execution"));
    Throwable failure = null;
    try {
      for (var hook : hooks)
        try {
          hook.run();
        } catch (RuntimeException | Error failed) {
          if (failure == null) failure = failed;
          else if (failure != failed) failure.addSuppressed(failed);
        }
    } finally {
      synchronized (gate) {
        ownerExecutor = null;
        workerExecutor = null;
        ownerThread = null;
      }
    }
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  private static PolicyRefusal busy() {
    return new PolicyRefusal("owner.busy", "Owner context admission is full");
  }

  private static PolicyRefusal closed(String message) {
    return new PolicyRefusal("owner.closed", message);
  }
}
