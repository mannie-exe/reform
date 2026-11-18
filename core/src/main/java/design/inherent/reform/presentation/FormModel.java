package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded active authoring structure, shared by generated and composed views; no owner effects. */
public final class FormModel {
  public enum Role {
    ROOT,
    FIELD,
    ROW,
    MAP_ROW,
    KEY,
    VALUE,
    VARIANT,
    UNKNOWN
  }

  public enum Input {
    SCALAR,
    RECORD,
    SEQUENCE,
    MAPPING,
    DEFAULTED,
    OPTIONAL,
    ALTERNATIVE,
    OPAQUE,
    MISSING,
    NULL
  }

  public record Node(
      FieldPath path,
      Role role,
      String name,
      Optional<ValueType<?>> type,
      DraftNode input,
      List<Node> children) {
    public Node {
      Objects.requireNonNull(path);
      Objects.requireNonNull(role);
      Objects.requireNonNull(name);
      Objects.requireNonNull(type);
      Objects.requireNonNull(input);
      children = List.copyOf(children);
    }

    public String key() {
      return FormModel.key(path);
    }

    public boolean cycling() {
      return FormModel.cycle(type.map(ValueType::shape).orElse(null), input);
    }
  }

  /** Text changes alone need no new widgets; structural state and editor flavor do. */
  public record Layout(
      FieldPath path,
      Role role,
      Optional<ValueShape> shape,
      Input input,
      String branch,
      boolean active,
      List<UUID> retained,
      boolean choice) {
    public Layout {
      retained = List.copyOf(retained);
    }
  }

  private final Node root;
  private final List<Layout> layout;

  public FormModel(ValueType<?> type, DraftTree draft) {
    var builder = new Builder(draft);
    root = builder.node(FieldPath.ROOT, Role.ROOT, "", Optional.of(type), draft.root(), 1);
    layout = List.copyOf(builder.layout);
  }

  public Node root() {
    return root;
  }

  public List<Layout> layout() {
    return layout;
  }

  /** Opaque local widget key. Not a command selector, file location or serialized field path. */
  public static String key(FieldPath path) {
    var result = new StringBuilder("root/");
    for (var segment : path.segments()) {
      switch (segment) {
        case FieldPath.Field field ->
            result.append('f').append(field.id().length()).append(':').append(field.id());
        case FieldPath.Variant variant ->
            result.append('v').append(variant.id().length()).append(':').append(variant.id());
        case FieldPath.Row row -> result.append('r').append(row.id());
        case FieldPath.Part part -> result.append(part == FieldPath.Part.KEY ? 'k' : 'u');
      }
      result.append('/');
    }
    return result.toString();
  }

  private static final class Builder {
    final DraftTree draft;
    final List<Layout> layout = new ArrayList<>();
    int nodes;

    Builder(DraftTree draft) {
      this.draft = java.util.Objects.requireNonNull(draft);
    }

    Node node(
        FieldPath path,
        Role role,
        String name,
        Optional<ValueType<?>> type,
        DraftNode input,
        int depth) {
      if (depth > draft.limits().maximumDepth() || ++nodes > draft.limits().maximumNodes())
        throw new IllegalArgumentException("Form structure exceeds its admitted depth/node budget");
      ValueShape shape = type.map(ValueType::shape).orElse(null);
      layout.add(
          new Layout(
              path,
              role,
              Optional.ofNullable(shape),
              kind(input),
              input instanceof DraftNode.Alternative a ? a.selected() : "",
              input instanceof DraftNode.OptionalValue o
                  ? o.present()
                  : !(input instanceof DraftNode.Defaulted d) || d.overridden(),
              input instanceof DraftNode.Defaulted d
                  ? d.retainedOverrides().stream().map(DraftNode.Row::id).toList()
                  : List.of(),
              cycle(shape, input)));
      var children = new ArrayList<Node>();
      if (shape instanceof ValueShape.ObjectValue object
          && input instanceof DraftNode.ObjectValue values) {
        for (var field : object.fields()) {
          var childPath =
              role == Role.MAP_ROW
                  ? path.append(
                      field.id().equals("key") ? FieldPath.Part.KEY : FieldPath.Part.VALUE)
                  : path.field(field.id());
          var childRole =
              childPath.segments().getLast() instanceof FieldPath.Part part
                  ? part == FieldPath.Part.KEY ? Role.KEY : Role.VALUE
                  : Role.FIELD;
          children.add(
              node(
                  childPath,
                  childRole,
                  field.id(),
                  Optional.of(field.type()),
                  values.fields().getOrDefault(field.id(), new DraftNode.Missing()),
                  depth + 1));
        }
        for (var field : values.fields().entrySet())
          if (object.fields().stream().noneMatch(known -> known.id().equals(field.getKey())))
            children.add(
                node(
                    path.field(field.getKey()),
                    Role.UNKNOWN,
                    field.getKey(),
                    Optional.empty(),
                    field.getValue(),
                    depth + 1));
      } else if (shape instanceof ValueShape.Sequence sequence
          && input instanceof DraftNode.Sequence values) {
        for (int i = 0; i < values.rows().size(); i++) {
          var row = values.rows().get(i);
          children.add(
              node(
                  path.row(row.id()),
                  Role.ROW,
                  Integer.toString(i + 1),
                  Optional.of(sequence.element()),
                  row.value(),
                  depth + 1));
        }
      } else if (shape instanceof ValueShape.Mapping mapping
          && input instanceof DraftNode.Mapping values) {
        var entry = DraftTypes.mapRowType(mapping.key(), mapping.value());
        for (int i = 0; i < values.rows().size(); i++) {
          var row = values.rows().get(i);
          children.add(
              node(
                  path.row(row.id()),
                  Role.MAP_ROW,
                  Integer.toString(i + 1),
                  Optional.of(entry),
                  row.value(),
                  depth + 1));
        }
      } else if (shape instanceof ValueShape.Defaulted<?> defaults
          && input instanceof DraftNode.Defaulted value) {
        if (value.overridden())
          children.add(
              node(
                  path.append(FieldPath.Part.VALUE),
                  Role.VALUE,
                  "",
                  Optional.of(defaults.element()),
                  value.value(),
                  depth + 1));
      } else if (shape instanceof ValueShape.OptionalValue<?> optional
          && input instanceof DraftNode.OptionalValue value) {
        if (value.present())
          children.add(
              node(
                  path.append(FieldPath.Part.VALUE),
                  Role.VALUE,
                  "",
                  Optional.of(optional.element()),
                  value.value(),
                  depth + 1));
      } else if (shape instanceof ValueShape.Alternative alternatives
          && input instanceof DraftNode.Alternative value) {
        alternatives
            .variant(value.selected())
            .ifPresent(
                variant ->
                    children.add(
                        node(
                            path.variant(variant.id()),
                            Role.VARIANT,
                            variant.id(),
                            Optional.of(variant.type()),
                            value.selectedValue(),
                            depth + 1)));
        value
            .unknownFields()
            .forEach(
                (key, child) ->
                    children.add(
                        node(
                            path.field(key),
                            Role.UNKNOWN,
                            key,
                            Optional.empty(),
                            child,
                            depth + 1)));
      }
      return new Node(path, role, name, type, input, children);
    }
  }

  private static boolean cycle(ValueShape shape, DraftNode node) {
    if (shape instanceof ValueShape.Reference<?> reference)
      return cycle(reference.element().shape(), node);
    if (!(shape instanceof ValueShape.Scalar scalar) || !(node instanceof DraftNode.Scalar value))
      return false;
    return scalar.kind() == ValueShape.ScalarKind.BOOLEAN
        ? value.raw().equals("true") || value.raw().equals("false")
        : scalar.choices().contains(value.raw());
  }

  private static Input kind(DraftNode node) {
    return switch (node) {
      case DraftNode.Scalar ignored -> Input.SCALAR;
      case DraftNode.ObjectValue ignored -> Input.RECORD;
      case DraftNode.Sequence ignored -> Input.SEQUENCE;
      case DraftNode.Mapping ignored -> Input.MAPPING;
      case DraftNode.Defaulted ignored -> Input.DEFAULTED;
      case DraftNode.OptionalValue ignored -> Input.OPTIONAL;
      case DraftNode.Alternative ignored -> Input.ALTERNATIVE;
      case DraftNode.Opaque ignored -> Input.OPAQUE;
      case DraftNode.Missing ignored -> Input.MISSING;
      case DraftNode.NullValue ignored -> Input.NULL;
    };
  }
}
