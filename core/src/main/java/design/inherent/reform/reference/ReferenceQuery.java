package design.inherent.reform.reference;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A cursor binds the actual index and exact filter. Changing the filter starts a new query. */
public record ReferenceQuery(String filter, int limit, Optional<Cursor> cursor, Match match) {
  public enum Match {
    TEXT,
    KEY_PREFIX
  }

  public record Cursor(UUID snapshot, String filter, int offset, Match match) {
    public Cursor {
      Objects.requireNonNull(snapshot);
      Objects.requireNonNull(filter);
      Objects.requireNonNull(match);
      if (offset < 0) throw new IllegalArgumentException("Negative reference cursor");
    }

    public Cursor(UUID snapshot, String filter, int offset) {
      this(snapshot, filter, offset, Match.TEXT);
    }
  }

  public ReferenceQuery {
    Objects.requireNonNull(filter);
    Objects.requireNonNull(cursor);
    Objects.requireNonNull(match);
    if (limit < 1) throw new IllegalArgumentException("Reference page size must be positive");
  }

  public ReferenceQuery(String filter, int limit) {
    this(filter, limit, Optional.empty(), Match.TEXT);
  }

  public ReferenceQuery(String filter, int limit, Optional<Cursor> cursor) {
    this(filter, limit, cursor, Match.TEXT);
  }

  public ReferenceQuery(String filter, int limit, Match match) {
    this(filter, limit, Optional.empty(), match);
  }
}
