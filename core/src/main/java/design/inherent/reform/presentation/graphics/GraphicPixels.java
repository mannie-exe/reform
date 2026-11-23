package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;

/** Immutable row-major ARGB pixels for one still frame, independent of native image ownership. */
public final class GraphicPixels {
  private final DocumentId id;
  private final int width, height;
  private final int[] argb;

  public GraphicPixels(GraphicLimits limits, DocumentId id, int width, int height, int[] argb) {
    Objects.requireNonNull(limits);
    this.id = Objects.requireNonNull(id);
    Objects.requireNonNull(argb);
    if (width < 1 || height < 1 || (long) width * height != argb.length)
      throw new IllegalArgumentException(
          "A graphic frame needs its complete positive pixel extent");
    if (argb.length > limits.decodedPixels()
        || (long) argb.length * Integer.BYTES > limits.outputBytes())
      throw new PolicyRefusal(
          "preview.budget", "The decoded frame exceeds its pixel or output-byte budget");
    this.width = width;
    this.height = height;
    this.argb = argb.clone();
  }

  public DocumentId id() {
    return id;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public long bytes() {
    return (long) argb.length * Integer.BYTES;
  }

  public int[] argb() {
    return argb.clone();
  }

  public int pixel(int x, int y) {
    if (x < 0 || y < 0 || x >= width || y >= height)
      throw new IndexOutOfBoundsException("Pixel outside the captured frame");
    return argb[y * width + x];
  }
}
