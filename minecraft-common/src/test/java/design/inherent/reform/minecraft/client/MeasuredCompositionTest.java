package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.UiLayout;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class MeasuredCompositionTest {
  @Test
  void wrappersKeepReadingOrderAndDoNotReplaceUnchangedActionProjection() {
    try (var owner =
        new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD)) {
      var cell = new AtomicReference<UiLocal<String>>();
      var calls = new AtomicInteger();
      var action =
          new UiCommand<Void>() {
            public UiAvailability availability() {
              return UiAvailability.READY;
            }

            public UiActionResult invoke(Void ignored) {
              calls.incrementAndGet();
              return new UiActionResult.Applied();
            }
          };
      try (var tree =
          MountedUiTree.open(
              ClientViewContext.borrow(owner),
              UiLimits.STANDARD,
              (scope, props) -> {
                cell.set(
                    scope.local(
                        new ViewStateKey<>(DocumentId.parse("test:unrelated"), ValueTypes.text(64)),
                        "first"));
                return Views.row(
                    "browser",
                    new UiLayout.Row(8, 16),
                    List.of(
                        Views.frame(
                            "choices",
                            new UiLayout.Frame(
                                UiLayout.Offer.flexible(80, 160), 8, UiLayout.Alignment.START),
                            Views.text(
                                "label",
                                Component.literal("Available profiles"),
                                Component.empty())),
                        Views.frame(
                            "preview",
                            new UiLayout.Frame(
                                UiLayout.Offer.flexible(80, 220), 8, UiLayout.Alignment.START),
                            Views.action(
                                "prepare",
                                UiText.of(Component.literal("Prepare preview")),
                                UiText.of(Component.empty()),
                                action))));
              },
              "fixed")) {
        var before =
            tree.snapshot().stream()
                .filter(row -> row.kind() == MountedUiTree.Kind.ACTION)
                .findFirst()
                .orElseThrow();
        assertEquals(
            List.of("Available profiles", "Prepare preview"),
            tree.snapshot().stream()
                .map(row -> row.label().getString())
                .filter(text -> !text.isEmpty())
                .toList());
        cell.get().replace("second");
        tree.refresh(false);
        var after =
            tree.snapshot().stream()
                .filter(row -> row.kind() == MountedUiTree.Kind.ACTION)
                .findFirst()
                .orElseThrow();
        assertSame(before, after, "Geometry wrappers do not change a command's declared inputs");
        assertEquals(0, calls.get(), "Mounting/projecting/reading layout must not invoke actions");
        assertTrue(tree.failure().isEmpty());
      }
    }
  }
}
