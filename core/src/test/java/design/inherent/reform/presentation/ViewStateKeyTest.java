package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ViewStateKeyTest {
  @Test
  void bookmarkRetainsOnlyDataAndEveryRestoreOwnsItsValue() {
    var key =
        new ViewStateKey<>(
            DocumentId.parse("example:list"), ValueTypes.list(ValueTypes.text(64), 8));
    var original = new ArrayList<>(List.of("selected"));
    var saved = key.capture(original);
    original.add("later");
    assertEquals(List.of("selected"), saved.value());
    assertEquals(List.of("selected"), saved.value());
    assertSame(key, saved.key());
  }

  @Test
  void rejectsEncodedByteAndNodeExcessWithoutReplacingPriorSnapshot() {
    var key =
        new ViewStateKey<>(
            DocumentId.parse("example:text"),
            ValueTypes.text(8192),
            new JsonLimits(16, 4, 4, 8192, 8, 0));
    var before = key.capture("kept");
    assertThrows(IllegalArgumentException.class, () -> key.capture("😀".repeat(5)));
    assertEquals("kept", before.value());
    var list =
        new ViewStateKey<>(
            DocumentId.parse("example:nodes"),
            ValueTypes.list(ValueTypes.text(8), 8),
            new JsonLimits(128, 4, 2, 8, 8, 0));
    assertThrows(IllegalArgumentException.class, () -> list.capture(List.of("a", "b", "c")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ViewStateKey<>(
                key.id(), ValueTypes.text(64), new JsonLimits(16_385, 4, 4, 8, 8, 0)));
  }
}
