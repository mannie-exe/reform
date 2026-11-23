package design.inherent.reform.presentation.graphics;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;

/** Known native buffer/image payload and owned-object accounting, not a driver-memory estimate. */
public final class GraphicLedger {
  public record State(long bytes, int resources, long peakBytes, int peakResources) {}

  public final class Allocation implements AutoCloseable {
    private final long bytes;
    private final int resources;
    private boolean released;

    private Allocation(long bytes, int resources) {
      this.bytes = bytes;
      this.resources = resources;
    }

    /**
     * Call only after actual native release; a close request alone is not a release observation.
     */
    @Override
    public void close() {
      synchronized (GraphicLedger.this) {
        if (released) return;
        released = true;
        retainedBytes -= bytes;
        retainedResources -= resources;
      }
    }
  }

  private final GraphicLimits limits;
  private long retainedBytes, peakBytes;
  private int retainedResources, peakResources;

  public GraphicLedger(GraphicLimits limits) {
    this.limits = Objects.requireNonNull(limits);
  }

  public GraphicLimits limits() {
    return limits;
  }

  public synchronized Allocation reserve(long bytes, int resources) {
    if (bytes < 0 || resources < 1)
      throw new IllegalArgumentException(
          "A native allocation needs nonnegative payload and positive object accounting");
    if (bytes > limits.nativeBytes() - retainedBytes
        || resources > limits.resources() - retainedResources)
      throw new PolicyRefusal(
          "preview.budget",
          "Outstanding graphic allocations exceed the native payload or object allowance");
    retainedBytes += bytes;
    retainedResources += resources;
    peakBytes = Math.max(peakBytes, retainedBytes);
    peakResources = Math.max(peakResources, retainedResources);
    return new Allocation(bytes, resources);
  }

  public synchronized State state() {
    return new State(retainedBytes, retainedResources, peakBytes, peakResources);
  }
}
