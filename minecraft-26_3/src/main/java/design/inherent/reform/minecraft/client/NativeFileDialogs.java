package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.system.MemoryUtil;

/** SDL starts on the client/main thread and owns the open native dialog until its callback. */
public final class NativeFileDialogs {
  private static final AtomicReference<Selection> active = new AtomicReference<>();

  public static CompletionStage<String> select(String title, BooleanSupplier admission) {
    ClientSettingsRuntime.requireClientThread();
    if (!admission.getAsBoolean()) return CompletableFuture.completedStage(null);
    var selection = new Selection();
    if (!active.compareAndSet(null, selection))
      return CompletableFuture.failedStage(
          new IllegalStateException("A native file dialog is already open"));
    try {
      selection.properties = SDLProperties.SDL_CreateProperties();
      if (selection.properties == 0
          || !SDLProperties.SDL_SetStringProperty(
              selection.properties, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title)
          || !SDLProperties.SDL_SetPointerProperty(
              selection.properties,
              SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER,
              Minecraft.getInstance().getWindow().handle()))
        throw new IllegalStateException(
            "The native file dialog could not initialize: " + SDLError.SDL_GetError());
      selection.callback =
          SDL_DialogFileCallback.create(
              (user, files, filter) -> {
                String chosen = null;
                Throwable failure = null;
                try {
                  if (files == 0)
                    throw new IllegalStateException(
                        "The native file dialog failed: " + SDLError.SDL_GetError());
                  long first = MemoryUtil.memGetAddress(files);
                  if (first != 0) chosen = MemoryUtil.memUTF8(first);
                } catch (RuntimeException failed) {
                  failure = failed;
                }
                selection.finish(chosen, failure);
              });
      // The owning window also selects Cocoa's asynchronous sheet path instead of runModal.
      // No filters or borrowed location buffers survive the call. SDL may call back on any thread.
      SDLDialog.SDL_ShowFileDialogWithProperties(
          SDLDialog.SDL_FILEDIALOG_OPENFILE, selection.callback, 0, selection.properties);
    } catch (RuntimeException failed) {
      selection.finish(null, failed);
    }
    return selection.result.minimalCompletionStage();
  }

  private static final class Selection {
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private int properties;
    private SDL_DialogFileCallback callback;

    private void finish(String value, Throwable failure) {
      if (!finished.compareAndSet(false, true)) return;
      try {
        if (callback != null) callback.free();
        if (properties != 0) SDLProperties.SDL_DestroyProperties(properties);
      } finally {
        active.compareAndSet(this, null);
        if (failure == null) result.complete(value);
        else result.completeExceptionally(failure);
      }
    }
  }

  private NativeFileDialogs() {}
}
