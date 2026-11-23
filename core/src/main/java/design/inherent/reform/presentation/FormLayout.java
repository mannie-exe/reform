package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded authored composition. It changes neither values nor domain validation. */
public final class FormLayout {
  public static final FormLayout AUTO = new FormLayout(List.of(), List.of());

  /**
   * Related sibling leaves, in authored order. Columns collapse when measured minima do not fit.
   */
  public record Row(String id, List<FieldPath> fields) {
    public Row {
      identity(id);
      fields = siblings(fields, 2, 3);
    }
  }

  /** A labelled box around related sibling fields or subtrees; help remains inside the box. */
  public record Group(
      String id, String translationKey, String label, String help, List<FieldPath> fields) {
    public Group {
      identity(id);
      if (Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(label).isBlank()
          || label.length() > 1024
          || Objects.requireNonNull(help).length() > 4096)
        throw new IllegalArgumentException("Group captions must be bounded");
      fields = siblings(fields, 1, 128);
    }
  }

  private final List<Row> rows;
  private final List<Group> groups;
  private final Map<FieldPath, Row> rowFields;
  private final Map<FieldPath, Group> groupFields;

  public FormLayout(List<Row> rows, List<Group> groups) {
    this.rows = List.copyOf(rows);
    this.groups = List.copyOf(groups);
    if (rows.size() > 128 || groups.size() > 128)
      throw new IllegalArgumentException("Too many form layout declarations");
    var ids = new HashSet<String>();
    var byRow = new HashMap<FieldPath, Row>();
    for (var row : this.rows) {
      if (!ids.add(row.id())) throw new IllegalArgumentException("Duplicate layout identity");
      for (var field : row.fields())
        if (byRow.putIfAbsent(field, row) != null)
          throw new IllegalArgumentException("A field may belong to one related row");
    }
    var byGroup = new HashMap<FieldPath, Group>();
    for (var group : this.groups) {
      if (!ids.add(group.id())) throw new IllegalArgumentException("Duplicate layout identity");
      for (var field : group.fields())
        if (byGroup.putIfAbsent(field, group) != null)
          throw new IllegalArgumentException("A field may belong to one sibling group");
    }
    for (var row : this.rows) {
      var group = Optional.ofNullable(byGroup.get(row.fields().getFirst()));
      if (row.fields().stream()
          .anyMatch(field -> !group.equals(Optional.ofNullable(byGroup.get(field)))))
        throw new IllegalArgumentException("A related row cannot cross group boundaries");
    }
    for (var path : byGroup.keySet()) {
      long depth =
          this.groups.stream()
              .filter(group -> group.fields().stream().anyMatch(path::startsWith))
              .count();
      if (depth > 6) throw new IllegalArgumentException("Form groups exceed nesting depth 6");
    }
    rowFields = Map.copyOf(byRow);
    groupFields = Map.copyOf(byGroup);
  }

  public List<Row> rows() {
    return rows;
  }

  public List<Group> groups() {
    return groups;
  }

  public Optional<Row> row(FieldPath path) {
    return Optional.ofNullable(rowFields.get(path));
  }

  public Optional<Group> group(FieldPath path) {
    return Optional.ofNullable(groupFields.get(path));
  }

  /** Structural admission only: no source reads, codecs, defaults or value construction. */
  public void requireCompatible(ValueType<?> root) {
    for (var group : groups) for (var field : group.fields()) DraftTypes.declaredAt(root, field);
    for (var row : rows)
      for (var field : row.fields()) {
        var shape = DraftTypes.declaredAt(root, field).shape();
        if (shape instanceof ValueShape.Defaulted<?> value) {
          shape = value.element().shape();
          if (!(shape instanceof ValueShape.Scalar scalar)
              || scalar.kind() != ValueShape.ScalarKind.BOOLEAN)
            throw new IllegalArgumentException(
                "Related rows require direct leaf controls; group structural overrides instead");
        }
        if (!(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>))
          throw new IllegalArgumentException("Related rows require declared leaf controls");
      }
  }

  private static List<FieldPath> siblings(List<FieldPath> fields, int minimum, int maximum) {
    var copy = List.copyOf(fields);
    if (copy.size() < minimum || copy.size() > maximum || new HashSet<>(copy).size() != copy.size())
      throw new IllegalArgumentException("Layout fields need distinct bounded identities");
    List<FieldPath.Segment> parent = null;
    for (var field : copy) {
      FormDependencies.path(field);
      var parts = field.segments();
      if (parts.isEmpty() || !(parts.getLast() instanceof FieldPath.Field))
        throw new IllegalArgumentException("Layout fields must be named siblings");
      var prefix = parts.subList(0, parts.size() - 1);
      if (parent != null && !parent.equals(prefix))
        throw new IllegalArgumentException("Layout fields must share one parent");
      parent = prefix;
    }
    return copy;
  }

  private static void identity(String id) {
    if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9._/-]{0,127}"))
      throw new IllegalArgumentException("Layout identity must be bounded");
  }
}
