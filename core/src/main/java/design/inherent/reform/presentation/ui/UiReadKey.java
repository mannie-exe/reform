package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * A nonportable cached observation contract. The admitted producer supplies owned snapshots and
 * measured payload accounting; there is no codec, local setter, or bookmark restoration authority.
 */
public final class UiReadKey<T> implements UiCaptureKey<T> {
  private final DocumentId id;
  private final SnapshotPolicy<T> policy;
  private final SnapshotContext.Limits limits;
  private final long maximumBytes;
  private final ToLongFunction<T> measurement;

  public UiReadKey(
      DocumentId id,
      SnapshotPolicy<T> policy,
      SnapshotContext.Limits limits,
      long maximumBytes,
      ToLongFunction<T> measurement) {
    this.id = Objects.requireNonNull(id);
    this.policy = Objects.requireNonNull(policy);
    this.limits = Objects.requireNonNull(limits);
    this.measurement = Objects.requireNonNull(measurement);
    if (maximumBytes < 1)
      throw new IllegalArgumentException("A finite read payload allowance is required");
    this.maximumBytes = maximumBytes;
  }

  @Override
  public DocumentId id() {
    return id;
  }

  @Override
  public boolean owns(UiSnapshot<?> snapshot) {
    return snapshot instanceof UiReadKey<?>.Captured captured && captured.owner() == this;
  }

  private long measure(T value) {
    long bytes = measurement.applyAsLong(value);
    if (bytes < 0 || bytes > maximumBytes)
      throw new IllegalArgumentException("The observation exceeds its declared payload allowance");
    return bytes;
  }

  private T copy(T value) {
    var context = new SnapshotContext(limits);
    return context.copy(value, () -> policy.freeze(value, context));
  }

  @Override
  public UiSnapshot<T> capture(T value) {
    measure(Objects.requireNonNull(value));
    var captured = copy(value);
    return new Captured(captured, measure(captured));
  }

  private final class Captured implements UiSnapshot<T> {
    private final T frozen;
    private final long bytes;

    Captured(T frozen, long bytes) {
      this.frozen = frozen;
      this.bytes = bytes;
    }

    @Override
    public T value() {
      return copy(frozen);
    }

    @Override
    public long retainedBytes() {
      return bytes;
    }

    @Override
    public boolean sameData(UiSnapshot<?> other) {
      if (!(other instanceof UiReadKey<?>.Captured prior) || prior.owner() != UiReadKey.this)
        return false;
      // Exact capture-key identity proves that both values were captured through this same T
      // policy.
      @SuppressWarnings("unchecked")
      var typed = (Captured) prior;
      return policy.equivalent(frozen, typed.frozen);
    }

    private UiReadKey<T> owner() {
      return UiReadKey.this;
    }
  }
}
