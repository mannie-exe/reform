package example.reform.platform;

/**
 * Test-only native frame-loop returns. Several client ticks can precede one return; a skipped
 * surface can still return without painting. Pixel evidence and native-use fences remain separate.
 */
public final class PlatformFrameObservations {
  private static long completed;

  private PlatformFrameObservations() {}

  public static void completedFrame() {
    completed++;
  }

  static long sequence() {
    return completed;
  }
}
