package example;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiObservation;
import design.inherent.reform.presentation.ui.UiScope;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Selective state services from the packaged product, without a game, document, or workflow. */
final class ScopedPresentation {
  static void verify() {
    Thread actualHostThread = Thread.currentThread();
    try (var host =
        new OwnerContext(
            Runnable::run,
            Runnable::run,
            () -> {
              if (Thread.currentThread() != actualHostThread)
                throw new IllegalStateException("The actual host thread is required");
            },
            OwnerContext.Limits.STANDARD)) {
      var root = UiScope.borrow(host, UiLimits.STANDARD);
      var child = root.child("inspector");
      var stateKey = new ViewStateKey<>(DocumentId.parse("example:query"), ValueTypes.text(64));
      var labelKey = new ViewStateKey<>(DocumentId.parse("example:label"), ValueTypes.text(128));
      var query = root.local(stateKey, "before");
      var computations = new AtomicInteger();
      var label =
          child.derive(
              labelKey,
              List.of(query),
              inputs -> {
                computations.incrementAndGet();
                return "Search: " + inputs.value(query);
              });
      root.update(
          () -> {
            query.replace("intermediate");
            query.replace("current");
          });
      require(label.snapshot().value().equals("Search: current"), "Latest derived state missing");
      require(computations.get() == 2, "Batched projections computed more than once per update");
      label.snapshot();
      require(computations.get() == 2, "Cached read executed a callback");
      var bad =
          child.derive(
              new ViewStateKey<>(DocumentId.parse("example:pure"), ValueTypes.text(8)),
              List.of(query),
              inputs -> {
                query.replace("unexpected");
                return "wrong";
              });
      require(bad.snapshot() instanceof UiObservation.Unavailable<?>, "Pure write was accepted");
      require(query.snapshot().value().equals("current"), "Pure computation changed state");
      var closes = new AtomicInteger();
      child.onRetire(closes::incrementAndGet);
      root.close();
      require(host.isOpen(), "Scope closed a borrowed host");
      require(closes.get() == 1, "Owned child was not retired exactly once");
      require(
          root.usage().stateBytes() == 0 && root.usage().scopes() == 0,
          "Retired state still charged");
      try {
        query.replace("late");
        throw new AssertionError("Closed state accepted a late write");
      } catch (PolicyRefusal expected) {
        require(
            expected.report().issues().getFirst().code().equals("ui.scope_closed"),
            "Late write has the wrong refusal");
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private ScopedPresentation() {}
}
