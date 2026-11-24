package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.presentation.FormTarget;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit presentation identities in one workflow; none discovers a source or grants authority.
 */
public sealed interface ClientDestination {
  record Home() implements ClientDestination {}

  record Category(DestinationId id) implements ClientDestination {
    public Category {
      Objects.requireNonNull(id);
    }
  }

  record Document(DocumentId route) implements ClientDestination {
    public Document {
      Objects.requireNonNull(route);
    }
  }

  /** The retained source/value inspection for one admitted owner, not a save or validation. */
  record Inspection(DocumentId route) implements ClientDestination {
    public Inspection {
      Objects.requireNonNull(route);
    }
  }

  /** Inspect one exact retained document operation without starting or repeating its effect. */
  record DocumentOperation(DocumentId route, UUID handle) implements ClientDestination {
    public DocumentOperation {
      Objects.requireNonNull(route);
      Objects.requireNonNull(handle);
    }
  }

  /** The owner's existing conflict review; opening it neither resolves nor saves. */
  record Conflict(DocumentId route) implements ClientDestination {
    public Conflict {
      Objects.requireNonNull(route);
    }
  }

  /** The admitted source/format import review; opening never reads a file, stages, or saves. */
  record Import(DocumentId route) implements ClientDestination {
    public Import {
      Objects.requireNonNull(route);
    }
  }

  /** Return to the current admitted complete review without capturing or saving again. */
  record BatchReview(DocumentId scope) implements ClientDestination {
    public BatchReview {
      Objects.requireNonNull(scope);
    }
  }

  /** Inspect one exact retained batch submission; opening never repeats it. */
  record BatchResult(DocumentId scope, UUID handle) implements ClientDestination {
    public BatchResult {
      Objects.requireNonNull(scope);
      Objects.requireNonNull(handle);
    }
  }

  record Placement(DestinationId id) implements ClientDestination {
    public Placement {
      Objects.requireNonNull(id);
    }
  }

  /** A registered shared task body, distinct from an ordinary category. */
  record View(DestinationId id) implements ClientDestination {
    public View {
      Objects.requireNonNull(id);
    }
  }

  /** A route and optional contextual alias; collection paths retain their actual stable row IDs. */
  record Field(DocumentId route, FieldPath path, Optional<DestinationId> placement)
      implements ClientDestination {
    public Field(DocumentId route, FieldPath path) {
      this(route, path, Optional.empty());
    }

    public Field {
      Objects.requireNonNull(route);
      Objects.requireNonNull(path);
      Objects.requireNonNull(placement);
    }
  }

  record Section(DocumentId route, FormTarget.Section section, Optional<DestinationId> placement)
      implements ClientDestination {
    public Section(DocumentId route, FormTarget.Section section) {
      this(route, section, Optional.empty());
    }

    public Section {
      Objects.requireNonNull(route);
      Objects.requireNonNull(section);
      Objects.requireNonNull(placement);
    }
  }

  /** Reveal the declared effect; opening this destination never invokes it. */
  record Operation(DocumentId id) implements ClientDestination {
    public Operation {
      Objects.requireNonNull(id);
    }
  }

  /** Inspect this exact retained handle, never a replacement or a new invocation. */
  record OperationResult(DocumentId id, UUID handle) implements ClientDestination {
    public OperationResult {
      Objects.requireNonNull(id);
      Objects.requireNonNull(handle);
    }
  }
}
