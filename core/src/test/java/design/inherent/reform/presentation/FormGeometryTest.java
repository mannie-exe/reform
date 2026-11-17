package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormGeometryTest {
  @Test
  void columnsRequireAnExplicitRelationshipAndBothMinimumWidths() {
    assertFalse(FormGeometry.pair(null, 170, null, 170, 1000, 12));
    assertFalse(FormGeometry.pair("limits", 170, "appearance", 170, 1000, 12));
    assertFalse(FormGeometry.pair("limits", 170, "limits", 240, 491, 12));
    assertTrue(FormGeometry.pair("limits", 170, "limits", 240, 492, 12));
    assertFalse(
        FormGeometry.pair("limits", Integer.MAX_VALUE, "limits", Integer.MAX_VALUE, 1000, 12));
  }

  @Test
  void narrowControlsWrapWithinTheirBoundsAndRetainAuthoredOrder() {
    var sizes =
        List.of(
            new FormGeometry.Size(80, 20),
            new FormGeometry.Size(18, 20),
            new FormGeometry.Size(18, 20),
            new FormGeometry.Size(42, 20));
    var narrow = FormGeometry.controls(sizes, 100, 4);
    assertEquals(44, narrow.height());
    for (var at : narrow.placements()) assertTrue(at.x() >= 0 && at.x() + at.width() <= 100);
    assertEquals(0, narrow.placements().getFirst().y());
    assertEquals(24, narrow.placements().get(1).y());
    var wide = FormGeometry.controls(sizes, 170, 4);
    assertEquals(20, wide.height());
    assertTrue(wide.placements().stream().allMatch(at -> at.y() == 0));
  }

  @Test
  void oversizedControlsClampWhileEmptyRowsHaveNoPhantomHeight() {
    var layout =
        FormGeometry.controls(
            List.of(new FormGeometry.Size(200, 30), new FormGeometry.Size(10, 12)), 70, 4);
    assertEquals(70, layout.placements().getFirst().width());
    assertEquals(46, layout.height());
    assertEquals(0, FormGeometry.controls(List.of(), 10, 4).height());
    assertThrows(IllegalArgumentException.class, () -> FormGeometry.controls(List.of(), 0, 4));
  }
}
