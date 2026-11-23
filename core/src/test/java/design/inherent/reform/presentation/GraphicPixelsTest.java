package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicPixels;
import org.junit.jupiter.api.Test;

class GraphicPixelsTest {
  private static final DocumentId ID = DocumentId.parse("fixture:frame");
  private static final GraphicLimits LIMITS = new GraphicLimits(1024, 16, 4, 1, 16);

  @Test
  void frozenArgbPreservesAlphaAndRowMajorPixelIdentity() {
    int[] source = {0x00123456, 0xfff01122, 0x7fabcdef, 0xff3344ef};
    var pixels = new GraphicPixels(LIMITS, ID, 2, 2, source);
    source[0] = 0;
    pixels.argb()[0] = 0;
    assertEquals(0x00123456, pixels.pixel(0, 0));
    assertEquals(0x7fabcdef, pixels.pixel(0, 1));
    assertEquals(0xff3344ef, pixels.pixel(1, 1));
    assertEquals(16, pixels.bytes());
    assertEquals(ID, pixels.id());
    assertThrows(IndexOutOfBoundsException.class, () -> pixels.pixel(-1, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> pixels.pixel(0, 2));
  }

  @Test
  void invalidExtentsAndBudgetRefuseBeforeCopying() {
    assertThrows(
        IllegalArgumentException.class, () -> new GraphicPixels(LIMITS, ID, 0, 0, new int[0]));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicPixels(LIMITS, ID, Integer.MAX_VALUE, Integer.MAX_VALUE, new int[1]));
    assertThrows(
        PolicyRefusal.class,
        () -> new GraphicPixels(new GraphicLimits(1, 15, 4, 1, 16), ID, 2, 2, new int[4]));
    assertThrows(
        PolicyRefusal.class,
        () -> new GraphicPixels(new GraphicLimits(1, 16, 3, 1, 16), ID, 2, 2, new int[4]));
  }
}
