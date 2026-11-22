package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NavigationHistoryTest {
  @Test
  void capacityEvictsOldestIdentityAndKeepsBothDirectionsBounded() {
    var history = new NavigationHistory<>("a", 3);
    history.select("b");
    history.select("c");
    history.select("d");
    assertEquals("c", history.back());
    assertEquals("b", history.back());
    assertFalse(history.canBack());
    assertEquals("b", history.back());
    assertEquals("c", history.forward());
    assertEquals("d", history.forward());
    assertFalse(history.canForward());
    assertEquals("d", history.forward());
  }

  @Test
  void repeatedEqualSelectionPreservesForwardHistoryButNewSelectionReplacesIt() {
    var history = new NavigationHistory<>("a", 4);
    history.select("b");
    history.select("c");
    history.back();
    history.select(new String(new char[] {'b'}));
    assertTrue(history.canForward());
    assertEquals("c", history.forward());
    history.back();
    history.select("d");
    assertFalse(history.canForward());
    assertEquals("b", history.back());
    assertEquals("a", history.back());
  }

  @Test
  void singleEntryHistoryRetainsOnlyLatestAndRefusesNullOrNonpositiveAdmission() {
    var history = new NavigationHistory<>("a", 1);
    history.select("b");
    assertEquals("b", history.selected());
    assertFalse(history.canBack());
    assertFalse(history.canForward());
    assertThrows(IllegalArgumentException.class, () -> new NavigationHistory<>("a", 0));
    assertThrows(NullPointerException.class, () -> new NavigationHistory<>(null, 1));
    assertThrows(NullPointerException.class, () -> history.select(null));
    assertEquals("b", history.selected());
  }
}
