package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScreenFrameTest {
  @Test
  void readingContentCentersInsideItsActualParentAndCollapsesWithoutEscaping() {
    var wide = new FormGeometry.Placement(0, 0, 1100, 760);
    var content = FormGeometry.centeredWidth(wide, 408);
    assertEquals(new FormGeometry.Placement(346, 0, 408, 760), content);
    assertTrue(wide.contains(content));
    var narrow = new FormGeometry.Placement(17, 29, 320, 240);
    assertEquals(narrow, FormGeometry.centeredWidth(narrow, 408));
    assertEquals(
        new FormGeometry.Placement(57, 29, 240, 240), FormGeometry.centeredWidth(narrow, 240));
    assertThrows(IllegalArgumentException.class, () -> FormGeometry.centeredWidth(wide, 0));
    assertThrows(
        ArithmeticException.class,
        () ->
            FormGeometry.centeredWidth(
                new FormGeometry.Placement(Integer.MAX_VALUE, 0, 320, 240), 100));
  }

  @Test
  void nativeChromeUsesMeasuredTextAndKeepsEveryRegionInsideTheHost() {
    var actions = List.of(new FormGeometry.Size(130, 20), new FormGeometry.Size(180, 20));
    var frame = FormGeometry.screenFrame(854, 480, 20, 18, 20, actions);
    assertEquals(12, frame.title().orElseThrow().y());
    assertEquals(32, frame.context().orElseThrow().y());
    assertEquals(58, frame.content().y());
    assertEquals(448, frame.actions().orElseThrow().y());
    assertFalse(frame.headerInContent());
    assertFalse(frame.statusInContent());
    assertFalse(frame.actionsInContent());
    assertTrue(frame.bounds().contains(frame.content()));
    var wrapped = FormGeometry.screenFrame(320, 240, 20, 54, 20, actions);
    assertEquals(54, wrapped.context().orElseThrow().height());
    assertEquals(44, wrapped.actionFlow().height());
    assertTrue(wrapped.content().height() > 0);
  }

  @Test
  void tallChromeMovesToScrollingContentInsteadOfOverlappingOrEscaping() {
    for (int width : List.of(1, 40, 320, 550, 1100))
      for (int height : List.of(1, 40, 240, 380, 760))
        for (int context : List.of(0, 9, Integer.MAX_VALUE)) {
          var frame =
              FormGeometry.screenFrame(
                  width,
                  height,
                  20,
                  context,
                  20,
                  java.util.Collections.nCopies(16, new FormGeometry.Size(180, 20)));
          assertTrue(frame.bounds().contains(frame.content()));
          assertEquals(frame.title().isEmpty(), frame.headerInContent());
          if (frame.actionsInContent()) assertTrue(frame.actions().isEmpty());
          if (frame.statusInContent()) assertTrue(frame.status().isEmpty());
        }
    assertThrows(
        IllegalArgumentException.class,
        () -> FormGeometry.screenFrame(0, 240, 20, 9, 20, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> FormGeometry.screenFrame(320, 240, -1, 9, 20, List.of()));
  }

  @Test
  void containmentUsesWideArithmeticAndRefusesEmptyChildren() {
    var parent = new FormGeometry.Placement(0, 0, 320, 240);
    assertFalse(parent.contains(new FormGeometry.Placement(Integer.MAX_VALUE, 0, 20, 20)));
    assertFalse(parent.contains(new FormGeometry.Placement(0, 0, 0, 20)));
    assertFalse(parent.contains(new FormGeometry.Placement(-1, 0, 20, 20)));
  }
}
