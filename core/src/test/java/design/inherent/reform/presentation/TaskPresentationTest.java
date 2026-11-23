package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TaskPresentationTest {
  private static final TaskPresentation.Offer LIST =
      new TaskPresentation.Offer(new FormGeometry.Size(240, 100), 320);
  private static final TaskPresentation.Offer DETAILS =
      new TaskPresentation.Offer(new FormGeometry.Size(200, 120), 260);

  @Test
  void actualWidthAndUsableHeightAdmitAdjacentOtherwisePreserveCompleteBody() {
    var policy = TaskPresentation.standard();
    var wide = new FormGeometry.Placement(12, 50, 448, 120);
    var result = policy.arrange(TaskPresentation.Kind.CHOOSER, wide, LIST, Optional.of(DETAILS), 8);
    assertEquals(TaskPresentation.Arrangement.ADJACENT, result.arrangement());
    assertTrue(wide.contains(result.primary()));
    assertTrue(wide.contains(result.detail().orElseThrow()));
    assertEquals(240, result.primary().width());
    assertEquals(260, result.detail().orElseThrow().x());
    for (var body :
        java.util.List.of(
            new FormGeometry.Placement(0, 0, 447, 300),
            new FormGeometry.Placement(0, 0, 1000, 119))) {
      var focused =
          policy.arrange(TaskPresentation.Kind.CHOOSER, body, LIST, Optional.of(DETAILS), 8);
      assertEquals(TaskPresentation.Arrangement.FOCUSED, focused.arrangement());
      assertEquals(body, focused.primary());
      assertTrue(focused.detail().isEmpty());
    }
  }

  @Test
  void preferenceIsScopedAndAbsentOptionalContentCreatesNoEmptyPane() {
    var policy =
        TaskPresentation.standard()
            .withPreference(TaskPresentation.Kind.INSPECTION, TaskPresentation.Preference.FOCUSED);
    var body = new FormGeometry.Placement(0, 0, 800, 500);
    assertEquals(
        TaskPresentation.Arrangement.FOCUSED,
        policy
            .arrange(TaskPresentation.Kind.INSPECTION, body, LIST, Optional.of(DETAILS), 8)
            .arrangement());
    assertEquals(
        TaskPresentation.Arrangement.ADJACENT,
        policy
            .arrange(TaskPresentation.Kind.CHOOSER, body, LIST, Optional.of(DETAILS), 8)
            .arrangement());
    assertEquals(
        TaskPresentation.Arrangement.FOCUSED,
        policy
            .arrange(TaskPresentation.Kind.CHOOSER, body, LIST, Optional.empty(), 8)
            .arrangement());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            policy.arrange(
                TaskPresentation.Kind.CHOOSER,
                new FormGeometry.Placement(Integer.MAX_VALUE, 0, 2, 2),
                LIST,
                Optional.of(DETAILS),
                8));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TaskPresentation.Offer(new FormGeometry.Size(20, 20), 19));
  }
}
