package design.inherent.reform.minecraft.client;

/** Every owned native scope is attempted; the original cleanup failure keeps its siblings. */
final class NativeGraphicCleanup {
  static void run(Runnable... actions) {
    Throwable failure = null;
    for (var action : actions)
      try {
        action.run();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else if (failure != failed) failure.addSuppressed(failed);
      }
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  private NativeGraphicCleanup() {}
}
