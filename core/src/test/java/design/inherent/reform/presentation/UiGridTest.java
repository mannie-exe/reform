package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.AbstractList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class UiGridTest {
  private static final UiLayout.Offer CONTENT = UiLayout.Offer.flexible(10, 30);

  @Test
  void tracksReserveFixedAndContentBeforeFractionsAndKeepSpanGeometry() {
    var grid =
        new UiGrid.Grid(
            List.of(
                UiGrid.Track.fixed(40),
                UiGrid.Track.content(20, 120),
                UiGrid.Track.fraction(20, 20, 500, 1)),
            2,
            8,
            6);
    var cells =
        List.of(
            new UiGrid.Cell(0, 0),
            new UiGrid.Cell(0, 1),
            new UiGrid.Cell(0, 2),
            new UiGrid.Cell(1, 0, 1, 3));
    var plan =
        UiGrid.plan(
            grid, cells, List.of(CONTENT, UiLayout.Offer.flexible(20, 80), CONTENT, CONTENT), 300);
    assertFalse(plan.stacked());
    assertEquals(
        List.of(40, 80, 164, 300),
        plan.placements().stream().map(UiGrid.Placement::width).toList());
    assertEquals(
        List.of(0, 48, 136, 0), plan.placements().stream().map(UiGrid.Placement::x).toList());
    assertEquals(
        new UiGrid.Vertical(List.of(0, 0, 0, 36), 56),
        UiGrid.arrange(plan, List.of(20, 30, 15, 20)));
  }

  @Test
  void rowSpansContributeContentExtentAndNarrowFallbackKeepsAuthoredOrder() {
    var grid =
        new UiGrid.Grid(
            List.of(UiGrid.Track.fraction(60, 100, 200, 1), UiGrid.Track.fraction(60, 100, 200, 1)),
            2,
            8,
            4);
    // Deliberate authored order differs from row order; layout must not reorder logical children.
    var cells = List.of(new UiGrid.Cell(1, 1), new UiGrid.Cell(0, 0, 2, 1), new UiGrid.Cell(0, 1));
    var plan = UiGrid.plan(grid, cells, List.of(CONTENT, CONTENT, CONTENT), 240);
    var vertical = UiGrid.arrange(plan, List.of(30, 100, 10));
    assertTrue(vertical.tops().get(0) > vertical.tops().get(2));
    assertTrue(vertical.height() >= 100);
    assertTrue(vertical.tops().get(0) >= 10 + 4);
    var narrow = UiGrid.plan(grid, cells, List.of(CONTENT, CONTENT, CONTENT), 100);
    assertTrue(narrow.stacked());
    assertEquals(
        new UiGrid.Vertical(List.of(0, 34, 138), 148),
        UiGrid.arrange(narrow, List.of(30, 100, 10)));
    var fixed = new UiGrid.Grid(List.of(UiGrid.Track.fixed(20)), 1, 0, 0);
    assertTrue(
        UiGrid.plan(fixed, List.of(new UiGrid.Cell(0, 0)), List.of(UiLayout.Offer.fixed(30)), 300)
            .minimumUnmet());
  }

  @Test
  void rejectsOverlapAndOversizeBeforeReadingAnyUnboundedChildren() {
    var grid = new UiGrid.Grid(List.of(UiGrid.Track.fixed(20)), 1, 0, 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> UiGrid.validate(grid, List.of(new UiGrid.Cell(0, 0), new UiGrid.Cell(0, 0))));
    assertThrows(
        IllegalArgumentException.class,
        () -> UiGrid.validate(grid, List.of(new UiGrid.Cell(0, 0, 2, 1))));
    var tooMany =
        new AbstractList<UiLayout.Offer>() {
          public int size() {
            return Integer.MAX_VALUE;
          }

          public UiLayout.Offer get(int index) {
            throw new AssertionError("Must reject size before accessing/copying children");
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> UiLayout.row(new UiLayout.Row(0, 0), tooMany, 200));
    var tracks =
        new AbstractList<UiGrid.Track>() {
          public int size() {
            return Integer.MAX_VALUE;
          }

          public UiGrid.Track get(int index) {
            throw new AssertionError("Must reject size before copying tracks");
          }
        };
    assertThrows(IllegalArgumentException.class, () -> new UiGrid.Grid(tracks, 1, 0, 0));
  }

  @Test
  void seededSpansStayInsideParentAndCannotOverlapAfterBothAxesArePlaced() {
    var random = new Random(136);
    for (int run = 0; run < 500; run++) {
      int columns = 1 + random.nextInt(8), rows = 1 + random.nextInt(8);
      var tracks =
          java.util.stream.IntStream.range(0, columns)
              .mapToObj(
                  i ->
                      UiGrid.Track.fraction(1 + random.nextInt(20), 30, 200, 1 + random.nextInt(3)))
              .toList();
      var grid = new UiGrid.Grid(tracks, rows, 3, 5);
      var cells = new java.util.ArrayList<UiGrid.Cell>();
      var offers = new java.util.ArrayList<UiLayout.Offer>();
      var heights = new java.util.ArrayList<Integer>();
      for (int row = 0; row < rows; row++)
        for (int col = 0; col < columns; ) {
          int span = 1 + random.nextInt(columns - col);
          cells.add(new UiGrid.Cell(row, col, 1, span));
          offers.add(CONTENT);
          heights.add(random.nextInt(100));
          col += span;
        }
      int width = 1 + random.nextInt(600);
      var plan = UiGrid.plan(grid, cells, offers, width);
      var vertical = UiGrid.arrange(plan, heights);
      for (int i = 0; i < cells.size(); i++) {
        var at = plan.placements().get(i);
        assertTrue(at.width() > 0);
        assertTrue(at.x() + at.width() <= width);
        assertTrue(vertical.tops().get(i) + heights.get(i) <= vertical.height());
        for (int j = 0; j < i; j++) {
          var other = plan.placements().get(j);
          assertTrue(
              at.x() >= other.x() + other.width()
                  || other.x() >= at.x() + at.width()
                  || vertical.tops().get(i) >= vertical.tops().get(j) + heights.get(j)
                  || vertical.tops().get(j) >= vertical.tops().get(i) + heights.get(i));
        }
      }
    }
  }
}
