package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.UiText;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiLocal;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.value.ValueTypes;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * Model/projection lifecycle under each actual target classpath; not native paint qualification.
 */
class MountedUiTreeTest {
  private static OwnerContext host() {
    return new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD);
  }

  private static ViewStateKey<String> textKey(String id) {
    return new ViewStateKey<>(DocumentId.parse("example:" + id), ValueTypes.text(128));
  }

  @Test
  void factoriesMountOnceAndSnapshotReadsDoNotProject() {
    try (var owner = host()) {
      var factoryCalls = new AtomicInteger();
      var projections = new AtomicInteger();
      var value = new AtomicReference<UiLocal<String>>();
      var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                factoryCalls.incrementAndGet();
                var local = scope.local(textKey("query"), "first");
                value.set(local);
                return Views.text(
                    "caption",
                    UiText.of(Component.literal("Search")),
                    UiText.from(
                        local,
                        s -> {
                          projections.incrementAndGet();
                          return Component.literal(s);
                        }));
              },
              "immutable");
      assertEquals("first", tree.snapshot().getFirst().body().getString());
      tree.snapshot();
      tree.refresh(false);
      assertEquals(1, factoryCalls.get());
      assertEquals(1, projections.get());
      value.get().replace("current");
      assertTrue(tree.refresh(false));
      assertEquals("current", tree.snapshot().getFirst().body().getString());
      assertEquals(1, factoryCalls.get());
      assertEquals(2, projections.get());
      tree.close();
      assertTrue(owner.isOpen());
      assertEquals(0, tree.scope().usage().scopes());
    }
  }

  @Test
  void actualRetainedViewStateCanBeBorrowedAndExplicitlyRefreshed() {
    try (var owner = host()) {
      var context = ClientViewContext.borrow(owner);
      var key = textKey("view_state");
      var state = new ClientViewState<>(key.capture("first"), context, () -> {});
      var calls = new AtomicInteger();
      var tree =
          MountedUiTree.open(
              context,
              UiLimits.STANDARD,
              (scope, props) -> {
                var source = scope.observe(key, state);
                return Views.text(
                    "text",
                    UiText.of(Component.empty()),
                    UiText.from(
                        source,
                        value -> {
                          calls.incrementAndGet();
                          return Component.literal(value);
                        }));
              },
              "fixed");
      state.replace("second");
      tree.refresh(false);
      assertEquals("second", tree.snapshot().getFirst().body().getString());
      state.refresh();
      tree.refresh(false);
      assertEquals(3, calls.get());
      tree.close();
    }
  }

  @Test
  void conditionalRemovalDisposesBranchCellsAndKeepsAncestorState() {
    try (var owner = host()) {
      var enabled = new AtomicReference<UiLocal<Boolean>>();
      var opened = new AtomicInteger();
      var closed = new AtomicInteger();
      var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var flag =
                    scope.local(
                        new ViewStateKey<>(DocumentId.parse("example:enabled"), ValueTypes.bool()),
                        true);
                enabled.set(flag);
                return Views.show(
                    "detail",
                    flag,
                    branch -> {
                      opened.incrementAndGet();
                      branch.onRetire(closed::incrementAndGet);
                      var local = branch.local(textKey("branch"), "yes");
                      return Views.text(
                          "text",
                          UiText.of(Component.empty()),
                          UiText.from(local, Component::literal));
                    },
                    branch -> Views.text("text", Component.empty(), Component.literal("no")));
              },
              "fixed");
      enabled.get().replace(false);
      tree.refresh(false);
      assertEquals(1, closed.get());
      assertEquals("no", tree.snapshot().getFirst().body().getString());
      enabled.get().replace(true);
      tree.refresh(false);
      assertEquals(2, opened.get());
      assertEquals("yes", tree.snapshot().getFirst().body().getString());
      tree.close();
      assertEquals(2, closed.get());
    }
  }

  @Test
  void factoryFailureAttemptsPartialCleanupAndDoesNotLeakTheBorrowedHostHook() {
    Thread actual = Thread.currentThread();
    try (var owner =
        new OwnerContext(
            Runnable::run,
            Runnable::run,
            () -> {
              if (Thread.currentThread() != actual) throw new IllegalStateException("wrong thread");
            },
            new OwnerContext.Limits(4, 4, 1))) {
      var captured = new AtomicReference<UiScope>();
      var closes = new AtomicInteger();
      var failure = new IllegalStateException("partial factory failure");
      assertSame(
          failure,
          assertThrows(
              IllegalStateException.class,
              () ->
                  MountedUiTree.open(
                      ClientViewContext.borrow(owner),
                      UiLimits.STANDARD,
                      (scope, props) -> {
                        captured.set(scope);
                        scope.onRetire(closes::incrementAndGet);
                        scope.local(textKey("partial"), "owned");
                        throw failure;
                      },
                      "fixed")));
      assertEquals(1, closes.get());
      assertEquals(0, captured.get().usage().stateBytes());
      assertTrue(owner.isOpen());
      try (var fresh =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> Views.text("text", Component.empty(), Component.literal("fresh")),
              "fixed")) {
        assertEquals("fresh", fresh.snapshot().getFirst().body().getString());
      }
    }
  }

  @Test
  void pureTextProjectionCannotEditStateAndFailedReplacementKeepsPriorRows() {
    try (var owner = host()) {
      var state = new AtomicReference<UiLocal<String>>();
      var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var input = scope.local(textKey("input"), "valid");
                state.set(input);
                return Views.text(
                    "text",
                    UiText.of(Component.empty()),
                    UiText.from(
                        input,
                        value -> {
                          if (value.equals("invalid")) input.replace("must not happen");
                          return Component.literal(value);
                        }));
              },
              "fixed");
      state.get().replace("invalid");
      tree.refresh(false);
      var failure = assertInstanceOf(PolicyRefusal.class, tree.failure().orElseThrow());
      assertEquals("ui.write_in_pure_phase", failure.report().issues().getFirst().code());
      assertEquals("invalid", state.get().snapshot().value());
      assertEquals("valid", tree.snapshot().getFirst().body().getString());
      state.get().replace("repaired");
      tree.refresh(false);
      assertTrue(tree.failure().isEmpty());
      assertEquals("repaired", tree.snapshot().getFirst().body().getString());
      tree.close();
    }
  }

  @Test
  void permissionLossClearsPreviouslyDisclosedRowsBeforeRecovery() {
    try (var owner = host()) {
      var allowed = new boolean[] {true};
      var access = owner.access(() -> allowed[0], () -> false);
      var key = textKey("private_title");
      var saved =
          new design.inherent.reform.presentation.ui.UiObservation.Available<>(
              key.capture("Private title"), 1);
      design.inherent.reform.presentation.ui.UiRead<String> source =
          () -> {
            access.require(OwnerContext.Right.READ);
            return saved;
          };
      var creates = new AtomicInteger();
      var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                creates.incrementAndGet();
                var observed = scope.observe(key, source);
                return Views.text(
                    "private",
                    UiText.of(Component.empty()),
                    UiText.from(observed, Component::literal));
              },
              "fixed");
      assertEquals("Private title", tree.snapshot().getFirst().body().getString());
      allowed[0] = false;
      tree.refresh(false);
      assertTrue(
          tree.snapshot().isEmpty(),
          "Earlier private text must not remain displayed after READ loss");
      assertTrue(tree.failure().isPresent());
      allowed[0] = true;
      tree.refresh(false);
      assertEquals("Private title", tree.snapshot().getFirst().body().getString());
      assertEquals(1, creates.get());
      tree.close();
    }
  }
}
