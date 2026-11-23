package design.inherent.reform.minecraft.client;

import design.inherent.reform.owner.OwnerContext;
import java.util.Objects;

/**
 * Borrowed native view lifetime. Register cleanup before allocating resources; this context grants
 * no document access, persistence operation, worker executor, or rendering authority.
 */
public final class ClientViewContext {
  private final OwnerContext owner;

  ClientViewContext(OwnerContext owner) {
    this.owner = Objects.requireNonNull(owner);
  }

  /** Cached lifetime observation. Activation still requires the current native owner thread. */
  public boolean isOpen() {
    return owner.isOpen();
  }

  public void requireCurrent() {
    owner.requireOwner();
  }

  /** Native-thread check that also works inside this context's actual cleanup callbacks. */
  public void checkNativeThread() {
    owner.checkOwnerThread();
  }

  /**
   * Register before entering the allocation. All registered actions are attempted on construction
   * failure and final retirement. Closing the hook unregisters it; it does not run cleanup.
   */
  public OwnerContext.CloseHook onClose(Runnable cleanup) {
    return owner.onClose(Objects.requireNonNull(cleanup));
  }
}
