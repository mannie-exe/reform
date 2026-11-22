package design.inherent.reform.edit;

import design.inherent.reform.definition.FieldPath;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Actual editor lifetime, optional optimistic draft version and structural location. */
public record DraftTarget(UUID session, long generation, Optional<Long> version, FieldPath path) {
  public static design.inherent.reform.value.ValueType<DraftTarget> type() {
    return DraftEditTypes.TARGET;
  }

  public DraftTarget {
    Objects.requireNonNull(session);
    Objects.requireNonNull(version);
    Objects.requireNonNull(path);
    if (generation < 0 || version.filter(value -> value < 0).isPresent())
      throw new IllegalArgumentException("Negative editor generation/version");
  }

  /** Explicit ordered typing streams may follow edits in this lifetime; reloads still refuse. */
  public DraftTarget typing() {
    return new DraftTarget(session, generation, Optional.empty(), path);
  }
}
