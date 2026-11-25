package design.inherent.reform.minecraft.client.ui;

/**
 * Owner-thread callbacks for an actually attached subtree. No callback runs during painting. Update
 * precedes source observation; suspend withdraws only this subtree's interests. The component's
 * scope remains the sole retirement owner.
 */
public interface UiMountLifecycle {
  void update();

  void suspend();
}
