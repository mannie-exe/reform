package design.inherent.reform.presentation.ui;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.value.*;
import org.junit.jupiter.api.Test;

class UiLensTest {
  record Pose(boolean open, int part, double lid) {}

  static final FieldKey<Boolean> OPEN = FieldKey.of("open", ValueTypes.bool());
  static final FieldKey<Integer> PART = FieldKey.of("part", ValueTypes.integer(0, 2));
  static final FieldKey<Double> LID = FieldKey.of("lid", ValueTypes.decimal(0, 1));
  static final ValueType<Pose> TYPE =
      RecordType.<Pose>builder(v -> new Pose(v.get(OPEN), v.get(PART), v.get(LID)))
          .field(OPEN, Pose::open)
          .field(PART, Pose::part)
          .field(LID, Pose::lid)
          .build();

  OwnerContext owner() {
    return new OwnerContext(Runnable::run, Runnable::run, () -> {}, OwnerContext.Limits.STANDARD);
  }

  <V> ViewStateKey<V> key(String id, ValueType<V> type) {
    return new ViewStateKey<>(DocumentId.parse("test:" + id), type);
  }

  @Test
  void independentTypedControlsRetainCompleteSiblingAndPartialPoseState() {
    try (var owner = owner();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var subject = scope.local(key("subject", TYPE), new Pose(false, 0, .5));
      var open =
          UiBindings.lens(
              scope,
              subject,
              key("open", ValueTypes.bool()),
              new UiLens<>(Pose::open, (s, v) -> new Pose(v, s.part(), s.lid())));
      var part =
          UiBindings.lens(
              scope,
              subject,
              key("part", ValueTypes.integer(0, 2)),
              new UiLens<>(Pose::part, (s, v) -> new Pose(s.open(), v, s.lid())));
      assertInstanceOf(UiActionResult.Applied.class, open.changes().invoke(true));
      assertInstanceOf(UiActionResult.Applied.class, part.changes().invoke(2));
      assertEquals(new Pose(true, 2, .5), subject.snapshot().value());
      assertTrue(open.value().snapshot().value());
      assertEquals(2, part.value().snapshot().value());
      assertInstanceOf(UiActionResult.Refused.class, part.changes().invoke(3));
      assertEquals(new Pose(true, 2, .5), subject.snapshot().value());
    }
  }

  @Test
  void aValidPartCannotBypassWholeSubjectCodecOrMutateFromPureReplacement() {
    try (var owner = owner();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var subject = scope.local(key("subject", TYPE), new Pose(false, 0, .5));
      var invalid =
          UiBindings.lens(
              scope,
              subject,
              key("part", ValueTypes.integer(0, 2)),
              new UiLens<>(Pose::part, (s, v) -> new Pose(s.open(), v, 2)));
      assertInstanceOf(UiActionResult.Refused.class, invalid.changes().invoke(1));
      assertEquals(.5, subject.snapshot().value().lid());
      var nested =
          UiBindings.lens(
              scope,
              subject,
              key("nested", ValueTypes.bool()),
              new UiLens<>(
                  Pose::open,
                  (s, v) -> {
                    subject.replace(new Pose(true, 2, 1));
                    return s;
                  }));
      assertInstanceOf(UiActionResult.Refused.class, nested.changes().invoke(true));
      assertEquals(new Pose(false, 0, .5), subject.snapshot().value());
    }
  }

  @Test
  void foreignLocalAndRetiredCallbacksCannotEditAnotherLifetime() {
    try (var owner = owner();
        var scope = UiScope.borrow(owner, UiLimits.STANDARD);
        var other = UiScope.borrow(owner, UiLimits.STANDARD)) {
      var subject = scope.local(key("subject", TYPE), new Pose(false, 0, .5));
      assertThrows(
          PolicyRefusal.class,
          () ->
              UiBindings.lens(
                  other,
                  subject,
                  key("foreign", ValueTypes.bool()),
                  new UiLens<>(Pose::open, (s, v) -> s)));
      var child = scope.child();
      var open =
          UiBindings.lens(
              child,
              subject,
              key("open", ValueTypes.bool()),
              new UiLens<>(Pose::open, (s, v) -> new Pose(v, s.part(), s.lid())));
      child.close();
      assertInstanceOf(UiActionResult.Refused.class, open.changes().invoke(true));
    }
  }
}
