package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded presentation state and declared projections under a borrowed host lifetime. No document,
 * navigation shell, executor, persistence, or graphics service is constructed by this scope.
 */
public final class UiScope implements AutoCloseable {
  public record Usage(
      int scopes, int states, int projections, int dependencies, int closeHooks, long stateBytes) {}

  /** Closing a hook unregisters it; actual retirement attempts registered hooks exactly once. */
  public final class CloseHook implements AutoCloseable {
    private Runnable action;

    private CloseHook(Runnable action) {
      this.action = action;
    }

    @Override
    public void close() {
      if (action == null) return;
      kernel.owner.checkOwnerThread();
      // Actual host retirement may interrupt a trusted pure callback. Revoked scopes still need
      // to unregister cleanup hooks; live projections must not change lifecycle subscriptions.
      if (open) kernel.requireMutable();
      remove();
    }

    private Runnable remove() {
      var selected = action;
      if (selected != null) {
        action = null;
        hooks.remove(this);
        kernel.closeHooks--;
      }
      return selected;
    }
  }

  private abstract class Node<T> implements UiRead<T> {
    final UiCaptureKey<T> key;
    final Set<Projection<?>> dependents = new LinkedHashSet<>();
    UiObservation<T> cached;
    long bytes;

    Node(UiCaptureKey<T> key) {
      this.key = key;
    }

    UiScope scope() {
      return UiScope.this;
    }

    @Override
    public final UiObservation<T> snapshot() {
      requireCurrent();
      if (kernel.reading != null && !kernel.reading.contains(this))
        throw refusal(
            "ui.undeclared_dependency", "Declare this presentation input before reading it");
      return cached;
    }

    boolean publish(UiSnapshot<T> snapshot) {
      requireCurrent();
      if (cached instanceof UiObservation.Available<T> prior
          && kernel.pure(Set.of(), () -> prior.data().sameData(snapshot))) return false;
      long nextBytes = snapshot.retainedBytes();
      if (nextBytes < 0)
        throw refusal("ui.snapshot_invalid", "A cached observation has invalid payload accounting");
      if (nextBytes > kernel.limits.maximumStateBytes() - (kernel.stateBytes - bytes))
        throw budget("Cached presentation data exceeds its declared byte allowance");
      kernel.stateBytes += nextBytes - bytes;
      bytes = nextBytes;
      cached = new UiObservation.Available<>(snapshot, ++kernel.revision);
      return true;
    }

    void unavailable(PolicyRefusal failure, Throwable cause) {
      kernel.stateBytes -= bytes;
      bytes = 0;
      cached =
          new UiObservation.Unavailable<>(
              failure.report(), Optional.ofNullable(cause), ++kernel.revision);
    }

    void retire() {
      unavailable(refusal("ui.scope_closed", "This presentation scope has ended"), null);
      kernel.invalidate(this);
      dependents.clear();
    }
  }

  private final class Cell<T> extends Node<T> implements UiLocal<T> {
    Cell(ViewStateKey<T> key, T initial) {
      super(key);
      publish(kernel.pure(Set.of(), () -> key.capture(initial)));
    }

    @Override
    public void replace(T value) {
      requireWrite();
      var captured = kernel.pure(Set.of(), () -> key.capture(value));
      if (!publish(captured)) return;
      kernel.invalidate(this);
      kernel.flush();
    }
  }

  private final class Source<T> extends Node<T> {
    final UiRead<T> source;
    long seen = -1;

    Source(UiCaptureKey<T> key, UiRead<T> source) {
      super(key);
      this.source = source;
    }

    void refresh() {
      if (!isOpen()) return;
      try {
        var next = kernel.pure(Set.of(source), source::snapshot);
        requireCurrent();
        if (next.revision() == seen) return;
        if (next instanceof UiObservation.Unavailable<T> missing) {
          unavailable(new PolicyRefusal(missing.report()), missing.cause().orElse(null));
        } else {
          var captured =
              kernel.pure(
                  Set.of(source),
                  () ->
                      next instanceof UiObservation.Available<T> available
                              && key.owns(available.data())
                          ? available.data()
                          : key.capture(next.value()));
          if (!publish(captured))
            cached = new UiObservation.Available<>(captured, ++kernel.revision);
        }
        seen = next.revision();
        kernel.invalidate(this);
      } catch (RuntimeException failed) {
        if (!open || !kernel.owner.isOpen()) return;
        var refusal =
            failed instanceof PolicyRefusal policy
                ? policy
                : refusal(
                    "ui.observation_unavailable",
                    "This cached observation is unavailable; inspect its cause");
        if (!(cached instanceof UiObservation.Unavailable<T> prior)
            || !prior.report().equals(refusal.report())) {
          unavailable(refusal, failed);
          kernel.invalidate(this);
        }
        seen = -1;
      }
    }

    @Override
    void retire() {
      kernel.sources.remove(this);
      super.retire();
    }
  }

  private final class Projection<T> extends Node<T> {
    final List<Node<?>> inputs;
    final Function<UiInputs, T> compute;
    boolean dirty = true;
    boolean evaluating;

    Projection(UiCaptureKey<T> key, List<Node<?>> inputs, Function<UiInputs, T> compute) {
      super(key);
      this.inputs = inputs;
      this.compute = compute;
    }

    void evaluate() {
      if (!dirty) return;
      if (!isOpen()) {
        dirty = false;
        kernel.dirty.remove(this);
        return;
      }
      if (evaluating)
        throw refusal("ui.dependency_cycle", "Presentation dependencies contain a cycle");
      evaluating = true;
      try {
        for (var input : inputs) {
          if (input instanceof UiScope.Projection<?> projection) projection.evaluate();
          if (input.cached instanceof UiObservation.Unavailable<?> missing) {
            unavailable(new PolicyRefusal(missing.report()), missing.cause().orElse(null));
            return;
          }
        }
        Map<UiRead<?>, UiObservation<?>> values = new IdentityHashMap<>();
        for (var input : inputs) values.put(input, input.cached);
        var captured =
            kernel.pure(Set.copyOf(inputs), () -> key.capture(compute.apply(new UiInputs(values))));
        // Trusted computation/codec callbacks may have ended the actual host lifetime.
        publish(captured);
      } catch (PolicyRefusal failed) {
        if (open && kernel.owner.isOpen()) unavailable(failed, failed);
      } catch (RuntimeException failed) {
        if (open && kernel.owner.isOpen())
          unavailable(
              refusal(
                  "ui.projection_failed", "The presentation projection failed; inspect its cause"),
              failed);
      } finally {
        evaluating = false;
        dirty = false;
        kernel.dirty.remove(this);
      }
    }

    @Override
    void retire() {
      for (var input : inputs) input.dependents.remove(this);
      kernel.dependencies -= inputs.size();
      kernel.projections--;
      kernel.dirty.remove(this);
      super.retire();
    }
  }

  private static final class Kernel {
    final OwnerContext owner;
    final UiLimits limits;
    final Set<UiScope.Projection<?>> dirty = new LinkedHashSet<>();
    final Set<UiScope.Source<?>> sources = new LinkedHashSet<>();
    Set<? extends UiRead<?>> reading;
    int scopes = 1, states, projections, dependencies, closeHooks, batch;
    long revision, stateBytes;
    boolean flushing;
    OwnerContext.CloseHook ownerHook;

    Kernel(OwnerContext owner, UiLimits limits) {
      this.owner = owner;
      this.limits = limits;
    }

    void requireMutable() {
      if (reading != null)
        throw refusal(
            "ui.write_in_pure_phase", "Presentation computations cannot change state or scope");
    }

    <T> T pure(Set<? extends UiRead<?>> inputs, Supplier<T> work) {
      var prior = reading;
      reading = inputs;
      try {
        return work.get();
      } finally {
        reading = prior;
      }
    }

    void invalidate(UiScope.Node<?> changed) {
      var pending = new ArrayDeque<UiScope.Node<?>>();
      var visited =
          java.util.Collections.newSetFromMap(new IdentityHashMap<UiScope.Node<?>, Boolean>());
      pending.add(changed);
      while (!pending.isEmpty()) {
        for (var dependent : pending.removeFirst().dependents)
          if (visited.add(dependent)) {
            dependent.dirty = true;
            dirty.add(dependent);
            pending.addLast(dependent);
          }
      }
    }

    void flush() {
      if (batch != 0 || flushing) return;
      flushing = true;
      try {
        while (!dirty.isEmpty()) dirty.iterator().next().evaluate();
      } finally {
        flushing = false;
      }
    }
  }

  private final Kernel kernel;
  private final UiScope parent;
  private final Object key;
  private final int depth;
  private final Map<Object, UiScope> children = new LinkedHashMap<>();
  private final Map<DocumentId, Node<?>> nodes = new LinkedHashMap<>();
  private final Set<CloseHook> hooks = new LinkedHashSet<>();
  private volatile boolean open = true;
  private volatile Throwable releaseFailure;
  private volatile Usage retiredUsage;

  private UiScope(Kernel kernel, UiScope parent, Object key) {
    this.kernel = kernel;
    this.parent = parent;
    this.key = key;
    depth = parent == null ? 0 : parent.depth + 1;
  }

  /** Borrows one actual host lifetime and installs one hook; closing the root never closes it. */
  public static UiScope borrow(OwnerContext host, UiLimits limits) {
    Objects.requireNonNull(host).requireOwner();
    var root = new UiScope(new Kernel(host, Objects.requireNonNull(limits)), null, "root");
    root.kernel.ownerHook = host.onClose(root::retireFromHost);
    return root;
  }

  public boolean isOpen() {
    return open && kernel.owner.isOpen();
  }

  /** Cached update identity for a host's update loop, not a render-time projection callback. */
  public long revision() {
    requireCurrent();
    return kernel.revision;
  }

  public UiLimits limits() {
    return kernel.limits;
  }

  /**
   * Projects an admitted native description from explicit cached reads. It adds no retained graph
   * edges and cannot mutate this kernel. Native rendering consumes the returned cached description.
   */
  public <T> T project(List<? extends UiRead<?>> inputs, Supplier<T> projection) {
    requireWrite();
    Objects.requireNonNull(projection);
    if (inputs.size() > kernel.limits.maximumDependencies())
      throw budget("Presentation projection input admission is full");
    Set<UiRead<?>> admitted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (var input : inputs) {
      if (!(input instanceof UiScope.Node<?> node)
          || node.scope().kernel != kernel
          || !node.scope().isOpen()) throw mismatch();
      admitted.add(input);
    }
    T result = kernel.pure(admitted, projection);
    requireCurrent();
    return result;
  }

  public void requireCurrent() {
    kernel.owner.requireOwner();
    if (!open) throw refusal("ui.scope_closed", "This presentation scope has ended");
  }

  private void requireWrite() {
    requireCurrent();
    kernel.requireMutable();
  }

  /** A stable live child key retains its cells. Closing/removing it ends that lifetime. */
  public UiScope child(String childKey) {
    requireWrite();
    if (childKey == null || childKey.isBlank() || childKey.length() > 256)
      throw new IllegalArgumentException("A bounded stable component key is required");
    var existing = children.get(childKey);
    if (existing != null) return existing;
    return createChild(childKey);
  }

  /** An independently owned child; internal component scopes cannot collide with authored keys. */
  public UiScope child() {
    requireWrite();
    return createChild(new Object());
  }

  private UiScope createChild(Object childKey) {
    if (depth >= kernel.limits.maximumDepth() || kernel.scopes >= kernel.limits.maximumScopes())
      throw budget("Presentation scope admission is full");
    var child = new UiScope(kernel, this, childKey);
    children.put(childKey, child);
    kernel.scopes++;
    return child;
  }

  public <T> UiLocal<T> local(ViewStateKey<T> state, T initial) {
    requireWrite();
    Objects.requireNonNull(state);
    var existing = nodes.get(state.id());
    if (existing != null) {
      if (existing.key != state || !(existing instanceof UiScope.Cell<?>)) throw mismatch();
      @SuppressWarnings("unchecked")
      var typed = (UiLocal<T>) existing;
      return typed;
    }
    if (kernel.states >= kernel.limits.maximumStates())
      throw budget("Presentation state admission is full");
    var cell = new Cell<>(state, initial);
    nodes.put(state.id(), cell);
    kernel.states++;
    return cell;
  }

  /**
   * Borrows an existing cached observation, not an IO or permission callback. The host explicitly
   * refreshes it outside paint. No change command, accepted draft, or source authority is created.
   */
  public <T> UiRead<T> observe(ViewStateKey<T> state, UiRead<T> source) {
    return observeCaptured(state, source);
  }

  /** Nonportable cached owner/input observations use their actual owned-payload contract. */
  public <T> UiRead<T> observe(UiReadKey<T> state, UiRead<T> source) {
    return observeCaptured(state, source);
  }

  private <T> UiRead<T> observeCaptured(UiCaptureKey<T> state, UiRead<T> source) {
    requireWrite();
    Objects.requireNonNull(state);
    Objects.requireNonNull(source);
    if (source instanceof UiScope.Node<?> node && node.scope().kernel == kernel)
      throw refusal("ui.binding_mismatch", "Use a declared derivation for this root's own reads");
    var existing = nodes.get(state.id());
    if (existing != null) {
      if (existing.key != state
          || !(existing instanceof UiScope.Source<?> observed)
          || observed.source != source) throw mismatch();
      @SuppressWarnings("unchecked")
      var typed = (UiRead<T>) existing;
      return typed;
    }
    if (kernel.states >= kernel.limits.maximumStates())
      throw budget("Presentation observation admission is full");
    var observed = new Source<>(state, source);
    nodes.put(state.id(), observed);
    kernel.states++;
    kernel.sources.add(observed);
    try {
      observed.refresh();
    } catch (Error failed) {
      if (nodes.remove(state.id(), observed)) {
        kernel.states--;
        observed.retire();
      }
      throw failed;
    }
    kernel.flush();
    return observed;
  }

  /** Polls only explicitly borrowed cached observations on the actual host's update turn. */
  public void refreshObservations() {
    requireWrite();
    kernel.batch++;
    try {
      for (var observed : List.copyOf(kernel.sources)) observed.refresh();
    } finally {
      kernel.batch--;
      if (isOpen()) kernel.flush();
    }
  }

  /** Poll one admitted cached source in this kernel, outside paint or pure projection. */
  public void refreshObservation(UiRead<?> observed) {
    requireWrite();
    if (!(observed instanceof UiScope.Source<?> source)
        || source.scope().kernel != kernel
        || !source.scope().isOpen()) throw mismatch();
    kernel.batch++;
    try {
      source.refresh();
    } finally {
      kernel.batch--;
      if (isOpen()) kernel.flush();
    }
  }

  /** Dependencies must be live reads from this root, declared once with a stable computation. */
  public <T> UiRead<T> derive(
      ViewStateKey<T> state,
      List<? extends UiRead<?>> dependencies,
      Function<UiInputs, T> compute) {
    return deriveCaptured(state, dependencies, compute);
  }

  public <T> UiRead<T> derive(
      UiReadKey<T> state, List<? extends UiRead<?>> dependencies, Function<UiInputs, T> compute) {
    return deriveCaptured(state, dependencies, compute);
  }

  private <T> UiRead<T> deriveCaptured(
      UiCaptureKey<T> state,
      List<? extends UiRead<?>> dependencies,
      Function<UiInputs, T> compute) {
    requireWrite();
    Objects.requireNonNull(state);
    Objects.requireNonNull(compute);
    if (dependencies.size() > kernel.limits.maximumDependencies())
      throw budget("Presentation dependency admission is full");
    var inputs = new ArrayList<Node<?>>();
    Set<UiRead<?>> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (var dependency : List.copyOf(dependencies)) {
      if (!(dependency instanceof UiScope.Node<?> input)
          || input.scope().kernel != kernel
          || !input.scope().isOpen()
          || !unique.add(dependency))
        throw refusal(
            "ui.binding_mismatch", "Declare distinct live inputs from this presentation root");
      inputs.add(input);
    }
    var existing = nodes.get(state.id());
    if (existing != null) {
      if (existing.key != state
          || !(existing instanceof UiScope.Projection<?> projection)
          || !projection.inputs.equals(inputs)
          || projection.compute != compute) throw mismatch();
      @SuppressWarnings("unchecked")
      var typed = (UiRead<T>) existing;
      return typed;
    }
    if (kernel.projections >= kernel.limits.maximumProjections()
        || inputs.size() > kernel.limits.maximumDependencies() - kernel.dependencies)
      throw budget("Presentation dependency admission is full");
    var projection = new Projection<>(state, List.copyOf(inputs), compute);
    nodes.put(state.id(), projection);
    kernel.projections++;
    kernel.dependencies += inputs.size();
    for (var input : inputs) input.dependents.add(projection);
    try {
      projection.evaluate();
    } catch (Error failed) {
      if (nodes.remove(state.id(), projection)) projection.retire();
      throw failed;
    }
    return projection;
  }

  /**
   * Batches projection invalidation only; edits already performed are not rolled back on failure.
   */
  public void update(Runnable edits) {
    requireWrite();
    kernel.batch++;
    try {
      Objects.requireNonNull(edits).run();
    } finally {
      kernel.batch--;
      if (isOpen()) kernel.flush();
    }
  }

  /**
   * Register before allocating owned resources. Neither this hook nor the scope grants authority.
   */
  public CloseHook onRetire(Runnable cleanup) {
    requireWrite();
    Objects.requireNonNull(cleanup);
    if (kernel.closeHooks >= kernel.limits.maximumCloseHooks())
      throw budget("Presentation cleanup admission is full");
    var hook = new CloseHook(cleanup);
    hooks.add(hook);
    kernel.closeHooks++;
    return hook;
  }

  public Usage usage() {
    var retired = retiredUsage;
    if (retired != null) return retired;
    kernel.owner.checkOwnerThread();
    return new Usage(
        kernel.scopes,
        kernel.states,
        kernel.projections,
        kernel.dependencies,
        kernel.closeHooks,
        kernel.stateBytes);
  }

  /** Retained even after the borrowed host executor ends. This does not refund native resources. */
  public Optional<Throwable> releaseFailure() {
    return Optional.ofNullable(releaseFailure);
  }

  @Override
  public void close() {
    if (!open) return;
    requireWrite();
    retireFromHost();
  }

  private void retireFromHost() {
    if (!open) return;
    kernel.owner.checkOwnerThread();
    revokeTree();
    if (parent == null) kernel.ownerHook.close();
    Throwable failed = disposeTree();
    if (parent != null) parent.children.remove(key, this);
    if (kernel.owner.isOpen()) kernel.flush();
    if (failed instanceof RuntimeException failure) throw failure;
    if (failed instanceof Error failure) throw failure;
  }

  private void revokeTree() {
    open = false;
    for (var child : children.values()) child.revokeTree();
  }

  private Throwable disposeTree() {
    Throwable failed = null;
    var childList = new ArrayList<>(children.values());
    for (int index = childList.size() - 1; index >= 0; index--)
      failed = combine(failed, childList.get(index).disposeTree());
    children.clear();
    var selectedHooks = new ArrayList<>(hooks);
    for (int index = selectedHooks.size() - 1; index >= 0; index--) {
      var work = selectedHooks.get(index).remove();
      if (work == null) continue;
      try {
        work.run();
      } catch (RuntimeException | Error failure) {
        failed = combine(failed, failure);
      }
    }
    for (var node : nodes.values()) {
      if (!(node instanceof UiScope.Projection<?>)) kernel.states--;
      node.retire();
    }
    nodes.clear();
    kernel.scopes--;
    releaseFailure = failed;
    retiredUsage =
        new Usage(
            kernel.scopes,
            kernel.states,
            kernel.projections,
            kernel.dependencies,
            kernel.closeHooks,
            kernel.stateBytes);
    return failed;
  }

  private static Throwable combine(Throwable first, Throwable later) {
    if (later == null) return first;
    if (first == null) return later;
    if (first != later) first.addSuppressed(later);
    return first;
  }

  private static PolicyRefusal mismatch() {
    return refusal(
        "ui.binding_mismatch", "A retained presentation key has a different data contract");
  }

  private static PolicyRefusal budget(String message) {
    return refusal("ui.budget_exceeded", message);
  }

  private static PolicyRefusal refusal(String code, String message) {
    return new PolicyRefusal(code, message);
  }
}
