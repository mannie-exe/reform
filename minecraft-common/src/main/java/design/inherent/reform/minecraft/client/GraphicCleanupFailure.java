package design.inherent.reform.minecraft.client;

/** Internal signal: native release is uncertain and its reservation remains charged. */
final class GraphicCleanupFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  GraphicCleanupFailure(Throwable cause) {
    super("Preview cleanup failed; its remaining resources are still accounted for", cause);
  }

  static GraphicCleanupFailure find(Throwable cause) {
    while (cause instanceof java.util.concurrent.CompletionException
        || cause instanceof java.util.concurrent.ExecutionException) {
      if (cause.getCause() == null) return null;
      cause = cause.getCause();
    }
    return cause instanceof GraphicCleanupFailure failed ? failed : null;
  }
}
