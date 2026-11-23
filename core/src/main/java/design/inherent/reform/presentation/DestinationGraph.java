package design.inherent.reform.presentation;

import java.util.*;

/**
 * An admitted immutable navigation topology in authored order. Keys and payloads retain their
 * existing identity types; constructing a graph invokes no destination or source callback.
 */
public final class DestinationGraph<K, T> {
  public record Limits(int maximumNodes, int maximumDepth) {
    public Limits {
      if (maximumNodes < 1 || maximumDepth < 1)
        throw new IllegalArgumentException("Destination graph limits must be positive");
    }
  }

  /** Payloads are cached declarations, not a request to open an owner or execute an action. */
  public record Node<K, T>(K id, Optional<K> parent, T target) {
    public Node {
      Objects.requireNonNull(id);
      Objects.requireNonNull(parent);
      Objects.requireNonNull(target);
      if (parent.filter(id::equals).isPresent())
        throw new IllegalArgumentException("A destination cannot parent itself");
    }
  }

  private final Map<K, Node<K, T>> byId;
  private final List<Node<K, T>> nodes;
  private final Map<K, List<Node<K, T>>> children;
  private final List<Node<K, T>> roots;
  private final Limits limits;

  /**
   * Admit an ordered forest. A workspace with Home can use {@link #requireRoot(Object)} to require
   * one rooted tree; an existing direct-entry workspace need not manufacture a Home destination.
   */
  public DestinationGraph(List<Node<K, T>> nodes, Limits limits) {
    this.limits = Objects.requireNonNull(limits);
    if (nodes.isEmpty() || nodes.size() > limits.maximumNodes())
      throw new IllegalArgumentException("Complete destination graph exceeds its node admission");
    this.nodes = List.copyOf(nodes);
    var index = new LinkedHashMap<K, Node<K, T>>();
    for (var node : this.nodes)
      if (index.putIfAbsent(node.id(), node) != null)
        throw new IllegalArgumentException("Duplicate destination identity");
    var depths = new HashMap<K, Integer>();
    for (var node : this.nodes) {
      var chain = new LinkedHashSet<K>();
      var current = node;
      while (!depths.containsKey(current.id())) {
        if (!chain.add(current.id()))
          throw new IllegalArgumentException("Destination parents contain a cycle");
        if (chain.size() > limits.maximumDepth())
          throw new IllegalArgumentException("Destination hierarchy exceeds its depth admission");
        if (current.parent().isEmpty()) break;
        current = index.get(current.parent().orElseThrow());
        if (current == null) throw new IllegalArgumentException("Destination parent is absent");
      }
      int depth = depths.getOrDefault(current.id(), 0);
      for (var id : new ArrayList<>(chain).reversed()) {
        if (depth >= limits.maximumDepth())
          throw new IllegalArgumentException("Destination hierarchy exceeds its depth admission");
        depths.put(id, ++depth);
      }
    }
    byId = Collections.unmodifiableMap(index);
    var descendants = new LinkedHashMap<K, List<Node<K, T>>>();
    var top = new ArrayList<Node<K, T>>();
    for (var node : this.nodes) {
      if (node.parent().isEmpty()) top.add(node);
      else
        descendants
            .computeIfAbsent(node.parent().orElseThrow(), ignored -> new ArrayList<>())
            .add(node);
    }
    descendants.replaceAll((id, values) -> List.copyOf(values));
    children = Collections.unmodifiableMap(descendants);
    roots = List.copyOf(top);
  }

  public Limits limits() {
    return limits;
  }

  public List<Node<K, T>> nodes() {
    return nodes;
  }

  public Map<K, Node<K, T>> byId() {
    return byId;
  }

  public Optional<Node<K, T>> node(K id) {
    return Optional.ofNullable(byId.get(Objects.requireNonNull(id)));
  }

  public List<Node<K, T>> roots() {
    return roots;
  }

  public List<Node<K, T>> children(K id) {
    Objects.requireNonNull(id);
    return children.getOrDefault(id, List.of());
  }

  /** Root-to-target path, preserving declared ancestry rather than visit history. */
  public List<Node<K, T>> ancestry(K id) {
    var current = node(id).orElseThrow(() -> new IllegalArgumentException("Destination is absent"));
    var result = new ArrayList<Node<K, T>>();
    while (true) {
      result.add(current);
      if (current.parent().isEmpty()) break;
      current = byId.get(current.parent().orElseThrow());
    }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  public DestinationGraph<K, T> requireRoot(K id) {
    if (roots.size() != 1 || !roots.getFirst().id().equals(Objects.requireNonNull(id)))
      throw new IllegalArgumentException("Destination graph needs its declared single root");
    return this;
  }
}
