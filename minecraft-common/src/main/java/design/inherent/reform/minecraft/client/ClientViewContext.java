package design.inherent.reform.minecraft.client;

import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiScope;
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

  /**
   * A caller-owned native host can borrow the same view services without creating a workflow or
   * document. The supplied context must enforce the actual native thread and lifetime. This wrapper
   * does not transfer ownership or close that context.
   */
  public static ClientViewContext borrow(OwnerContext owner) {
    Objects.requireNonNull(owner).requireOwner();
    return new ClientViewContext(owner);
  }

  /** Opens one bounded presentation root, automatically retired with this borrowed lifetime. */
  public UiScope openScope(UiLimits limits) {
    requireCurrent();
    return UiScope.borrow(owner, limits);
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
