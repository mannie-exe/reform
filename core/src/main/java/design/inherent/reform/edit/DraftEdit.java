package design.inherent.reform.edit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Data-only structural edits; no callback, arbitrary JSON patch or implicit owner effect. */
public record DraftEdit(DraftTarget target, Action action) {
  public DraftEdit {
    Objects.requireNonNull(target);
    Objects.requireNonNull(action);
    if (target.version().isEmpty() && !(action instanceof SetRaw || action instanceof SetUnitRaw))
      throw new IllegalArgumentException("Structural edits require an exact draft version");
  }

  public sealed interface Action
      permits SetRaw,
          SetUnitRaw,
          SetRecordScalars,
          ChangeScalarRows,
          InsertRow,
          DuplicateRow,
          RemoveRow,
          MoveRow,
          Presence,
          Override,
          SelectDefaultedScalar,
          SelectVariant,
          DiscardBranch,
          RestoreOverride,
          DiscardOverride,
          RemoveUnknown,
          Repair {}

  public record SetRaw(String text) implements Action {
    public SetRaw {
      Objects.requireNonNull(text);
    }
  }

  /** Only display input and a declared unit ID; mapping is performed by the actual editor. */
  public record SetUnitRaw(String unit, String text) implements Action {
    public SetUnitRaw {
      Objects.requireNonNull(unit);
      Objects.requireNonNull(text);
    }
  }

  /** Explicit immediate scalar fields in one declared record, with one history boundary. */
  public record SetRecordScalars(Map<String, String> values) implements Action {
    public SetRecordScalars {
      Objects.requireNonNull(values);
      if (values.size() > 32)
        throw new IllegalArgumentException("Too many scalar fields in one action");
      values = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
      if (values.size() > 32)
        throw new IllegalArgumentException("Too many scalar fields in one action");
      long characters = 0;
      for (var entry : values.entrySet()) {
        Objects.requireNonNull(entry.getKey());
        Objects.requireNonNull(entry.getValue());
        if (entry.getKey().isEmpty()
            || entry.getKey().length() > 4096
            || entry.getValue().length() > 32768
            || (characters += (long) entry.getKey().length() + entry.getValue().length()) > 131072)
          throw new IllegalArgumentException("Scalar record input exceeds its admitted budget");
      }
    }
  }

  /** No before anchor appends a blank, declared-shape row. */
  public record InsertRow(Optional<UUID> before) implements Action {
    public InsertRow {
      Objects.requireNonNull(before);
    }
  }

  /** Explicit additions/removals, one history step; existing rows keep identity and order. */
  public record ChangeScalarRows(List<String> append, Set<UUID> remove) implements Action {
    public ChangeScalarRows {
      Objects.requireNonNull(append);
      Objects.requireNonNull(remove);
      if ((long) append.size() + remove.size() > 256)
        throw new IllegalArgumentException("Too many membership changes in one action");
      // Admit the retained snapshot too; callers may have supplied mutable collections.
      append = List.copyOf(append);
      remove = Set.copyOf(remove);
      if ((long) append.size() + remove.size() > 256)
        throw new IllegalArgumentException("Too many membership changes in one action");
      long characters = 0;
      for (String text : append) {
        Objects.requireNonNull(text);
        if (text.length() > 32768 || (characters += text.length()) > 131072)
          throw new IllegalArgumentException("Membership input exceeds its text budget");
      }
    }
  }

  public record DuplicateRow(UUID row, Optional<UUID> before) implements Action {
    public DuplicateRow {
      Objects.requireNonNull(row);
      Objects.requireNonNull(before);
    }
  }

  public record RemoveRow(UUID row) implements Action {
    public RemoveRow {
      Objects.requireNonNull(row);
    }
  }

  public record MoveRow(UUID row, Optional<UUID> before) implements Action {
    public MoveRow {
      Objects.requireNonNull(row);
      Objects.requireNonNull(before);
    }
  }

  public record Presence(boolean present) implements Action {}

  public record Override(boolean overridden) implements Action {}

  /** Select an omitted default or an explicit scalar value with one exact history boundary. */
  public record SelectDefaultedScalar(Optional<String> value) implements Action {
    public SelectDefaultedScalar {
      Objects.requireNonNull(value);
      if (value.filter(text -> text.length() > 32768).isPresent())
        throw new IllegalArgumentException("Scalar input exceeds its admitted text budget");
    }
  }

  public record SelectVariant(String id) implements Action {
    public SelectVariant {
      Objects.requireNonNull(id);
    }
  }

  public record DiscardBranch(String id) implements Action {
    public DiscardBranch {
      Objects.requireNonNull(id);
    }
  }

  public record RestoreOverride(UUID row) implements Action {
    public RestoreOverride {
      Objects.requireNonNull(row);
    }
  }

  public record DiscardOverride(UUID row) implements Action {
    public DiscardOverride {
      Objects.requireNonNull(row);
    }
  }

  public record RemoveUnknown(String field) implements Action {
    public RemoveUnknown {
      Objects.requireNonNull(field);
    }
  }

  /** Explicit replacement of a malformed subtree with its editable blank shape; undo retains it. */
  public record Repair() implements Action {}
}
