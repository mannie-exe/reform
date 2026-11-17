package design.inherent.reform.definition;

import java.util.Objects;

/** Logical identity, never a filesystem path, display label or owner lifetime. */
public record DocumentId(String namespace, String path) {
  public DocumentId {
    Objects.requireNonNull(namespace);
    Objects.requireNonNull(path);
    if (namespace.length() + path.length() > 255
        || !namespace.matches("[a-z0-9_.-]+")
        || !path.matches("[a-z0-9_./-]+")) {
      throw new IllegalArgumentException("Expected a namespaced document identity");
    }
  }

  public static DocumentId parse(String text) {
    int separator = Objects.requireNonNull(text).indexOf(':');
    if (separator < 1) throw new IllegalArgumentException("Expected namespace:path");
    return new DocumentId(text.substring(0, separator), text.substring(separator + 1));
  }

  @Override
  public String toString() {
    return namespace + ":" + path;
  }
}
