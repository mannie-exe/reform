package design.inherent.reform.edit;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Data-only structural edits; no callback, arbitrary JSON patch or implicit owner effect. */
public record DraftEdit(DraftTarget target, Action action) {
  public DraftEdit {
    Objects.requireNonNull(target);
    Objects.requireNonNull(action);
    if (target.version().isEmpty() && !(action instanceof SetRaw))
      throw new IllegalArgumentException("Structural edits require an exact draft version");
  }

  public sealed interface Action
      permits SetRaw,
          InsertRow,
          DuplicateRow,
          RemoveRow,
          MoveRow,
          Presence,
          Override,
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

  /** No before anchor appends a blank, declared-shape row. */
  public record InsertRow(Optional<UUID> before) implements Action {
    public InsertRow {
      Objects.requireNonNull(before);
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
