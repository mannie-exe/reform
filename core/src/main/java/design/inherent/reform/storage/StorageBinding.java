package design.inherent.reform.storage;

import design.inherent.reform.definition.DocumentId;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** An owner-declared location. Constructing one performs no IO and creates no directory. */
public record StorageBinding(Path root, List<String> segments) {
  public StorageBinding {
    Objects.requireNonNull(root);
    if (!root.isAbsolute())
      throw new IllegalArgumentException("Owner must supply an absolute admitted storage root");
    root = root.normalize();
    segments = List.copyOf(segments);
    if (segments.isEmpty() || segments.size() > 16)
      throw new IllegalArgumentException("Expected one to sixteen portable path segments");
    for (String segment : segments) {
      if (!segment.matches("[a-zA-Z0-9_.-]{1,128}")
          || segment.endsWith(".")
          || segment.equals(".")
          || segment.equals(".."))
        throw new IllegalArgumentException("Invalid portable storage segment");
      String stem = segment.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
      if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
        throw new IllegalArgumentException("Reserved portable storage name");
    }
  }

  public static StorageBinding relative(Path root, String... segments) {
    return new StorageBinding(root, Arrays.asList(segments));
  }

  /** New documents only. Existing owners must supply their actual path and format. */
  public static StorageBinding newJson(Path configurationRoot, DocumentId id) {
    var parts = new ArrayList<String>();
    parts.add(id.namespace());
    parts.addAll(Arrays.asList(id.path().split("/", -1)));
    parts.set(parts.size() - 1, parts.getLast() + ".json");
    return new StorageBinding(configurationRoot, parts);
  }

  /** Diagnostic location, not a read/write permission or a client-supplied path capability. */
  public Path path() {
    Path resolved = root;
    for (String segment : segments) resolved = resolved.resolve(segment);
    return resolved;
  }

  String lockKey() throws IOException {
    directory(root);
    return root.toRealPath().resolve(root.relativize(path())).toString().toLowerCase(Locale.ROOT);
  }

  Optional<Path> parent(boolean create, int maximumDirectoryEntries) throws IOException {
    return parent(create, maximumDirectoryEntries, null);
  }

  Optional<Path> parent(boolean create, int maximumDirectoryEntries, Path ownTemporary)
      throws IOException {
    directory(root);
    Path current = root;
    for (int index = 0; index < segments.size() - 1; index++) {
      String name = segments.get(index);
      collision(current, name, maximumDirectoryEntries, ownTemporary);
      Path next = current.resolve(name);
      if (create) {
        try {
          Files.createDirectory(next);
        } catch (FileAlreadyExistsException existing) {
          // A competing initializer may create the same regular directory, never a link/file.
        }
      } else if (Files.notExists(next, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
      directory(next);
      current = next;
    }
    collision(current, segments.getLast(), maximumDirectoryEntries, ownTemporary);
    return Optional.of(current);
  }

  private static void directory(Path path) throws IOException {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Storage requires a regular owner directory");
  }

  private static void collision(Path directory, String name, int limit, Path ownTemporary)
      throws IOException {
    int entries = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      for (Path entry : stream) {
        if (entry.equals(ownTemporary)) continue;
        if (++entries > limit)
          throw new IOException("Storage directory exceeds its admission limit");
        String actual = entry.getFileName().toString();
        if (!actual.equals(name) && actual.equalsIgnoreCase(name))
          throw new IOException("Storage names collide on a case-insensitive filesystem");
      }
    }
  }
}
