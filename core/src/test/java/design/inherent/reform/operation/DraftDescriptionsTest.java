package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.NumericDisplay;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DraftDescriptionsTest {
  private static DraftDescriptions text(int nodes, int characters) {
    return new DraftDescriptions(
        nodes, characters, "conflict.preview_limit", "Complete conflict input exceeds its limit");
  }

  @Test
  void rawValuesDistinguishMissingNullBlankAndEscapedOrInvisibleInput() {
    var text = text(100, 4096);
    assertEquals("<missing>", text.render(new DraftNode.Missing()));
    assertEquals("<null>", text.render(new DraftNode.NullValue()));
    assertEquals("\"\"", text.render(new DraftNode.Scalar("")));
    assertEquals("\"null\"", text.render(new DraftNode.Scalar("null")));
    assertEquals(
        "\"Café\\u000a\\u202e\\ud800\"", text.render(new DraftNode.Scalar("Café\n\u202e\ud800")));
  }

  @Test
  void allRetainedInputRemainsVisibleWithoutLeakingRowIdentities() {
    var fields = new LinkedHashMap<String, DraftNode>();
    fields.put("absent", new DraftNode.OptionalValue(false, new DraftNode.Scalar("old optional")));
    fields.put(
        "defaulted",
        new DraftNode.Defaulted(
            false,
            new DraftNode.Scalar("old override"),
            List.of(DraftNode.Row.create(new DraftNode.Scalar("earlier override")))));
    fields.put(
        "alternative",
        new DraftNode.Alternative(
            "first",
            Map.of(
                "first",
                new DraftNode.Scalar("active"),
                "second",
                new DraftNode.Scalar("inactive")),
            Map.of("unknown", new DraftNode.Opaque("{bad retained input"))));
    fields.put(
        "number",
        new DraftNode.Scalar(
            "1200", new DraftNode.UnitInput("seconds", "-", NumericDisplay.State.INCOMPLETE)));
    var result = text(100, 4096).render(new DraftNode.ObjectValue(fields));
    for (var value :
        List.of(
            "old optional",
            "old override",
            "earlier override",
            "active",
            "inactive",
            "unknown",
            "bad retained input",
            "seconds",
            "incomplete")) assertTrue(result.contains(value), value);
    var first = DraftNode.Row.create(new DraftNode.Scalar("row"));
    var second = DraftNode.Row.create(new DraftNode.Scalar("row"));
    String a = text(100, 4096).render(new DraftNode.Sequence(List.of(first)));
    String b = text(100, 4096).render(new DraftNode.Sequence(List.of(second)));
    assertEquals(a, b);
    assertFalse(a.contains(first.id().toString()));
  }

  @Test
  void listSetAndDuplicateMapEntriesStayDistinct() {
    var row = DraftNode.Row.create(new DraftNode.Scalar("a"));
    var text = text(100, 4096);
    assertEquals("list [\"a\"]", text.render(new DraftNode.Sequence(List.of(row))));
    assertEquals("set [\"a\"]", text.render(new DraftNode.Sequence(List.of(row), true)));
    var pair =
        new DraftNode.ObjectValue(
            Map.of("key", new DraftNode.Scalar("same"), "value", new DraftNode.Scalar("v")));
    var value =
        text.render(
            new DraftNode.Mapping(List.of(DraftNode.Row.create(pair), DraftNode.Row.create(pair))));
    assertTrue(value.startsWith("map entries ["));
    assertEquals(2, value.split("same", -1).length - 1);
  }

  @Test
  void aggregateNodeAndEscapedTextBudgetsRefuseWholeDescriptions() {
    var text = text(100, 5);
    assertEquals("\"a\"", text.render(new DraftNode.Scalar("a")));
    assertThrows(PolicyRefusal.class, () -> text.render(new DraftNode.Scalar("b")));
    assertThrows(
        PolicyRefusal.class,
        () ->
            text(1, 4096)
                .render(
                    new DraftNode.Sequence(
                        List.of(DraftNode.Row.create(new DraftNode.Scalar("a"))))));
    var escaped =
        assertThrows(PolicyRefusal.class, () -> text(100, 7).render(new DraftNode.Scalar("\n")));
    assertEquals("conflict.preview_limit", escaped.report().issues().getFirst().code());
  }
}
