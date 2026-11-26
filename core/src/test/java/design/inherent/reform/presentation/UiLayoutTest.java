package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class UiLayoutTest {
  @Test
  void weightedGrowthRedistributesAfterSaturationAndGapsStayBounded() {
    var row = new UiLayout.Row(8, 16);
    var result =
        UiLayout.row(
            row,
            List.of(new UiLayout.Offer(40, 80, 100, 1, 1), new UiLayout.Offer(40, 80, 400, 3, 1)),
            308);
    assertFalse(result.stacked());
    assertEquals(List.of(100, 200), result.widths());
    assertEquals(8, result.gap());
    var fixed =
        UiLayout.row(row, List.of(UiLayout.Offer.fixed(40), UiLayout.Offer.fixed(40)), 1000);
    assertEquals(List.of(40, 40), fixed.widths());
    assertEquals(16, fixed.gap(), "Spare width must not separate related actions arbitrarily");
  }

  @Test
  void minimaAndUnshrinkableOffersSelectStackingWithoutChangingOrderOrDomain() {
    var row = new UiLayout.Row(8, 8);
    var children =
        List.of(new UiLayout.Offer(60, 100, 120, 1, 0), new UiLayout.Offer(40, 80, 120, 1, 1));
    assertEquals(List.of(100, 40), UiLayout.row(row, children, 148).widths());
    assertTrue(UiLayout.row(row, children, 128).stacked());
    var compact = UiLayout.row(row, children, 50);
    assertTrue(compact.stacked());
    assertTrue(compact.minimumUnmet());
    assertEquals(List.of(50, 50), compact.widths());
  }

  @Test
  void frameHasOnePaddingInsetAndExplicitNarrowOffer() {
    var spec =
        new UiLayout.Frame(new UiLayout.Offer(80, 100, 120, 0, 1), 8, UiLayout.Alignment.CENTER);
    var frame = UiLayout.frame(spec, 200);
    assertEquals(new UiLayout.Framed(50, 100, 58, 84, 8, false), frame);
    var tiny = UiLayout.frame(spec, 10);
    assertTrue(tiny.minimumUnmet());
    assertTrue(tiny.contentWidth() > 0);
    assertTrue(tiny.contentX() + tiny.contentWidth() <= 10);
  }

  @Test
  void boundedSweepKeepsEveryAdjacentTrackInItsOfferAndInsideParent() {
    var random = new Random(135);
    for (int run = 0; run < 1000; run++) {
      var children =
          java.util.stream.IntStream.range(0, 1 + random.nextInt(16))
              .mapToObj(
                  i -> {
                    int min = 1 + random.nextInt(40), pref = min + random.nextInt(60);
                    return new UiLayout.Offer(
                        min, pref, pref + random.nextInt(80), random.nextInt(4), random.nextInt(4));
                  })
              .toList();
      int width = 1 + random.nextInt(1000);
      var result = UiLayout.row(new UiLayout.Row(3, 9), children, width);
      assertEquals(children.size(), result.widths().size());
      if (!result.stacked()) {
        int sum = result.gap() * (children.size() - 1);
        for (int i = 0; i < children.size(); i++) {
          int actual = result.widths().get(i);
          assertTrue(actual >= children.get(i).minimum());
          assertTrue(actual <= children.get(i).maximum());
          sum += actual;
        }
        assertTrue(sum <= width);
      }
    }
    assertThrows(IllegalArgumentException.class, () -> new UiLayout.Offer(5, 4, 10, 1, 1));
    assertThrows(
        IllegalArgumentException.class, () -> UiLayout.row(new UiLayout.Row(0, 0), List.of(), 20));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UiLayout.frame(
                new UiLayout.Frame(UiLayout.Offer.fixed(1), 0, UiLayout.Alignment.START), 0));
  }
}
