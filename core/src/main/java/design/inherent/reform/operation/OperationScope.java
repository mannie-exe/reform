package design.inherent.reform.operation;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SerialLane;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Bounded local operation observations using borrowed owner/worker executors. Providers keep their
 * domain scheduling, actual effect preflight and result/readback policies. This is not a job pool.
 */
public final class OperationScope implements AutoCloseable {
  public record Limits(
      int maximumDefinitions, int maximumActive, int maximumRetained, JsonLimits input) {
    public static final Limits STANDARD =
        new Limits(64, 16, 64, new JsonLimits(65536, 64, 16384, 32768, 128, 4096));

    public Limits {
      Objects.requireNonNull(input);
      if (maximumDefinitions < 1 || maximumActive < 1 || maximumRetained < maximumActive)
        throw new IllegalArgumentException("Invalid operation admission limits");
    }
  }

  @FunctionalInterface
  public interface Handler<I, O> {
    /** Called on the owner executor to compose stages; dispatch pure/IO work explicitly. */
    CompletionStage<O> run(Execution execution, I request);
  }

  public final class Execution {
    private final Job<?, ?> job;

    private Execution(Job<?, ?> job) {
      this.job = job;
    }

    public boolean cancellationRequested() {
      return job.snapshot().cancellationRequested();
    }

    /**
     * A cooperative safe point, for use before effects or when the provider has stopped/drained its
     * own work. Never abandon an outstanding native save and interpret this signal as rollback.
     */
    public void checkpoint() {
      if (cancellationRequested())
        throw new PolicyRefusal(
            "operation.cancelled", "Operation was cancelled before its next effect");
    }

    public boolean progress(OperationSnapshot.Progress progress) {
      return job.progress(Objects.requireNonNull(progress));
    }
  }

  private static final class Stopped extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  private record Registered<I, O>(OperationDescriptor<I, O> descriptor, Handler<I, O> handler) {}

  private final class Job<I, O> {
    private final UUID id = UUID.randomUUID();
    private final OperationDescriptor<I, O> descriptor;
    private OwnerContext.Access access;
    private final CompletableFuture<OperationSnapshot<O>> completion = new CompletableFuture<>();
    private volatile OperationSnapshot<O> current;
    private final OperationHandle<O> handle;

    Job(OperationDescriptor<I, O> descriptor, OwnerContext.Access access) {
      this.descriptor = descriptor;
      this.access = access;
      current =
          new OperationSnapshot<>(
              id,
              descriptor.id(),
              context.id(),
              descriptor.target(),
              0,
              OperationSnapshot.State.QUEUED,
              false,
              false,
              OperationSnapshot.Progress.QUEUED,
              Optional.empty(),
              ValidationReport.VALID);
      handle =
          new OperationHandle<>(
              this::snapshot,
              () -> {
                if (current.terminal()) return CompletableFuture.completedStage(current);
                return context.onOwner(
                    () -> {
                      final OwnerContext.Access actor;
                      synchronized (gate) {
                        if (current.terminal()) return current;
                        actor = this.access;
                      }
                      actor.require(descriptor.right());
                      return cancel();
                    });
              },
              completion.minimalCompletionStage());
    }

    OperationSnapshot<O> snapshot() {
      return current;
    }

    private void update(
        OperationSnapshot.State state,
        boolean started,
        boolean cancellation,
        OperationSnapshot.Progress progress,
        Optional<O> result,
        ValidationReport report) {
      current =
          new OperationSnapshot<>(
              id,
              descriptor.id(),
              context.id(),
              descriptor.target(),
              current.revision() + 1,
              state,
              started,
              cancellation,
              progress,
              result,
              report);
    }

    private OperationSnapshot<O> cancel() {
      synchronized (gate) {
        if (current.terminal() || current.cancellationRequested()) return current;
        update(
            OperationSnapshot.State.CANCELLATION_REQUESTED,
            current.started(),
            true,
            current.progress(),
            Optional.empty(),
            current.report());
        return current;
      }
    }

    private boolean progress(OperationSnapshot.Progress progress) {
      synchronized (gate) {
        if (current.terminal()) return false;
        if (current.progress().phase().equals(progress.phase())
            && progress.completed() < current.progress().completed())
          throw new IllegalArgumentException("Progress moved backwards within its phase");
        update(
            current.state(),
            current.started(),
            current.cancellationRequested(),
            progress,
            Optional.empty(),
            current.report());
        return true;
      }
    }

    private CompletionStage<CompletionStage<O>> start(I prepared, Handler<I, O> handler) {
      return context.onOwner(
          () -> {
            final OwnerContext.Access actor;
            synchronized (gate) {
              if (closed || current.cancellationRequested()) throw new Stopped();
              actor = access;
            }
            actor.require(descriptor.right());
            synchronized (gate) {
              if (closed || current.cancellationRequested()) throw new Stopped();
              update(
                  OperationSnapshot.State.RUNNING,
                  true,
                  false,
                  new OperationSnapshot.Progress("running", 0, java.util.OptionalLong.empty(), ""),
                  Optional.empty(),
                  ValidationReport.VALID);
            }
            return Objects.requireNonNull(handler.run(new Execution(this), prepared));
          });
    }

    private void finish(O result, Throwable failed) {
      while (failed instanceof CompletionException && failed.getCause() != null)
        failed = failed.getCause();
      final OperationSnapshot<O> settled;
      synchronized (gate) {
        if (current.terminal()) return;
        if (failed == null) {
          if (result == null) {
            failed = new IllegalStateException("Operation provider returned no result");
          } else
            update(
                OperationSnapshot.State.COMPLETED,
                current.started(),
                current.cancellationRequested(),
                current.progress(),
                Optional.of(result),
                ValidationReport.VALID);
        }
        if (failed != null) {
          var state =
              failed instanceof Stopped || cancelled(failed)
                  ? OperationSnapshot.State.CANCELLED
                  : failed instanceof PolicyRefusal
                      ? OperationSnapshot.State.REFUSED
                      : OperationSnapshot.State.FAILED;
          var report =
              failed instanceof PolicyRefusal refused
                  ? refused.report()
                  : failed instanceof Stopped
                      ? ValidationReport.VALID
                      : new ValidationReport(
                          List.of(
                              Issue.error(
                                  "operation.failed",
                                  FieldPath.ROOT,
                                  "Operation could not complete; review the owner's effect result")),
                          false);
          update(
              state,
              current.started(),
              current.cancellationRequested(),
              current.progress(),
              Optional.empty(),
              report);
        }
        active--;
        access = null;
        settled = current;
        if (closed) jobs.remove(id);
      }
      completion.complete(settled);
    }
  }

  private final Object gate = new Object();

  private static boolean cancelled(Throwable failure) {
    return failure instanceof PolicyRefusal refused
        && refused.report().issues().size() == 1
        && refused.report().issues().getFirst().code().equals("operation.cancelled");
  }

  private final OwnerContext context;
  private OwnerContext.Access access;
  private final Limits limits;
  private final SerialLane invocations;
  private final Map<DocumentId, Registered<?, ?>> definitions = new LinkedHashMap<>();
  private final Map<UUID, Job<?, ?>> jobs = new LinkedHashMap<>();
  private final OwnerContext.CloseHook closeHook;
  private boolean frozen;
  private volatile boolean closed;
  private int active;

  public OperationScope(OwnerContext context, OwnerContext.Access access, Limits limits) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    if (!Objects.requireNonNull(access).context().equals(context.id()) || !access.isOpen())
      throw new IllegalArgumentException("Operation scope requires its actual actor access");
    this.access = access;
    this.limits = Objects.requireNonNull(limits);
    invocations = new SerialLane(limits.maximumActive());
    closeHook = context.onClose(this::close);
  }

  public <I, O> void register(OperationDescriptor<I, O> descriptor, Handler<I, O> handler) {
    context.requireOwner();
    Objects.requireNonNull(descriptor);
    Objects.requireNonNull(handler);
    synchronized (gate) {
      if (closed || frozen) throw new IllegalStateException("Operation registration is closed");
      if (definitions.size() >= limits.maximumDefinitions())
        throw new IllegalStateException("Too many operation definitions");
      if (definitions.putIfAbsent(descriptor.id(), new Registered<>(descriptor, handler)) != null)
        throw new IllegalArgumentException("Duplicate operation identity");
    }
  }

  public void freeze() {
    context.requireOwner();
    synchronized (gate) {
      if (closed) throw new IllegalStateException("Operation scope is closed");
      frozen = true;
    }
  }

  /** Request values must be immutable while worker admission snapshots/validates them. */
  public <I, O> OperationHandle<O> submit(
      OperationDescriptor<I, O> descriptor, OperationDescriptor.EntryPoint entry, I request) {
    return submit(descriptor, entry, request, CompletableFuture.completedStage(null));
  }

  /** A prerequisite gates provider entry, never authority; native access is checked afterward. */
  public <I, O> OperationHandle<O> submit(
      OperationDescriptor<I, O> descriptor,
      OperationDescriptor.EntryPoint entry,
      I request,
      CompletionStage<?> prerequisite) {
    context.requireOwner();
    Objects.requireNonNull(prerequisite);
    Objects.requireNonNull(request);
    Objects.requireNonNull(entry);
    final OwnerContext.Access actor;
    synchronized (gate) {
      actor = access;
    }
    if (actor == null) throw new PolicyRefusal("owner.closed", "Operation scope is closed");
    actor.require(descriptor.right());
    final Registered<I, O> registered;
    final Job<I, O> job;
    synchronized (gate) {
      if (closed || !frozen)
        throw new PolicyRefusal("operation.unavailable", "Operations are not ready");
      var found = definitions.get(descriptor.id());
      if (found == null
          || found.descriptor() != descriptor
          || !descriptor.entryPoints().contains(entry))
        throw new PolicyRefusal(
            "operation.unavailable", "Operation is not registered for this entry point");
      if (active >= limits.maximumActive())
        throw new PolicyRefusal("operation.busy", "Active operation admission is full");
      if (jobs.size() >= limits.maximumRetained()) {
        var evict =
            jobs.entrySet().stream()
                .filter(item -> item.getValue().snapshot().terminal())
                .findFirst();
        if (evict.isEmpty())
          throw new PolicyRefusal("operation.busy", "Retained operation admission is full");
        jobs.remove(evict.orElseThrow().getKey());
      }
      @SuppressWarnings(
          "unchecked") // Exact registered descriptor identity pairs its typed handler.
      var paired = (Registered<I, O>) found;
      registered = paired;
      job = new Job<>(descriptor, actor);
      jobs.put(job.id, job);
      active++;
    }
    var preparation =
        context.onWorker(
            () -> {
              new Execution(job).checkpoint();
              var frozenInput = descriptor.input().freeze(request);
              var encoded = descriptor.input().codec().encodeStart(JsonOps.INSTANCE, frozenInput);
              if (encoded.result().isEmpty() || encoded.error().isPresent())
                throw new PolicyRefusal("operation.input", "Operation input is invalid");
              final design.inherent.reform.validation.DecodedValue<I> decoded;
              try {
                decoded =
                    JsonInput.read(encoded.result().orElseThrow().toString(), limits.input())
                        .decode(descriptor.input());
              } catch (JsonInputException invalid) {
                throw new PolicyRefusal(
                    "operation.input", "Operation input exceeds its declared admission");
              }
              if (decoded.value().isEmpty() || !decoded.report().valid())
                throw new PolicyRefusal(decoded.report());
              return decoded.value().orElseThrow();
            });
    // Input work may run in parallel. Enter providers in submission order, releasing this lane as
    // soon as the provider RETURNS its stage; never wait for a save/preview to finish here.
    invocations
        .submit(
            () ->
                prerequisite
                    .thenCompose(ignored -> preparation)
                    .thenCompose(prepared -> job.start(prepared, registered.handler())))
        .whenComplete(
            (provider, failed) -> {
              if (failed != null) job.finish(null, failed);
              else provider.whenComplete(job::finish);
            });
    return job.handle;
  }

  public List<OperationSnapshot<?>> snapshots() {
    context.requireOwner();
    synchronized (gate) {
      return jobs.values().stream().<OperationSnapshot<?>>map(Job::snapshot).toList();
    }
  }

  /** Looks up only this actor scope; a job UUID alone never selects another actor's operation. */
  public Optional<OperationHandle<?>> find(UUID id) {
    context.requireOwner();
    final OwnerContext.Access actor;
    synchronized (gate) {
      actor = access;
    }
    if (actor == null) return Optional.empty();
    actor.require(OwnerContext.Right.READ);
    synchronized (gate) {
      var job = jobs.get(Objects.requireNonNull(id));
      return job == null ? Optional.empty() : Optional.of(job.handle);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    context.checkOwnerThread();
    final ArrayList<Job<?, ?>> draining;
    synchronized (gate) {
      if (closed) return;
      closed = true;
      access = null;
      definitions.clear();
      jobs.entrySet().removeIf(item -> item.getValue().snapshot().terminal());
      draining = new ArrayList<>(jobs.values());
    }
    for (var job : draining) job.cancel();
    invocations.close();
    closeHook.close();
  }
}
