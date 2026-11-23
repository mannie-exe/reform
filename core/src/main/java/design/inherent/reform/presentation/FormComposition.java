package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
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

  private FormComposition() {}
}
