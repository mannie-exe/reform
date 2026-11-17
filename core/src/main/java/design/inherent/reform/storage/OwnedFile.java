package design.inherent.reform.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Synchronous worker-side file primitive. No implicit defaults, decoding, authorization or apply.
 */
public final class OwnedFile {
  public enum Persistence {
    UNCHANGED,
    SAVED,
    REFUSED,
    FAILED,
    UNKNOWN
  }

  public static final class Snapshot {
    private final Object binding;
    private final String text;
    private final String revision;

    private Snapshot(Object binding, String text, String revision) {
      this.binding = binding;
      this.text = text;
      this.revision = revision;
    }

    public Optional<String> text() {
      return Optional.ofNullable(text);
    }

    public boolean present() {
      return text != null;
    }

    public String revision() {
      return revision;
    }
  }

  /** SAVED means intended bytes were observed, not that runtime publication succeeded. */
  public record Result(
      Persistence state, Optional<Snapshot> observed, Optional<IOException> failure) {
    public Result {
      Objects.requireNonNull(state);
      Objects.requireNonNull(observed);
      Objects.requireNonNull(failure);
      if ((state == Persistence.SAVED || state == Persistence.UNCHANGED) && observed.isEmpty())
        throw new IllegalArgumentException("Observed persistence requires a snapshot");
    }
  }

  @FunctionalInterface
  interface WriteProbe {
    default void before() throws IOException {}

    void run() throws IOException;
  }

  static final PathLocks LOCKS = new PathLocks();
  private final Object identity = new Object();
  private final StorageBinding binding;
  private final int maximumBytes, maximumDirectoryEntries;
  private final WriteProbe writeProbe;

  public OwnedFile(StorageBinding binding, int maximumBytes) {
    this(binding, maximumBytes, 4096);
  }

  public OwnedFile(StorageBinding binding, int maximumBytes, int maximumDirectoryEntries) {
    this(binding, maximumBytes, maximumDirectoryEntries, () -> {});
  }

  OwnedFile(
      StorageBinding binding, int maximumBytes, int maximumDirectoryEntries, WriteProbe hook) {
    this.binding = Objects.requireNonNull(binding);
    if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE || maximumDirectoryEntries < 1)
      throw new IllegalArgumentException("Invalid file admission limits");
    this.maximumBytes = maximumBytes;
    this.maximumDirectoryEntries = maximumDirectoryEntries;
    writeProbe = Objects.requireNonNull(hook);
  }

  public StorageBinding binding() {
    return binding;
  }

  public Snapshot read() throws IOException {
    return LOCKS.withLock(binding.lockKey(), this::readLocked);
  }

  private Snapshot readLocked() throws IOException {
    return readLocked(null);
  }

  private Snapshot readLocked(Path ownTemporary) throws IOException {
    if (binding.parent(false, maximumDirectoryEntries, ownTemporary).isEmpty()) return missing();
    final BasicFileAttributes attributes;
    try {
      attributes =
          Files.readAttributes(
              binding.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException missing) {
      return missing();
    }
    if (!attributes.isRegularFile() || attributes.isSymbolicLink())
      throw new IOException("Owned configuration must be a regular local file");
    byte[] bytes;
    try (var input = Files.newInputStream(binding.path(), LinkOption.NOFOLLOW_LINKS)) {
      bytes = input.readNBytes(maximumBytes + 1);
    }
    if (bytes.length > maximumBytes)
      throw new IOException("Owned configuration exceeds its byte limit");
    String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    return new Snapshot(identity, text, digest(bytes));
  }

  private Snapshot missing() {
    return new Snapshot(identity, null, "missing");
  }

  /**
   * Replace against exact observed text, or publish a complete first file if it is still absent.
   * This coordinates library writers, not arbitrary processes racing the final native move.
   */
  public Result write(Snapshot expected, String candidate) {
    return write(expected, candidate, () -> {});
  }

  /**
   * The synchronous, nonblocking guard verifies an already authorized operation's current lifetime.
   * It runs under the file lock; it must never dispatch to or await a native executor.
   */
  public Result write(Snapshot expected, String candidate, Runnable currentLifetime) {
    Objects.requireNonNull(expected);
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(currentLifetime);
    if (expected.binding != identity)
      throw new IllegalArgumentException("Snapshot belongs to another file binding");
    final ByteBuffer encoded;
    try {
      if (candidate.length() > maximumBytes)
        throw new IOException("Candidate exceeds its byte limit");
      encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(candidate));
      if (encoded.remaining() > maximumBytes)
        throw new IOException("Candidate exceeds its byte limit");
    } catch (IOException refused) {
      return result(Persistence.REFUSED, null, refused);
    }
    try {
      return LOCKS.withLock(
          binding.lockKey(),
          () -> {
            Snapshot current = readLocked();
            if (!same(expected, current))
              return result(
                  Persistence.REFUSED,
                  current,
                  new IOException("Saved bytes changed; review them before saving"));
            currentLifetime.run();
            if (current.present() && candidate.equals(current.text))
              return result(Persistence.UNCHANGED, current, null);
            Path parent = binding.parent(!current.present(), maximumDirectoryEntries).orElseThrow();
            if (current.present() && !Files.isWritable(binding.path()))
              return result(
                  Persistence.REFUSED,
                  current,
                  new IOException("Owned configuration is not writable"));
            Path temporary =
                Files.createTempFile(
                    parent, "." + binding.path().getFileName() + ".", ".reform.tmp");
            boolean attempted = false;
            boolean replaced = false;
            Result completed;
            try {
              if (current.present()) {
                var attributes =
                    Files.getFileAttributeView(
                        binding.path(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes != null)
                  Files.setPosixFilePermissions(
                      temporary, attributes.readAttributes().permissions());
              }
              try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (encoded.hasRemaining()) output.write(encoded);
                output.force(true);
              }
              Snapshot checked = readLocked(temporary);
              if (!same(expected, checked))
                completed =
                    result(
                        Persistence.REFUSED,
                        checked,
                        new IOException("Saved bytes changed during preparation"));
              else {
                writeProbe.before();
                currentLifetime.run();
                attempted = true;
                if (current.present())
                  Files.move(
                      temporary,
                      binding.path(),
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                else Files.createLink(binding.path(), temporary);
                replaced = true;
                writeProbe.run();
                Snapshot observed = readLocked(temporary);
                completed =
                    candidate.equals(observed.text)
                        ? result(Persistence.SAVED, observed, null)
                        : result(
                            Persistence.UNKNOWN,
                            observed,
                            new IOException("Saved bytes changed before readback"));
              }
            } catch (IOException | RuntimeException failure) {
              completed =
                  reconcile(
                      expected,
                      candidate,
                      attempted,
                      io(failure),
                      failure instanceof FileAlreadyExistsException
                          && !current.present()
                          && !replaced,
                      temporary);
            } catch (Error fatal) {
              try {
                Files.deleteIfExists(temporary);
              } catch (IOException | RuntimeException cleanup) {
                fatal.addSuppressed(cleanup);
              }
              throw fatal;
            }
            try {
              Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanupFailure) {
              IOException cleanup = io(cleanupFailure);
              if (completed.failure().isPresent())
                completed.failure().orElseThrow().addSuppressed(cleanup);
              else
                completed = result(completed.state(), completed.observed().orElse(null), cleanup);
            }
            return completed;
          });
    } catch (IOException | RuntimeException refused) {
      return result(Persistence.REFUSED, null, io(refused));
    }
  }

  private Result reconcile(
      Snapshot expected,
      String candidate,
      boolean attempted,
      IOException failure,
      boolean competitor,
      Path ownTemporary) {
    try {
      Snapshot observed = readLocked(ownTemporary);
      if (!attempted || competitor) return result(Persistence.REFUSED, observed, failure);
      if (candidate.equals(observed.text)) return result(Persistence.SAVED, observed, failure);
      return result(
          same(expected, observed) ? Persistence.FAILED : Persistence.UNKNOWN, observed, failure);
    } catch (IOException | RuntimeException unavailable) {
      failure.addSuppressed(unavailable);
      return result(attempted ? Persistence.UNKNOWN : Persistence.REFUSED, null, failure);
    }
  }

  private static IOException io(Exception failure) {
    return failure instanceof IOException io
        ? io
        : new IOException("File backend could not complete", failure);
  }

  private static boolean same(Snapshot first, Snapshot second) {
    return Objects.equals(first.text, second.text);
  }

  private static Result result(Persistence state, Snapshot observed, IOException failure) {
    return new Result(state, Optional.ofNullable(observed), Optional.ofNullable(failure));
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new AssertionError(unavailable);
    }
  }
}
