package design.inherent.reform.reference;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A cursor binds the actual index and exact filter. Changing the filter starts a new query. */
public record ReferenceQuery(String filter, int limit, Optional<Cursor> cursor) {
  public record Cursor(UUID snapshot, String filter, int offset) {
    public Cursor {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(filter);
      if (offset < 0) throw new IllegalArgumentException("Negative reference cursor");
    }
  }

  public ReferenceQuery {
    Objects.requireNonNull(filter);
    Objects.requireNonNull(cursor);
    if (limit < 1) throw new IllegalArgumentException("Reference page size must be positive");
  }

  public ReferenceQuery(String filter, int limit) {
    this(filter, limit, Optional.empty());
  }
}
