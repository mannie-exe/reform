package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderLayoutTest {
  @Test
  void historyStaysAdjacentToThePathAndCompactOffersFlowInReadingOrder() {
    var wide = HeaderLayout.location(400, 20, 31, 80, 4);
    assertEquals(
        List.of(
            new FormGeometry.Placement(0, 0, 31, 20),
            new FormGeometry.Placement(35, 0, 31, 20),
            new FormGeometry.Placement(70, 0, 330, 20)),
        wide.placements());
    assertEquals(20, wide.height());
    var compact = HeaderLayout.location(140, 20, 31, 80, 4);
    assertEquals(new FormGeometry.Placement(0, 24, 140, 20), compact.placements().get(2));
    assertEquals(44, compact.height());
    for (int width : List.of(1, 31, 65, 66, 140, 149, 150, 400, Integer.MAX_VALUE)) {
      var measured = HeaderLayout.location(width, 20, 31, 80, 4);
      assertTrue(
          measured.placements().stream()
              .allMatch(
                  p ->
                      p.x() >= 0
                          && (long) p.x() + p.width() <= width
                          && p.y() + p.height() <= measured.height()));
    }
    assertThrows(IllegalArgumentException.class, () -> HeaderLayout.location(100, 0, 31, 80, 4));
    assertThrows(IllegalArgumentException.class, () -> HeaderLayout.location(100, 20, 0, 80, 4));
  }

  private static HeaderLayout.Offer fixed(int minimum, int preferred, int height) {
    return new HeaderLayout.Offer(minimum, preferred, ignored -> height);
  }

  @Test
  void staticOffersReserveTheHeaderIndependentlyOfCurrentLocationText() {
    var rows = List.of(List.of("identity", "location"), List.of("subtitle"));
    var offers = Map.of("identity", fixed(80, 140, 18), "location", fixed(64, 180, 20));
    var wide = HeaderLayout.measure(rows, offers, 500, 8);
    assertEquals(List.of("identity", "location"), wide.slots().keySet().stream().toList());
    assertEquals(new FormGeometry.Placement(0, 0, 140, 18), wide.slots().get("identity"));
    assertEquals(new FormGeometry.Placement(148, 0, 352, 20), wide.slots().get("location"));
    assertEquals(20, wide.height());
    assertEquals(wide, HeaderLayout.measure(rows, offers, 500, 8));
    assertThrows(UnsupportedOperationException.class, () -> wide.slots().clear());
  }

  @Test
  void wholeAuthoredRowStacksWithoutRetryingItsSuffixAsColumns() {
    var rows = List.of(List.of("identity", "location", "canvas"));
    var offers =
        Map.of(
            "identity", fixed(90, 120, 18),
            "location", fixed(60, 100, 20),
            "canvas", fixed(60, 100, 50));
    var compact = HeaderLayout.measure(rows, offers, 200, 8);
    assertEquals(new FormGeometry.Placement(0, 0, 200, 18), compact.slots().get("identity"));
    assertEquals(new FormGeometry.Placement(0, 26, 200, 20), compact.slots().get("location"));
    assertEquals(new FormGeometry.Placement(0, 54, 200, 50), compact.slots().get("canvas"));
    assertEquals(104, compact.height());
    var exact = HeaderLayout.measure(rows, offers, 226, 8);
    assertTrue(exact.slots().values().stream().allMatch(at -> at.y() == 0));
    assertEquals(226, exact.slots().get("canvas").x() + exact.slots().get("canvas").width());
  }

  @Test
  void wrappingAndOptionalRowsUseTheActualMeasuredWidthAndNoPhantomGap() {
    var rows = List.of(List.of("absent"), List.of("title"), List.of("absent"), List.of("body"));
    var offers =
        Map.of(
            "title",
            new HeaderLayout.Offer(1, 20, w -> (100 + w - 1) / w * 9),
            "body",
            fixed(1, 10, 20));
    var result = HeaderLayout.measure(rows, offers, 25, 4);
    assertEquals(new FormGeometry.Placement(0, 0, 25, 36), result.slots().get("title"));
    assertEquals(new FormGeometry.Placement(0, 40, 25, 20), result.slots().get("body"));
    assertEquals(60, result.height());
    var onePixel = HeaderLayout.measure(rows, offers, 1, 0);
    assertTrue(onePixel.slots().values().stream().allMatch(at -> at.width() == 1 && at.x() == 0));
  }

  @Test
  void invalidOffersRepeatedSlotsAndOverflowRefuseRatherThanClipOrWrapIntegers() {
    assertThrows(IllegalArgumentException.class, () -> fixed(0, 20, 1));
    assertThrows(IllegalArgumentException.class, () -> fixed(20, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> HeaderLayout.measure(List.of(List.of("x")), Map.of("x", fixed(1, 1, 0)), 20, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HeaderLayout.measure(List.of(List.of("x", "x")), Map.of("x", fixed(1, 1, 20)), 20, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HeaderLayout.measure(
                List.of(List.of("x"), List.of("x")), Map.of("x", fixed(1, 1, 20)), 20, 0));
    assertThrows(
        ArithmeticException.class,
        () ->
            HeaderLayout.measure(
                List.of(List.of("x"), List.of("y")),
                Map.of("x", fixed(1, 1, Integer.MAX_VALUE), "y", fixed(1, 1, 20)),
                20,
                1));
    var maximumWidth =
        HeaderLayout.measure(
            List.of(List.of("x")), Map.of("x", fixed(1, 1, 20)), Integer.MAX_VALUE, 0);
    assertEquals(Integer.MAX_VALUE, maximumWidth.slots().get("x").width());
  }
}
