package example.reform.platform;

import design.inherent.reform.storage.OwnedFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Scoped observer of the actual replaced-file boundary; never installed by a product. */
public final class PlatformBatchWriteFault {
  @FunctionalInterface
  public interface Hook {
    void replaced(Path path) throws IOException;
  }

  private static final AtomicReference<Hook> ACTIVE = new AtomicReference<>();

  public static Runnable install(Hook hook) {
    Objects.requireNonNull(hook);
    if (!ACTIVE.compareAndSet(null, hook))
      throw new IllegalStateException("Write probe already active");
    return () -> ACTIVE.compareAndSet(hook, null);
  }

  public static void replaced(OwnedFile file) throws IOException {
    var hook = ACTIVE.get();
    if (hook != null) hook.replaced(file.binding().path());
  }

  private PlatformBatchWriteFault() {}
}
