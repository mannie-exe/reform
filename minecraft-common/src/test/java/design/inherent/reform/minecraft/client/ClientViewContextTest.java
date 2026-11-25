package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Headless lifecycle contract, exercised in each actual native target's test runtime. */
class ClientViewContextTest {
  @Test
  void callerAndFrameworkContextsUseTheSameScopedKernelWithoutAnotherOwner() {
    var owner =
        new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD);
    var external = ClientViewContext.borrow(owner);
    var framework = new ClientViewContext(owner);
    var externalScope = external.openScope(UiLimits.STANDARD);
    var frameworkScope = framework.openScope(UiLimits.STANDARD);
    var key = new ViewStateKey<>(DocumentId.parse("example:query"), ValueTypes.text(32));
    var state = externalScope.local(key, "original");
    var frameworkState = frameworkScope.local(key, "independent");
    var closed = new ArrayList<String>();
    externalScope.onRetire(() -> closed.add("external"));
    frameworkScope.onRetire(() -> closed.add("framework"));

    state.replace("retained");
    assertEquals("independent", frameworkState.snapshot().value());
    externalScope.close();
    assertTrue(external.isOpen());
    assertTrue(frameworkScope.isOpen());
    assertEquals(List.of("external"), closed);
    owner.close();
    assertEquals(List.of("external", "framework"), closed);
    assertEquals(0, frameworkScope.usage().stateBytes());
    assertFalse(external.isOpen());
    assertThrows(PolicyRefusal.class, () -> external.openScope(UiLimits.STANDARD));
  }

  @Test
  void closedHostCannotBeBorrowedAndOpeningScopeRechecksItsActualThread() {
    var threadAllowed = new boolean[] {true};
    var owner =
        new OwnerContext(
            Runnable::run,
            Runnable::run,
            () -> {
              if (!threadAllowed[0]) throw new IllegalStateException("Native thread required");
            },
            OwnerContext.Limits.STANDARD);
    var view = ClientViewContext.borrow(owner);
    threadAllowed[0] = false;
    assertThrows(IllegalStateException.class, () -> view.openScope(UiLimits.STANDARD));
    threadAllowed[0] = true;
    owner.close();
    assertThrows(PolicyRefusal.class, () -> ClientViewContext.borrow(owner));
  }
}
