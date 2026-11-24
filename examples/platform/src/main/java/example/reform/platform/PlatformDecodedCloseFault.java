package example.reform.platform;

/** One real decoded image, one post-release fault; no resource or actor state is patched. */
public final class PlatformDecodedCloseFault {
  private static final ThreadLocal<Boolean> DECODING = ThreadLocal.withInitial(() -> false);
  public static volatile boolean armed, actualImageFreed;
  public static volatile RuntimeException expected;

  public static void enter() {
    DECODING.set(armed);
  }

  public static void leave() {
    DECODING.remove();
  }

  public static void closeReturned(long pixels) {
    if (!armed || !DECODING.get()) return;
    armed = false;
    DECODING.remove();
    if (pixels != 0) throw new AssertionError("Decode fault did not observe a freed native image");
    actualImageFreed = true;
    expected = new IllegalStateException("reform-native-decoded-image-close-fault");
    throw expected;
  }

  private PlatformDecodedCloseFault() {}
}
