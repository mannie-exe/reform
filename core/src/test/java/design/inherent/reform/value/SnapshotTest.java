package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SnapshotTest {
  @Test
  void sharedAcyclicReferencesAreAllowedAndCopiedSeparately() {
    var child = new ArrayList<>(List.of("a"));
    var type = ValueTypes.list(ValueTypes.list(ValueTypes.text(8), 4), 4);
    var copy = type.freeze(List.of(child, child));
    child.clear();
    assertEquals(List.of(List.of("a"), List.of("a")), copy);
  }

  private record Node(List<Node> children) {}

  @Test
  void trueObjectCyclesAreRefusedBeforeUnboundedRecursion() {
    var nodes = new AtomicReference<ValueType<Node>>();
    nodes.set(
        ValueType.of(
            Codec.unit(new Node(List.of())),
            new ValueShape.Custom("fixture:node"),
            (value, context) ->
                new Node(
                    value.children().stream()
                        .map(child -> nodes.get().freeze(child, context))
                        .toList())));
    var children = new ArrayList<Node>();
    var root = new Node(children);
    children.add(root);
    assertThrows(IllegalArgumentException.class, () -> nodes.get().freeze(root));
  }

  @Test
  void nodeAndDepthLimitsAreIndependent() {
    var type = ValueTypes.list(ValueTypes.text(8), 4);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            type.freeze(List.of("a", "b"), new SnapshotContext(new SnapshotContext.Limits(8, 2))));
    assertThrows(
        IllegalArgumentException.class,
        () -> type.freeze(List.of("a"), new SnapshotContext(new SnapshotContext.Limits(1, 10))));
  }

  @Test
  void listSemanticEqualityUsesItsChildPolicy() {
    var insensitive =
        ValueType.of(
            Codec.STRING,
            new ValueShape.Custom("fixture:case"),
            new SnapshotPolicy<String>() {
              @Override
              public String freeze(String value, SnapshotContext context) {
                return value;
              }

              @Override
              public boolean equivalent(String first, String second) {
                return first.equalsIgnoreCase(second);
              }
            });
    var type = ValueTypes.list(insensitive, 8);
    assertTrue(type.equivalent(List.of("Hello"), List.of("hello")));
    assertFalse(type.equivalent(List.of("Hello"), List.of("other")));
  }
}
