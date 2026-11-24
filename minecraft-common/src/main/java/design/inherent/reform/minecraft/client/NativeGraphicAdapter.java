package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.value.ValueType;

/**
 * Explicit native implementation for one game version. Preparation is never ordinary paint;
 * submission reads only prepared state and releases through the host's actual native-use lifetime.
 */
public interface NativeGraphicAdapter<I> {
  DocumentId id();

  /** Cached, bounded, effect-free snapshot and serialization policy for the prepared input. */
  ValueType<I> inputType();

  /** Runs on the client/render thread; partial preparation must clean up its owned siblings. */
  Prepared prepare(I input, NativeGraphicPreparationContext context);

  interface Prepared extends NativeGraphicResource {
    ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext context);

    /**
     * Called only after submitted uses retire. The teardown context is actual renderer teardown,
     * not a timer or permission to call a destroyed device. Keep resource release idempotent and
     * retain ledger charges for failed or uncertain releases.
     */
    void release(NativeGraphicReleaseContext context);
  }
}
