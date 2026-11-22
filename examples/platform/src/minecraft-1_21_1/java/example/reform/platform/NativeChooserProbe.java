package example.reform.platform;

/** Actual game-provided native picker backend, for the independent fixture receipt. */
final class NativeChooserProbe {
  static String backend() {
    return "game-provided TinyFD";
  }

  private NativeChooserProbe() {}
}
