package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.UiText;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.minecraft.client.ui.Views;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiLocal;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.value.ValueTypes;
import java.util.HashMap;
import java.util.List;
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
  void keyedRowsRetainIdentityAcrossMoveButReadCurrentValuesAndIndices() {
    try (var owner = host()) {
      var items = new AtomicReference<UiLocal<List<String>>>();
      var state = new HashMap<String, UiLocal<String>>();
      var factories = new HashMap<String, Integer>();
      var closes = new HashMap<String, Integer>();
      var type = ValueTypes.text(128);
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var source =
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:rows"), ValueTypes.list(type, 8)),
                        List.of("a:One", "b:Two"));
                items.set(source);
                return Views.forEach(
                    "items",
                    source,
                    type,
                    4,
                    value -> value.substring(0, 1),
                    (row, key, item, index) -> {
                      factories.merge(key, 1, Integer::sum);
                      row.onRetire(() -> closes.merge(key, 1, Integer::sum));
                      var local = row.local(textKey("row_local"), "local " + key);
                      state.put(key, local);
                      return Views.column(
                          "entry",
                          List.of(
                              Views.text(
                                  "value",
                                  UiText.from(index, n -> Component.literal("Position " + n)),
                                  UiText.from(item, Component::literal)),
                              Views.text(
                                  "local",
                                  UiText.of(Component.empty()),
                                  UiText.from(local, Component::literal))));
                    });
              },
              "fixed")) {
        var a = tree.snapshot().get(0).node();
        var b = tree.snapshot().get(2).node();
        state.get("a").replace("keep a");
        items.get().replace(List.of("b:Updated", "a:One"));
        tree.refresh(false);
        assertSame(b, tree.snapshot().get(0).node());
        assertSame(a, tree.snapshot().get(2).node());
        assertEquals(
            List.of("b:Updated", "local b", "a:One", "keep a"),
            tree.snapshot().stream().map(r -> r.body().getString()).toList());
        assertEquals("Position 1", tree.snapshot().get(2).label().getString());
        assertEquals(java.util.Map.of("a", 1, "b", 1), factories);
        assertTrue(closes.isEmpty());
        items.get().replace(List.of("b:Updated"));
        tree.refresh(false);
        assertEquals(1, closes.get("a"));
        assertFalse(a.scope.isOpen());
        items.get().replace(List.of("a:New", "b:Updated"));
        tree.refresh(false);
        assertNotSame(a, tree.snapshot().getFirst().node());
        assertEquals("local a", tree.snapshot().get(1).body().getString());
        assertEquals(2, factories.get("a"));
      }
      assertEquals(java.util.Map.of("a", 2, "b", 1), closes);
    }
  }

  @Test
  void invalidKeyVectorsAndOversizedListsDoNotPartiallyPublishAndCanRecover() {
    try (var owner = host()) {
      var items = new AtomicReference<UiLocal<List<String>>>();
      var creates = new AtomicInteger();
      var type = ValueTypes.text(128);
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var source =
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:keys"), ValueTypes.list(type, 8)),
                        List.of("a", "b"));
                items.set(source);
                return Views.forEach(
                    "items",
                    source,
                    type,
                    3,
                    value -> value,
                    (row, key, item, index) -> {
                      creates.incrementAndGet();
                      return Views.text(
                          "value",
                          UiText.of(Component.empty()),
                          UiText.from(item, Component::literal));
                    });
              },
              "fixed")) {
        var before = tree.snapshot();
        for (var invalid : List.of(List.of("x", "x"), List.of(""), List.of("a", "b", "c", "d"))) {
          items.get().replace(invalid);
          tree.refresh(false);
          assertInstanceOf(PolicyRefusal.class, tree.failure().orElseThrow());
          assertEquals(before, tree.snapshot().subList(1, tree.snapshot().size()));
          assertEquals(
              "This list could not update.", tree.snapshot().getFirst().label().getString());
          assertEquals(2, creates.get());
        }
        items.get().replace(List.of("b", "a"));
        tree.refresh(false);
        assertTrue(tree.failure().isEmpty());
        assertSame(before.get(1).node(), tree.snapshot().get(0).node());
        assertEquals(2, creates.get());
      }
    }
  }

  @Test
  void failedRowKeepsItsSlotCauseAndKeyWithoutReplacingOtherRowsAndRetriesOnUpdate() {
    try (var owner = host()) {
      var items = new AtomicReference<UiLocal<List<String>>>();
      var creates = new HashMap<String, Integer>();
      var closes = new AtomicInteger();
      var failure = new IllegalStateException("actual row factory cause");
      var type = ValueTypes.text(128);
      var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var source =
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:failed_rows"), ValueTypes.list(type, 8)),
                        List.of("a:Good", "b:Bad", "c:Good"));
                items.set(source);
                return Views.forEach(
                    "items",
                    source,
                    type,
                    4,
                    value -> value.substring(0, 1),
                    (row, key, item, index) -> {
                      creates.merge(key, 1, Integer::sum);
                      row.onRetire(closes::incrementAndGet);
                      row.local(textKey("partial_row"), "owned");
                      if (item.snapshot().value().endsWith("Bad")) throw failure;
                      return Views.text(
                          "value",
                          UiText.of(Component.empty()),
                          UiText.from(item, Component::literal));
                    });
              },
              "fixed");
      assertEquals(
          List.of(
              "a:Good", "This item could not be shown. Update the list to try again.", "c:Good"),
          tree.snapshot().stream().map(r -> r.body().getString()).toList());
      var first = tree.snapshot().getFirst().node();
      var last = tree.snapshot().getLast().node();
      var problem = tree.rowFailures().getFirst();
      assertEquals("b", problem.rowKey());
      assertEquals("ui.row_failed", problem.report().issues().getFirst().code());
      assertSame(failure, problem.cause().orElseThrow());
      assertEquals(1, closes.get());
      tree.refresh(false);
      assertEquals(1, creates.get("b"));
      items.get().replace(List.of("a:Good", "b:Repaired", "c:Good"));
      tree.refresh(false);
      assertTrue(tree.rowFailures().isEmpty());
      assertSame(first, tree.snapshot().getFirst().node());
      assertSame(last, tree.snapshot().getLast().node());
      assertEquals(2, creates.get("b"));
      assertEquals("b:Repaired", tree.snapshot().get(1).body().getString());
      tree.close();
      assertEquals(4, closes.get());
      assertEquals(0, tree.scope().usage().stateBytes());
      assertEquals(0, tree.scope().usage().scopes());
    }
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
  void unavailableCollectionRetiresCopiedPrivateRowsBeforeSameSourceRecovery() {
    try (var owner = host()) {
      var allowed = new boolean[] {true};
      var access = owner.access(() -> allowed[0], () -> false);
      var type = ValueTypes.text(128);
      var key =
          new ViewStateKey<>(DocumentId.parse("example:private_rows"), ValueTypes.list(type, 4));
      var saved =
          new design.inherent.reform.presentation.ui.UiObservation.Available<>(
              key.capture(List.of("private")), 1);
      design.inherent.reform.presentation.ui.UiRead<List<String>> source =
          () -> {
            access.require(OwnerContext.Right.READ);
            return saved;
          };
      var rowScopes = new java.util.ArrayList<UiScope>();
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) ->
                  Views.forEach(
                      "private",
                      scope.observe(key, source),
                      type,
                      4,
                      s -> s,
                      (row, rowKey, item, index) -> {
                        rowScopes.add(row);
                        return Views.text(
                            "value",
                            UiText.of(Component.empty()),
                            UiText.from(item, Component::literal));
                      }),
              "fixed")) {
        allowed[0] = false;
        tree.refresh(false);
        assertEquals(1, tree.snapshot().size());
        assertFalse(tree.snapshot().getFirst().body().getString().contains("private"));
        assertFalse(
            rowScopes.getFirst().isOpen(),
            "Copied item values must be retired when their admitted source becomes unavailable");
        allowed[0] = true;
        tree.refresh(false);
        assertEquals("private", tree.snapshot().getFirst().body().getString());
        assertEquals(2, rowScopes.size());
        assertTrue(rowScopes.getLast().isOpen());
      }
    }
  }

  @Test
  void switchBranchesFollowAuthoredKeysAndRejectExclusiveAmbiguityBeforeChangingBranch() {
    try (var owner = host()) {
      var first = new AtomicReference<UiLocal<Boolean>>();
      var second = new AtomicReference<UiLocal<Boolean>>();
      var opened = new HashMap<String, Integer>();
      var closed = new HashMap<String, Integer>();
      java.util.function.Function<String, ViewSpec.Branch> branch =
          key ->
              scope -> {
                opened.merge(key, 1, Integer::sum);
                scope.onRetire(() -> closed.merge(key, 1, Integer::sum));
                return Views.text("body", Component.empty(), Component.literal(key));
              };
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                first.set(
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:first_case"), ValueTypes.bool()),
                        false));
                second.set(
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:second_case"), ValueTypes.bool()),
                        false));
                return Views.switchOn(
                    "case",
                    List.of(
                        new ViewSpec.Case("first", first.get(), branch.apply("first")),
                        new ViewSpec.Case("second", second.get(), branch.apply("second"))),
                    branch.apply("fallback"),
                    true);
              },
              "fixed")) {
        assertEquals("fallback", tree.snapshot().getFirst().body().getString());
        first.get().replace(true);
        tree.refresh(false);
        var same = tree.snapshot().getFirst().node();
        second.get().replace(true);
        tree.refresh(false);
        assertEquals(
            "ui.ambiguous_case",
            ((PolicyRefusal) tree.failure().orElseThrow()).report().issues().getFirst().code());
        assertSame(same, tree.snapshot().getFirst().node());
        assertEquals(java.util.Map.of("fallback", 1, "first", 1), opened);
        first.get().replace(false);
        tree.refresh(false);
        assertTrue(tree.failure().isEmpty());
        assertFalse(same.scope.isOpen());
        assertEquals("second", tree.snapshot().getFirst().body().getString());
        second.get().replace(false);
        tree.refresh(false);
        assertEquals("fallback", tree.snapshot().getFirst().body().getString());
      }
      assertEquals(opened, closed);
    }
  }

  @Test
  void orderedSwitchChoosesFirstMatchingCaseAndRejectsDuplicateCaseIdentity() {
    try (var owner = host()) {
      var secondCalls = new AtomicInteger();
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var yes =
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:ordered_case"), ValueTypes.bool()),
                        true);
                var first =
                    new ViewSpec.Case(
                        "first",
                        yes,
                        row -> Views.text("text", Component.empty(), Component.literal("first")));
                assertThrows(
                    IllegalArgumentException.class,
                    () ->
                        Views.switchOn("duplicate", List.of(first, first), first.content(), false));
                return Views.switchOn(
                    "ordered",
                    List.of(
                        first,
                        new ViewSpec.Case(
                            "second",
                            yes,
                            row -> {
                              secondCalls.incrementAndGet();
                              return Views.text(
                                  "text", Component.empty(), Component.literal("second"));
                            })),
                    first.content(),
                    false);
              },
              "fixed")) {
        assertEquals("first", tree.snapshot().getFirst().body().getString());
        assertEquals(0, secondCalls.get());
      }
    }
  }

  @Test
  void repeatUsesOnlyDeclaredPositionalSlotsAndRetiresRemovedScopes() {
    try (var owner = host()) {
      var count = new AtomicReference<UiLocal<Integer>>();
      var creates = new HashMap<Integer, Integer>();
      var closes = new AtomicInteger();
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                count.set(
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:slot_count"), ValueTypes.integer(-1, 8)),
                        2));
                return Views.repeat(
                    "decoration",
                    count.get(),
                    5,
                    3,
                    (row, slot) -> {
                      creates.merge(slot, 1, Integer::sum);
                      row.onRetire(closes::incrementAndGet);
                      return Views.text(
                          "slot", Component.empty(), Component.literal("Slot " + slot));
                    });
              },
              "fixed")) {
        var stable = tree.snapshot().getFirst().node();
        count.get().replace(1);
        tree.refresh(false);
        assertEquals(1, closes.get());
        assertSame(stable, tree.snapshot().getFirst().node());
        count.get().replace(3);
        tree.refresh(false);
        assertEquals(
            List.of("Slot 5", "Slot 6", "Slot 7"),
            tree.snapshot().stream().map(r -> r.body().getString()).toList());
        assertEquals(java.util.Map.of(5, 1, 6, 2, 7, 1), creates);
        count.get().replace(4);
        tree.refresh(false);
        assertTrue(tree.failure().isPresent());
        assertEquals(1, tree.snapshot().size());
        assertEquals("This list could not update.", tree.snapshot().getFirst().label().getString());
        assertEquals(4, closes.get());
        count.get().replace(0);
        tree.refresh(false);
        assertTrue(tree.failure().isEmpty());
        assertTrue(tree.snapshot().isEmpty());
      }
      assertEquals(4, closes.get());
    }
  }

  @Test
  void failedKeyVectorBlocksOnlyItsRowsAndKeepsARealRepairCommandAvailable() {
    try (var owner = host()) {
      var items = new AtomicReference<UiLocal<List<String>>>();
      var type = ValueTypes.text(128);
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                var source =
                    scope.local(
                        new ViewStateKey<>(
                            DocumentId.parse("example:repair_rows"), ValueTypes.list(type, 4)),
                        List.of("a"));
                items.set(source);
                var repair =
                    new design.inherent.reform.presentation.ui.UiCommand<Void>() {
                      public design.inherent.reform.presentation.ui.UiAvailability availability() {
                        return design.inherent.reform.presentation.ui.UiAvailability.READY;
                      }

                      public design.inherent.reform.presentation.ui.UiActionResult invoke(
                          Void ignored) {
                        source.replace(List.of("a"));
                        return new design.inherent.reform.presentation.ui.UiActionResult.Applied();
                      }
                    };
                return Views.column(
                    "view",
                    List.of(
                        Views.action(
                            "repair",
                            UiText.of(Component.literal("Repair")),
                            UiText.of(Component.empty()),
                            repair),
                        Views.forEach(
                            "items",
                            source,
                            type,
                            4,
                            s -> s,
                            (row, key, item, index) ->
                                Views.action(
                                    "row",
                                    UiText.from(item, Component::literal),
                                    UiText.of(Component.empty()),
                                    repair))));
              },
              "fixed")) {
        items.get().replace(List.of("a", "a"));
        tree.refresh(false);
        var actions =
            tree.snapshot().stream().filter(r -> r.kind() == MountedUiTree.Kind.ACTION).toList();
        assertTrue(
            actions.getFirst().availability().available(),
            "A list failure must not disable unrelated repair controls");
        assertFalse(actions.getLast().availability().available());
      }
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
