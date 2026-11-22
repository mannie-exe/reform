package example.reform.platform;

/** Actual game-provided native picker backend, for the independent fixture receipt. */
final class NativeChooserProbe {
  static String backend() {
    return "SDL "
        + org.lwjgl.sdl.SDLVersion.SDL_GetVersion()
        + "/"
        + org.lwjgl.sdl.SDLVersion.SDL_GetRevision()
        + ", main="
        + org.lwjgl.sdl.SDLInit.SDL_IsMainThread();
  }

  private NativeChooserProbe() {}
}
