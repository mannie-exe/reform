package design.inherent.reform.presentation.graphics;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;

/**
 * Known copied input, scene, or native payload and owned-object accounting; not a heap or driver
 * estimate.
 */
public final class GraphicLedger {
  public record State(long bytes, int resources, long peakBytes, int peakResources) {}

  public final class Allocation implements AutoCloseable {
    private final GraphicLedger owner = GraphicLedger.this;
    private long bytes;
    private final int resources;
    private boolean released;

    private Allocation(long bytes, int resources) {
      this.bytes = bytes;
      this.resources = resources;
    }

    /**
     * Call only after the associated work or resource actually retires; a close request alone is
     * not a release observation.
     */
    @Override
    public void close() {
      synchronized (gate) {
        if (released) return;
        released = true;
        for (var ledger = GraphicLedger.this; ledger != null; ledger = ledger.parent) {
          ledger.retainedBytes -= bytes;
          ledger.retainedResources -= resources;
        }
      }
    }
  }

  private final GraphicLimits limits;
  private final Object gate;
  private final GraphicLedger parent;
  private long retainedBytes, peakBytes;
  private int retainedResources, peakResources;

  /**
   * A separate aggregate for captured input payload, distinct from retained scene/native payload.
   */
  public static GraphicLedger inputs(GraphicLimits limits) {
    return new GraphicLedger(
        new GraphicLimits(
            limits.inputBytes(),
            limits.outputBytes(),
            limits.decodedPixels(),
            limits.resources(),
            limits.inputBytes()));
  }

  public GraphicLedger(GraphicLimits limits) {
    this(limits, null);
  }

  private GraphicLedger(GraphicLimits limits, GraphicLedger parent) {
    this.limits = Objects.requireNonNull(limits);
    this.parent = parent;
    gate = parent == null ? new Object() : parent.gate;
  }

  /** A provider's narrower allowances share atomic ownership with the enclosing workflow. */
  public GraphicLedger child(GraphicLimits limits) {
    Objects.requireNonNull(limits);
    if (limits.inputBytes() > this.limits.inputBytes()
        || limits.outputBytes() > this.limits.outputBytes()
        || limits.decodedPixels() > this.limits.decodedPixels()
        || limits.resources() > this.limits.resources()
        || limits.nativeBytes() > this.limits.nativeBytes())
      throw new IllegalArgumentException("A child graphic allowance cannot widen its parent");
    return new GraphicLedger(limits, this);
  }

  public GraphicLimits limits() {
    return limits;
  }

  /** Grow one owned input block atomically before its next copy, without refunding entered work. */
  public void grow(Allocation allocation, long bytes) {
    Objects.requireNonNull(allocation);
    if (allocation.owner != this)
      throw new IllegalArgumentException("Use this allocation's actual ledger");
    synchronized (gate) {
      if (allocation.released) throw new IllegalStateException("This allocation already retired");
      if (bytes < allocation.bytes)
        throw new IllegalArgumentException("An entered allocation cannot shrink");
      long extra = bytes - allocation.bytes;
      for (var ledger = this; ledger != null; ledger = ledger.parent)
        if (extra > ledger.limits.nativeBytes() - ledger.retainedBytes)
          throw new PolicyRefusal(
              "preview.budget", "Outstanding graphic allocations exceed their payload allowance");
      for (var ledger = this; ledger != null; ledger = ledger.parent) {
        ledger.retainedBytes += extra;
        ledger.peakBytes = Math.max(ledger.peakBytes, ledger.retainedBytes);
      }
      allocation.bytes = bytes;
    }
  }

  public Allocation reserve(long bytes, int resources) {
    if (bytes < 0 || resources < 1)
      throw new IllegalArgumentException(
          "An allocation needs nonnegative payload and positive object accounting");
    synchronized (gate) {
      for (var ledger = this; ledger != null; ledger = ledger.parent)
        if (bytes > ledger.limits.nativeBytes() - ledger.retainedBytes
            || resources > ledger.limits.resources() - ledger.retainedResources)
          throw new PolicyRefusal(
              "preview.budget",
              "Outstanding graphic allocations exceed their payload or object allowance");
      for (var ledger = this; ledger != null; ledger = ledger.parent) {
        ledger.retainedBytes += bytes;
        ledger.retainedResources += resources;
        ledger.peakBytes = Math.max(ledger.peakBytes, ledger.retainedBytes);
        ledger.peakResources = Math.max(ledger.peakResources, ledger.retainedResources);
      }
      return new Allocation(bytes, resources);
    }
  }

  public State state() {
    synchronized (gate) {
      return new State(retainedBytes, retainedResources, peakBytes, peakResources);
    }
  }
}
