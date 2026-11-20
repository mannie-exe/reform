package design.inherent.reform.edit;

import design.inherent.reform.owner.SourceInfo;
import java.util.Objects;

/**
 * Provenance for an owner-issued full basis behind a projected draft. The payload stays with the
 * owner.
 */
public final class DraftBasis {
  private final String revision;
  private final SourceInfo source;

  public DraftBasis(String revision, SourceInfo source) {
    this.revision = Objects.requireNonNull(revision);
    this.source = Objects.requireNonNull(source);
    if (revision.isBlank() || revision.length() > 256)
      throw new IllegalArgumentException("A draft basis needs a bounded source revision");
  }

  public String revision() {
    return revision;
  }

  public SourceInfo source() {
    return source;
  }
}
