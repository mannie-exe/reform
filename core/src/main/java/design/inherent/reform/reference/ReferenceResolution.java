package design.inherent.reform.reference;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A resolved empty domain collection is still RESOLVED; absence/context failure is separate. */
public record ReferenceResolution<V>(
    ReferenceStamp stamp,
    Status status,
    Optional<V> value,
    Map<String, String> preview,
    String reason) {
  public enum Status {
    RESOLVED,
    UNKNOWN_REFERENCE,
    MALFORMED,
    MISSING_DEPENDENCY,
    UNAVAILABLE_CONTEXT
  }

  public ReferenceResolution {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(status);
    Objects.requireNonNull(value);
    Objects.requireNonNull(reason);
    preview = Map.copyOf(preview);
    if ((status == Status.RESOLVED) != value.isPresent())
      throw new IllegalArgumentException("Only resolved references carry a value");
    if (status != Status.RESOLVED && !preview.isEmpty())
      throw new IllegalArgumentException(
          "Unresolved references carry a reason, not a value preview");
  }

  public static <V> ReferenceResolution<V> resolved(
      ReferenceStamp stamp, V value, Map<String, String> preview) {
    return new ReferenceResolution<>(stamp, Status.RESOLVED, Optional.of(value), preview, "");
  }

  public static <V> ReferenceResolution<V> unavailable(
      ReferenceStamp stamp, Status status, String reason) {
    return new ReferenceResolution<>(stamp, status, Optional.empty(), Map.of(), reason);
  }
}
