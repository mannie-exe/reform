package design.inherent.reform.value;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Per-copy accounting: no ambient thread state, retained object cache or shallow-copy promise. */
public final class SnapshotContext {
  public record Limits(int maximumDepth, int maximumNodes) {
    public static final Limits CONFIGURATION = new Limits(128, 65_536);

    public Limits {
      if (maximumDepth < 1 || maximumDepth > 256 || maximumNodes < 1) {
        throw new IllegalArgumentException("Invalid snapshot limits");
      }
    }
  }

  private final Limits limits;
  private final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
  private int depth;
  private int nodes;

  public SnapshotContext(Limits limits) {
    this.limits = Objects.requireNonNull(limits);
  }

  public <T> T copy(Object source, Supplier<T> operation) {
    Objects.requireNonNull(source);
    if (depth >= limits.maximumDepth() || ++nodes > limits.maximumNodes()) {
      throw new IllegalArgumentException("Snapshot exceeds its declared depth/node limit");
    }
    if (active.put(source, Boolean.TRUE) != null) {
      throw new IllegalArgumentException("Cyclic value instance cannot be snapshotted");
    }
    depth++;
    try {
      return Objects.requireNonNull(operation.get(), "Snapshot policy returned null");
    } finally {
      depth--;
      active.remove(source);
    }
  }
}
