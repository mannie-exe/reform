package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One resolved sibling flow for body, field navigation, and presentation-only group destinations.
 */
public final class FormComposition {
  public sealed interface Part permits Field, Box, Row {}

  public record Field(FormModel.Node node) implements Part {
    public Field {
      Objects.requireNonNull(node);
    }
  }

  /** Stable presentation identity, deliberately not a fabricated document FieldPath. */
  public record Box(
      String id, String translationKey, String label, String help, List<Part> children)
      implements Part {
    public Box {
      Objects.requireNonNull(id);
      Objects.requireNonNull(translationKey);
      Objects.requireNonNull(label);
      Objects.requireNonNull(help);
      children = List.copyOf(children);
      if (!id.matches("(?:group|range):[a-z][a-z0-9._/-]{0,127}")
          || translationKey.isBlank()
          || translationKey.length() > 256
          || label.isBlank()
          || label.length() > 1024
          || help.length() > 4096
          || children.isEmpty()
          || children.size() > 128)
        throw new IllegalArgumentException(
            "A resolved box retains its bounded layout identity and captions");
    }
  }

  public record Row(String id, List<Field> children) implements Part {
    public Row {
      Objects.requireNonNull(id);
      children = List.copyOf(children);
      if (!id.matches("row:[a-z][a-z0-9._/-]{0,127}") || children.isEmpty() || children.size() > 3)
        throw new IllegalArgumentException(
            "A resolved row retains its bounded relationship identity");
    }
  }

  public static List<Part> resolve(List<FormModel.Node> siblings, FormPresentation presentation) {
    Objects.requireNonNull(presentation);
    var byPath = new LinkedHashMap<FieldPath, FormModel.Node>();
    for (var node : siblings)
      if (byPath.putIfAbsent(node.path(), node) != null)
        throw new IllegalArgumentException("A composed sibling flow needs unique field identities");
    return resolve(byPath, presentation, true);
  }

  private static List<Part> resolve(
      LinkedHashMap<FieldPath, FormModel.Node> siblings,
      FormPresentation presentation,
      boolean groups) {
    var result = new ArrayList<Part>();
    Set<FieldPath> emitted = new HashSet<>();
    for (var node : siblings.values()) {
      if (emitted.contains(node.path())) continue;
      var group =
          groups
              ? presentation.layout().group(node.path())
              : java.util.Optional.<FormLayout.Group>empty();
      if (group.isPresent()) {
        var box = group.orElseThrow();
        var members = members(box.fields(), siblings);
        result.add(
            new Box(
                "group:" + box.id(),
                box.translationKey(),
                box.label(),
                box.help(),
                resolve(members, presentation, false)));
        emitted.addAll(members.keySet());
        continue;
      }
      var range = presentation.range(node.path());
      if (range.isPresent()) {
        var pair = range.orElseThrow();
        if (siblings.containsKey(pair.minimum()) && siblings.containsKey(pair.maximum())) {
          result.add(
              new Box(
                  "range:" + pair.id(),
                  pair.translationKey(),
                  pair.label(),
                  pair.help(),
                  List.of(
                      new Field(siblings.get(pair.minimum())),
                      new Field(siblings.get(pair.maximum())))));
          emitted.add(pair.minimum());
          emitted.add(pair.maximum());
          continue;
        }
      }
      var row = presentation.layout().row(node.path());
      if (row.isPresent()) {
        var relationship = row.orElseThrow();
        var members = members(relationship.fields(), siblings);
        result.add(
            new Row(
                "row:" + relationship.id(), members.values().stream().map(Field::new).toList()));
        emitted.addAll(members.keySet());
        continue;
      }
      result.add(new Field(node));
      emitted.add(node.path());
    }
    return List.copyOf(result);
  }

  private static LinkedHashMap<FieldPath, FormModel.Node> members(
      List<FieldPath> paths, LinkedHashMap<FieldPath, FormModel.Node> siblings) {
    var members = new LinkedHashMap<FieldPath, FormModel.Node>();
    for (var path : paths) if (siblings.containsKey(path)) members.put(path, siblings.get(path));
    return members;
  }

  /** Flatten this level only; node descendants keep their actual structural parent. */
  public static List<FormModel.Node> fields(List<Part> parts) {
    var result = new ArrayList<FormModel.Node>();
    for (var part : parts)
      switch (part) {
        case Field field -> result.add(field.node());
        case Box box -> result.addAll(fields(box.children()));
        case Row row -> row.children().forEach(field -> result.add(field.node()));
      }
    return List.copyOf(result);
  }

  /**
   * Project admitted field metadata and actual boxes in the same flow as the body. An absent field
   * retains its typed identity with empty content, never another field at a similar index. This
   * neither admits a source nor widens the caller's catalogue or draft limits.
   */
  public static DestinationGraph<FormTarget, Optional<Part>> navigation(
      FormModel model,
      FormPresentation presentation,
      List<FieldPath> admittedFields,
      int maximumEntries) {
    Objects.requireNonNull(model);
    Objects.requireNonNull(presentation);
    var admitted = new java.util.LinkedHashSet<>(admittedFields);
    var builder = new NavigationBuilder(presentation, admitted, maximumEntries);
    var root = new FormTarget.Field(FieldPath.ROOT);
    builder.add(root, Optional.empty(), Optional.of(new Field(model.root())));
    builder.walk(resolve(model.root().children(), presentation), root);
    for (var path : admittedFields) {
      var target = new FormTarget.Field(path);
      if (builder.nodes.containsKey(target)) continue;
      FormTarget parent = root;
      int depth = 0;
      for (var known : builder.nodes.keySet())
        if (known instanceof FormTarget.Field field
            && path.startsWith(field.path())
            && field.path().segments().size() > depth) {
          parent = known;
          depth = field.path().segments().size();
        }
      builder.add(target, Optional.of(parent), Optional.empty());
    }
    return new DestinationGraph<>(
            List.copyOf(builder.nodes.values()),
            new DestinationGraph.Limits(maximumEntries, maximumEntries))
        .requireRoot(root);
  }

  private static final class NavigationBuilder {
    private final FormPresentation presentation;
    private final Set<FieldPath> admitted;
    private final int maximum;
    private final LinkedHashMap<FormTarget, DestinationGraph.Node<FormTarget, Optional<Part>>>
        nodes = new LinkedHashMap<>();

    private NavigationBuilder(FormPresentation presentation, Set<FieldPath> admitted, int maximum) {
      if (maximum < 1) throw new IllegalArgumentException("Navigation admission must be positive");
      this.presentation = presentation;
      this.admitted = admitted;
      this.maximum = maximum;
    }

    private void add(FormTarget id, Optional<FormTarget> parent, Optional<Part> part) {
      if (nodes.size() >= maximum)
        throw new IllegalArgumentException("Complete composed navigation exceeds its admission");
      if (nodes.putIfAbsent(id, new DestinationGraph.Node<>(id, parent, part)) != null)
        throw new IllegalArgumentException("Duplicate composed navigation identity");
    }

    private void walk(List<Part> parts, FormTarget parent) {
      for (var part : parts)
        switch (part) {
          case Field field -> {
            FormTarget next = parent;
            if (admitted.contains(field.node().path())) {
              next = new FormTarget.Field(field.node().path());
              add(next, Optional.of(parent), Optional.of(field));
            }
            walk(resolve(field.node().children(), presentation), next);
          }
          case Box box -> {
            var id = new FormTarget.Section(box.id());
            add(id, Optional.of(parent), Optional.of(box));
            walk(box.children(), id);
          }
          case Row row -> walk(new ArrayList<>(row.children()), parent);
        }
    }
  }

  private FormComposition() {}
}
