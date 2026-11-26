package design.inherent.reform.minecraft.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompletionPlacementTest {
  @Test
  void popupUsesRootSpaceAboveOrBelowWithoutDependingOnBodyClipping() {
    var below = CompletionPlacement.fit(320, 240, 220, 30, 70, 20, 20, 4, 12, 12).orElseThrow();
    assertEquals(4, below.rows());
    assertEquals(50, below.y());
    assertTrue(below.x() + below.width() <= 316);
    assertTrue(below.contains(below.x(), below.y()));
    assertFalse(below.contains(below.x() + below.width(), below.y()));
    var above = CompletionPlacement.fit(320, 240, 20, 200, 100, 20, 20, 4, 12, 12).orElseThrow();
    assertEquals(200, above.y() + above.height());
    assertEquals(4, above.rows());
  }

  @Test
  void constrainedOfferShrinksRowsAndRefusesAnInvisibleAnchor() {
    var compact = CompletionPlacement.fit(120, 100, 10, 5, 90, 20, 20, 4, 12, 12).orElseThrow();
    assertEquals(2, compact.rows());
    assertEquals(112, compact.width());
    assertTrue(CompletionPlacement.fit(120, 60, 10, 20, 90, 20, 20, 4, 12, 12).isEmpty());
    assertTrue(CompletionPlacement.fit(120, 100, -1, 5, 90, 20, 20, 4, 12, 12).isEmpty());
    assertTrue(CompletionPlacement.fit(120, 100, 10, 5, 111, 20, 20, 4, 12, 12).isEmpty());
    assertTrue(CompletionPlacement.fit(7, 100, 0, 5, 7, 20, 20, 4, 12, 12).isEmpty());
  }

  @Test
  void rowIdentityRetainsExactUtf16WithoutRestrictingDomainIds() {
    assertEquals(64, CompletionIdentity.key("").length());
    assertEquals(64, CompletionIdentity.key("x".repeat(40000)).length());
    assertNotEquals(CompletionIdentity.key("\ud800"), CompletionIdentity.key("\ud801"));
    assertNotEquals(CompletionIdentity.key("\ud800"), CompletionIdentity.key("?"));
    assertNotEquals(CompletionIdentity.key("é"), CompletionIdentity.key("e\u0301"));
    assertEquals(CompletionIdentity.key("exact"), CompletionIdentity.key("exact"));
  }
}
