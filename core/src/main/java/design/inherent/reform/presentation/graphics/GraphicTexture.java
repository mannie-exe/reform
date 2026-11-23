package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Frozen encoded PNG and sidecar, never a live resource, stream, native image or GPU handle. */
public final class GraphicTexture {
  private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
  private final DocumentId id;
  private final byte[] bytes;
  private final byte[] metadata;
  private final int width, height;

  public GraphicTexture(
      GraphicLimits limits, DocumentId id, byte[] png, Optional<byte[]> metadata) {
    Objects.requireNonNull(limits);
    this.id = Objects.requireNonNull(id);
    Objects.requireNonNull(png);
    Objects.requireNonNull(metadata);
    if ((long) png.length + metadata.map(value -> value.length).orElse(0) > limits.inputBytes())
      throw new PolicyRefusal("preview.budget", "The texture exceeds its encoded input budget");
    bytes = png.clone();
    this.metadata = metadata.map(byte[]::clone).orElse(null);
    if (bytes.length < 33 || !Arrays.equals(PNG, Arrays.copyOf(bytes, 8)))
      throw new IllegalArgumentException("A captured texture needs a PNG header");
    var header = ByteBuffer.wrap(bytes);
    if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452)
      throw new IllegalArgumentException("A captured PNG needs its leading IHDR");
    width = header.getInt(16);
    height = header.getInt(20);
    if (width < 1 || height < 1)
      throw new IllegalArgumentException("A captured PNG needs positive dimensions");
    if ((long) width * height > limits.decodedPixels())
      throw new PolicyRefusal("preview.budget", "The PNG strip exceeds its decoded-pixel budget");
  }

  public DocumentId id() {
    return id;
  }

  public byte[] png() {
    return bytes.clone();
  }

  public Optional<byte[]> metadata() {
    return Optional.ofNullable(metadata).map(byte[]::clone);
  }

  public int encodedWidth() {
    return width;
  }

  public int encodedHeight() {
    return height;
  }

  public long encodedBytes() {
    return (long) bytes.length + (metadata == null ? 0 : metadata.length);
  }
}
