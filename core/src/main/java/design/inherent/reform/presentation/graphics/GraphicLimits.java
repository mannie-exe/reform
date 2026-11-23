package design.inherent.reform.presentation.graphics;

/** Explicit finite preparation budgets. Native-byte accounting must state when it is estimated. */
public record GraphicLimits(
    long inputBytes, long outputBytes, long decodedPixels, int resources, long nativeBytes) {
  public GraphicLimits {
    if (inputBytes < 1 || outputBytes < 1 || decodedPixels < 1 || resources < 1 || nativeBytes < 1)
      throw new IllegalArgumentException("Graphic preparation budgets must be positive and finite");
  }
}
