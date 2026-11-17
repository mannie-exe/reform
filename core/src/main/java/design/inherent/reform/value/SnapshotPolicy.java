package design.inherent.reform.value;

import java.util.Objects;

/** Custom types explicitly own deep-copy and semantic-equality behavior. */
@FunctionalInterface
public interface SnapshotPolicy<T> {
  T freeze(T value, SnapshotContext context);

  default boolean equivalent(T first, T second) {
    return Objects.equals(first, second);
  }

  /** Use only for values whose complete reachable state is immutable. */
  static <T> SnapshotPolicy<T> immutable() {
    return (value, context) -> value;
  }
}
