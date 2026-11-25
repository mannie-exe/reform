package design.inherent.reform.presentation.ui;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UiScopeTest {
  private static OwnerContext host() {
    Thread owner = Thread.currentThread();
    return new OwnerContext(
        Runnable::run,
        Runnable::run,
        () -> {
          if (Thread.currentThread() != owner) throw new IllegalStateException("Wrong host thread");
        },
        new OwnerContext.Limits(4, 4, 4));
  }

  private static ViewStateKey<String> text(String id) {
    return new ViewStateKey<>(DocumentId.parse("test:" + id), ValueTypes.text(1024));
  }

  private static void code(String code, Runnable refused) {
    assertEquals(
        code, assertThrows(PolicyRefusal.class, refused::run).report().issues().getFirst().code());
  }

  @Test
  void selectiveUseRequiresOnlyBorrowedLifetimeAndNeverClosesItsHost() {
    try (var owner = host()) {
      var scope = UiScope.borrow(owner, UiLimits.STANDARD);
      var local = scope.local(text("query"), "first");
      local.replace("second");
      assertEquals("second", local.snapshot().value());
      scope.close();
      scope.close();
      assertTrue(owner.isOpen());
      assertFalse(scope.isOpen());
      assertEquals(new UiScope.Usage(0, 0, 0, 0, 0, 0), scope.usage());
      code("ui.scope_closed", () -> local.replace("stale"));
      code("ui.scope_closed", local::snapshot);
    }
  }

  @Test
  void stateInitializesOncePerExactKeyAndCannotChangeItsDataContract() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var key = text("query");
      var first = scope.local(key, "first");
      first.replace("retained");
      assertSame(first, scope.local(key, "x".repeat(2048)));
      assertEquals("retained", first.snapshot().value());
      code("ui.binding_mismatch", () -> scope.local(text("query"), "other"));
      assertEquals(1, scope.usage().states());
    }
  }

  @Test
  void cachedDerivationsBatchInDependencyOrderAndDoNotRunOnReads() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var input = scope.local(text("input"), "a");
      var calls = new AtomicInteger();
      var upper =
          scope.derive(
              text("upper"),
              List.of(input),
              in -> {
                calls.incrementAndGet();
                return in.value(input).toUpperCase(java.util.Locale.ROOT);
              });
      var caption =
          scope.derive(text("caption"), List.of(upper), in -> "Result: " + in.value(upper));
      assertEquals("Result: A", caption.snapshot().value());
      assertEquals("Result: A", caption.snapshot().value());
      assertEquals(1, calls.get());
      scope.update(
          () -> {
            input.replace("b");
            input.replace("c");
          });
      assertEquals("Result: C", caption.snapshot().value());
      assertEquals(2, calls.get());
      assertEquals(2, scope.usage().dependencies());
    }
  }

  @Test
  void failedUpdateKeepsActualAcceptedLocalChangesAndRefreshesDependents() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var input = scope.local(text("input"), "a");
      var result = scope.derive(text("result"), List.of(input), in -> in.value(input));
      assertThrows(
          IllegalStateException.class,
          () ->
              scope.update(
                  () -> {
                    input.replace("accepted");
                    throw new IllegalStateException("later event failed");
                  }));
      assertEquals("accepted", result.snapshot().value());
    }
  }

  @Test
  void projectionCannotWriteOrReadAnUndeclaredDependencyAndRetainsActualCause() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var input = scope.local(text("input"), "kept");
      var badWrite =
          scope.derive(
              text("write"),
              List.of(input),
              in -> {
                input.replace("must not happen");
                return "wrong";
              });
      code("ui.write_in_pure_phase", () -> badWrite.snapshot().value());
      assertEquals("kept", input.snapshot().value());
      var badRead = scope.derive(text("read"), List.of(), in -> input.snapshot().value());
      code("ui.undeclared_dependency", () -> badRead.snapshot().value());
      var cause = new IllegalStateException("actual projection failure");
      var failed =
          scope.derive(
              text("failed"),
              List.of(input),
              in -> {
                throw cause;
              });
      var unavailable = assertInstanceOf(UiObservation.Unavailable.class, failed.snapshot());
      assertSame(cause, unavailable.cause().orElseThrow());
    }
  }

  @Test
  void dependencyFailurePropagatesWithoutRunningDownstreamAndCanRecover() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var input = scope.local(text("input"), "bad");
      var first =
          scope.derive(
              text("first"),
              List.of(input),
              in -> {
                if (in.value(input).equals("bad"))
                  throw new PolicyRefusal("test.invalid", "Repair input");
                return in.value(input);
              });
      var calls = new AtomicInteger();
      var next =
          scope.derive(
              text("next"),
              List.of(first),
              in -> {
                calls.incrementAndGet();
                return in.value(first);
              });
      code("test.invalid", () -> next.snapshot().value());
      assertEquals(0, calls.get());
      input.replace("repaired");
      assertEquals("repaired", next.snapshot().value());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void byteRefusalPreservesPriorLocalValueAndProjectionBudgetFailureCanRecover() {
    var limits = new UiLimits(8, 4, 8, 8, 8, 8, 40);
    try (var owner = host();
        var scope = UiScope.borrow(owner, limits)) {
      var input = scope.local(text("input"), "a");
      var result = scope.derive(text("result"), List.of(input), in -> in.value(input).repeat(10));
      assertEquals("a".repeat(10), result.snapshot().value());
      code("ui.budget_exceeded", () -> input.replace("x".repeat(100)));
      assertEquals("a", input.snapshot().value());
      input.replace("long");
      code("ui.budget_exceeded", () -> result.snapshot().value());
      assertEquals(6, scope.usage().stateBytes());
      input.replace("b");
      assertEquals("b".repeat(10), result.snapshot().value());
      assertEquals(15, scope.usage().stateBytes());
    }
  }

  @Test
  void childDisposalEndsItsCellsButNotAncestorStateOrOtherChildren() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var retained = scope.local(text("retained"), "query");
      var child = scope.child("detail");
      assertSame(child, scope.child("detail"));
      var local = child.local(text("input"), "detail");
      var calls = new AtomicInteger();
      var dependent =
          scope.derive(
              text("dependency"),
              List.of(local),
              in -> {
                calls.incrementAndGet();
                return in.value(local);
              });
      child.close();
      code("ui.scope_closed", () -> dependent.snapshot().value());
      assertEquals(1, calls.get());
      assertEquals("query", retained.snapshot().value());
      var fresh = scope.child("detail");
      assertNotSame(child, fresh);
      assertEquals("new", fresh.local(text("input"), "new").snapshot().value());
      code("ui.scope_closed", () -> local.replace("stale"));
    }
  }

  @Test
  void differentRootsClosedInputsAndDuplicateDependenciesRefuseBeforeAdmission() {
    try (var owner = host();
        var a = UiScope.borrow(owner, UiLimits.STANDARD);
        var b = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var input = a.local(text("input"), "a");
      code("ui.binding_mismatch", () -> b.derive(text("result"), List.of(input), in -> "bad"));
      code(
          "ui.binding_mismatch",
          () -> a.derive(text("result"), List.of(input, input), in -> "bad"));
      assertEquals(0, a.usage().projections());
      assertEquals(0, b.usage().dependencies());
    }
  }

  @Test
  void childFirstReverseCleanupAttemptsEverythingAndRetainsFailureWithoutClosingHost() {
    try (var owner = host()) {
      var events = new ArrayList<String>();
      var root = UiScope.borrow(owner, UiLimits.STANDARD);
      var child = root.child("child");
      var first = new IllegalStateException("child failure");
      var second = new IllegalStateException("root failure");
      root.onRetire(
          () -> {
            events.add("root1");
            throw second;
          });
      root.onRetire(() -> events.add("root2"));
      child.onRetire(() -> events.add("child1"));
      child.onRetire(
          () -> {
            assertFalse(root.isOpen());
            events.add("child2");
            throw first;
          });
      assertSame(first, assertThrows(IllegalStateException.class, root::close));
      assertEquals(List.of("child2", "child1", "root2", "root1"), events);
      assertSame(second, first.getSuppressed()[0]);
      assertSame(first, root.releaseFailure().orElseThrow());
      assertEquals(new UiScope.Usage(0, 0, 0, 0, 0, 0), root.usage());
      root.close();
      assertEquals(4, events.size());
      assertTrue(owner.isOpen());
    }
  }

  @Test
  void hostKeepsItsOwnHookOrderAndClosesScopeWithNoResurrection() {
    var owner = host();
    var events = new ArrayList<String>();
    owner.onClose(() -> events.add("before"));
    var root = UiScope.borrow(owner, UiLimits.STANDARD);
    root.child("child").onRetire(() -> events.add("child"));
    root.onRetire(() -> events.add("root"));
    owner.onClose(() -> events.add("after"));
    var input = root.local(text("input"), "kept");
    owner.close();
    assertEquals(List.of("before", "child", "root", "after"), events);
    assertFalse(root.isOpen());
    assertEquals(0, root.usage().stateBytes());
    code("owner.closed", () -> input.replace("late"));
  }

  @Test
  void hostClosureInsideTrustedComputationCannotPublishOrLeakCachedState() {
    var owner = host();
    var root = UiScope.borrow(owner, UiLimits.STANDARD);
    root.derive(
        text("close"),
        List.of(),
        in -> {
          owner.close();
          return "late";
        });
    assertFalse(root.isOpen());
    assertEquals(new UiScope.Usage(0, 0, 0, 0, 0, 0), root.usage());
  }

  @Test
  void wrongThreadCannotReadWriteOrDisposeLiveScope() throws InterruptedException {
    try (var owner = host();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var input = scope.local(text("input"), "kept");
      var failure = new AtomicReference<Throwable>();
      var thread =
          Thread.startVirtualThread(
              () -> {
                try {
                  assertThrows(IllegalStateException.class, input::snapshot);
                  assertThrows(IllegalStateException.class, () -> input.replace("bad"));
                  assertThrows(IllegalStateException.class, scope::close);
                } catch (Throwable failed) {
                  failure.set(failed);
                }
              });
      thread.join(1000);
      assertFalse(thread.isAlive());
      if (failure.get() != null) fail(failure.get());
      assertEquals("kept", input.snapshot().value());
    }
  }

  @Test
  void failedConstructionRefundsOnlyItsAdmissionAndDoesNotCloseOtherState() {
    var limits = new UiLimits(4, 4, 1, 1, 1, 4, 128);
    try (var owner = host();
        var scope = UiScope.borrow(owner, limits)) {
      var input = scope.local(text("input"), "kept");
      code("ui.budget_exceeded", () -> scope.local(text("extra"), "bad"));
      var failure = new AssertionError("factory failure");
      assertSame(
          failure,
          assertThrows(
              AssertionError.class,
              () ->
                  scope.derive(
                      text("result"),
                      List.of(input),
                      in -> {
                        throw failure;
                      })));
      assertEquals(0, scope.usage().projections());
      assertEquals(0, scope.usage().dependencies());
      assertEquals(6, scope.usage().stateBytes());
      var result = scope.derive(text("result"), List.of(input), in -> in.value(input));
      assertEquals("kept", result.snapshot().value());
      code("ui.budget_exceeded", () -> scope.derive(text("extra"), List.of(), in -> "bad"));
      assertEquals(1, scope.usage().projections());
      assertEquals(1, scope.usage().dependencies());
    }
  }

  @Test
  void scopeAndCleanupLimitsRefuseWithoutAllocatingAndHooksCanBeUnregistered() {
    try (var owner = host();
        var scope = UiScope.borrow(owner, new UiLimits(2, 1, 1, 1, 1, 1, 32))) {
      var child = scope.child("child");
      code("ui.budget_exceeded", () -> child.child("too-deep"));
      code("ui.budget_exceeded", () -> scope.child("too-many"));
      var calls = new AtomicInteger();
      var hook = child.onRetire(calls::incrementAndGet);
      code("ui.budget_exceeded", () -> scope.onRetire(calls::incrementAndGet));
      hook.close();
      scope.onRetire(calls::incrementAndGet);
      child.close();
      assertEquals(0, calls.get());
      assertEquals(1, scope.usage().scopes());
    }
  }
}
