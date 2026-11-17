package design.inherent.reform.owner;

import java.util.Objects;
import java.util.Optional;

/** Read-only declared provenance. A displayed path is never a file access capability. */
public record SourceInfo(String kind, String description, Optional<String> location) {
  public static final SourceInfo UNKNOWN =
      new SourceInfo("owner", "Owner-supplied source", Optional.empty());

  public SourceInfo {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(description);
    Objects.requireNonNull(location);
    if (kind.length() > 80
        || description.length() > 512
        || location.map(String::length).orElse(0) > 4096)
      throw new IllegalArgumentException("Source details exceed their declared limits");
  }
}
