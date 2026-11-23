package design.inherent.reform.presentation;

import design.inherent.reform.definition.DocumentId;
import java.util.Objects;

/** Presentation identity, distinct from a document's persistence identity. */
public record DestinationId(DocumentId value) {
  public DestinationId {
    Objects.requireNonNull(value);
  }

  public static DestinationId parse(String value) {
    return new DestinationId(DocumentId.parse(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
