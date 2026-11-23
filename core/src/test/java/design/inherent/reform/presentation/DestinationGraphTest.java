package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DestinationGraphTest {
  private static DestinationGraph.Node<DestinationId, String> node(String id, String parent) {
    return new DestinationGraph.Node<>(
        DestinationId.parse("test:" + id),
        Optional.ofNullable(parent).map(value -> DestinationId.parse("test:" + value)),
        id);
  }

  @Test
  void preservesAuthoredOrderAndAncestryWithIndependentPresentationIds() {
    var home = node("home", null);
    var nodes =
        new ArrayList<>(List.of(home, node("b", "home"), node("a", "home"), node("leaf", "b")));
    var graph = new DestinationGraph<>(nodes, new DestinationGraph.Limits(4, 3));
    assertSame(graph, graph.requireRoot(home.id()));
    assertEquals(
        List.of("b", "a"),
        graph.children(home.id()).stream().map(DestinationGraph.Node::target).toList());
    assertEquals(
        List.of("home", "b", "leaf"),
        graph.ancestry(DestinationId.parse("test:leaf")).stream()
            .map(DestinationGraph.Node::target)
            .toList());
    assertEquals(nodes, graph.nodes());
    nodes.clear();
    assertEquals(4, graph.nodes().size());
    assertThrows(UnsupportedOperationException.class, () -> graph.byId().clear());
    assertThrows(UnsupportedOperationException.class, () -> graph.children(home.id()).clear());
    assertThrows(
        IllegalArgumentException.class, () -> graph.ancestry(DestinationId.parse("test:missing")));
    assertTrue(graph.node(DestinationId.parse("test:missing")).isEmpty());
  }

  @Test
  void completeAdmissionRejectsCyclesMissingParentsDuplicatesAndDepthInEitherDeclarationOrder() {
    var limits = new DestinationGraph.Limits(4, 3);
    assertThrows(IllegalArgumentException.class, () -> new DestinationGraph<>(List.of(), limits));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DestinationGraph<>(List.of(node("a", null), node("a", null)), limits));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DestinationGraph<>(List.of(node("a", "missing")), limits));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DestinationGraph<>(List.of(node("a", "b"), node("b", "a")), limits));
    assertThrows(IllegalArgumentException.class, () -> node("a", "a"));
    var chain = List.of(node("a", null), node("b", "a"), node("c", "b"), node("d", "c"));
    for (var nodes : List.of(chain, chain.reversed())) {
      assertThrows(IllegalArgumentException.class, () -> new DestinationGraph<>(nodes, limits));
      assertEquals(
          4,
          new DestinationGraph<>(nodes, new DestinationGraph.Limits(4, 4))
              .ancestry(DestinationId.parse("test:d"))
              .size());
      assertThrows(
          IllegalArgumentException.class,
          () -> new DestinationGraph<>(nodes, new DestinationGraph.Limits(3, 4)));
    }
    var forest = new DestinationGraph<>(List.of(node("a", null), node("b", null)), limits);
    assertThrows(
        IllegalArgumentException.class, () -> forest.requireRoot(DestinationId.parse("test:a")));
    assertThrows(IllegalArgumentException.class, () -> new DestinationGraph.Limits(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DestinationGraph.Limits(1, 0));
  }

  @Test
  void admittedWorkflowEnvelopeIncludesEveryCategoryPrefixWithoutEnteringPayloads() {
    var nodes = new ArrayList<DestinationGraph.Node<DestinationId, Runnable>>();
    Runnable unopened =
        () -> {
          throw new AssertionError("Opened a destination");
        };
    var home = DestinationId.parse("test:home");
    nodes.add(new DestinationGraph.Node<>(home, Optional.empty(), unopened));
    for (int group = 0; group < 128; group++) {
      var parent = home;
      for (int depth = 0; depth < 4; depth++) {
        var id = DestinationId.parse("test:group_" + group + "/" + depth);
        nodes.add(new DestinationGraph.Node<>(id, Optional.of(parent), unopened));
        parent = id;
      }
      for (int placement = 0; placement < 4; placement++)
        nodes.add(
            new DestinationGraph.Node<>(
                DestinationId.parse("test:placement_" + group + "/" + placement),
                Optional.of(parent),
                unopened));
    }
    assertEquals(1025, nodes.size());
    assertThrows(
        IllegalArgumentException.class,
        () -> new DestinationGraph<>(nodes, new DestinationGraph.Limits(512 + 128 + 1, 6)));
    var graph = new DestinationGraph<>(nodes, new DestinationGraph.Limits(1 + 4 * 128 + 512, 6));
    assertEquals(1025, graph.requireRoot(home).nodes().size());
    assertEquals(128, graph.children(home).size());
  }
}
