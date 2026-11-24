package design.inherent.reform.presentation;

import design.inherent.reform.definition.DocumentId;
import java.util.Objects;

/** A view-only recipe identity, never a command, unlock, transfer, or serialized viewer object. */
public record RecipeLinkRequest(Kind kind, DocumentId identity) {
  public enum Kind {
    PRODUCING,
    USES,
    RECIPE,
    CATEGORY,
    WORKSTATION
  }

  public RecipeLinkRequest {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(identity);
  }
}
