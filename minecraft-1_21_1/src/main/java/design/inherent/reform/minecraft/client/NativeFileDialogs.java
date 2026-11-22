package design.inherent.reform.minecraft.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.Util;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** The game-provided blocking picker runs outside the client thread, with one entry claim. */
public final class NativeFileDialogs {
  public static CompletionStage<String> select(String title, BooleanSupplier admission) {
    return CompletableFuture.supplyAsync(
        () ->
            admission.getAsBoolean()
                ? TinyFileDialogs.tinyfd_openFileDialog(title, null, null, null, false)
                : null,
        Util.ioPool());
  }

  private NativeFileDialogs() {}
}
