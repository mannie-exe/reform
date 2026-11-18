package design.inherent.reform.reference;

import design.inherent.reform.definition.DocumentId;
import java.util.Objects;
import java.util.UUID;

/**
 * Context and actual snapshot identity; the descriptive revision alone is not a freshness token.
 */
public record ReferenceStamp(UUID context, DocumentId source, UUID snapshot, String revision) {
  public ReferenceStamp {
    Objects.requireNonNull(context);
    Objects.requireNonNull(source);
    Objects.requireNonNull(snapshot);
    if (Objects.requireNonNull(revision).isBlank() || revision.length() > 256)
      throw new IllegalArgumentException("Reference source revision must be nonblank and bounded");
  }
}
