package design.inherent.reform.serialization;

/** Explicit backend admission. These are not a global settings or transport policy. */
public record JsonLimits(
    int maximumBytes,
    int maximumDepth,
    int maximumNodes,
    int maximumStringCharacters,
    int maximumNumberCharacters,
    int maximumAbsoluteScale) {
  public JsonLimits {
    if (maximumBytes < 1
        || maximumBytes == Integer.MAX_VALUE
        || maximumDepth < 1
        || maximumDepth > 256
        || maximumNodes < 1
        || maximumStringCharacters < 1
        || maximumNumberCharacters < 1
        || maximumAbsoluteScale < 0) {
      throw new IllegalArgumentException(
          "Invalid JSON admission limits (maximum supported depth: 256)");
    }
  }
}
