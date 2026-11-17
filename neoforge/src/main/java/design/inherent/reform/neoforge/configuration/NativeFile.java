package design.inherent.reform.neoforge.configuration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** Exact admitted native file, read only. Native FML remains the sole serializer/writer. */
final class NativeFile {
  private final byte[] bytes;
  final String revision;

  private NativeFile(byte[] bytes) {
    this.bytes = bytes;
    try {
      revision =
          "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static NativeFile read(Path path, int maximumBytes) throws IOException {
    var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() > maximumBytes)
      throw new IOException("Native source is not an admitted regular file");
    var buffer = ByteBuffer.allocate(maximumBytes + 1);
    try (SeekableByteChannel channel =
        Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
    }
    var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!after.isRegularFile()
        || buffer.position() > maximumBytes
        || before.size() != after.size()
        || after.size() != buffer.position()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
      throw new IOException("Native source changed during the bounded read");
    return new NativeFile(Arrays.copyOf(buffer.array(), buffer.position()));
  }

  String text() throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  boolean same(NativeFile other) {
    return Arrays.equals(bytes, other.bytes);
  }
}
