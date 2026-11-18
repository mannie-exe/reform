package design.inherent.reform.importing;

import design.inherent.reform.definition.DocumentId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Read-only, explicitly admitted local file. Never construct from an untrusted server path. */
public final class FileImportSource implements ImportSource {
  private final DocumentId id;
  private final Path path;
  private final String label;
  private final int maximumBytes;
  private final Runnable current;

  public FileImportSource(DocumentId id, Path admittedPath, int maximumBytes, Runnable current) {
    this.id = Objects.requireNonNull(id);
    Objects.requireNonNull(admittedPath);
    if (!admittedPath.isAbsolute()
        || admittedPath.getFileName() == null
        || maximumBytes < 1
        || maximumBytes == Integer.MAX_VALUE)
      throw new IllegalArgumentException(
          "Import requires an admitted absolute file and byte bound");
    path = admittedPath.normalize();
    if (path.getFileName() == null)
      throw new IllegalArgumentException("Import requires a file rather than a root directory");
    label = path.getFileName().toString();
    if (label.isBlank() || label.length() > 256)
      throw new IllegalArgumentException("Import filename exceeds its display admission");
    this.maximumBytes = maximumBytes;
    this.current = Objects.requireNonNull(current);
  }

  public FileImportSource(DocumentId id, Path admittedPath, int maximumBytes) {
    this(id, admittedPath, maximumBytes, () -> {});
  }

  @Override
  public DocumentId id() {
    return id;
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public int maximumBytes() {
    return maximumBytes;
  }

  @Override
  public void requireCurrent() {
    current.run();
  }

  @Override
  public Capture capture() {
    return this::read;
  }

  private ImportInput read() throws IOException {
    var attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || attributes.isSymbolicLink())
      throw new IOException("Import requires a regular non-symlink local file");
    if (attributes.size() > maximumBytes)
      throw new IOException("Import file exceeds its byte limit");
    final byte[] bytes;
    try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(maximumBytes + 1);
    }
    if (bytes.length > maximumBytes) throw new IOException("Import file exceeds its byte limit");
    return new ImportInput(label, bytes, maximumBytes);
  }
}
