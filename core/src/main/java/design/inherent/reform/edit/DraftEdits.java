package design.inherent.reform.edit;

import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shape-checked local edits. Invalid values stay drafts; only explicit actions replace data. */
public final class DraftEdits {
  public static ValueType<DraftEdit> type() {
    return DraftEditTypes.TYPE;
  }

  static DraftTree apply(ValueType<?> root, DraftTree tree, DraftEdit edit) {
    var path = edit.target().path();
    var type = DraftTypes.at(root, tree, path);
    return tree.change(path, node -> change(type, node, edit.action(), tree.limits()));
  }

  private static DraftNode change(
      ValueType<?> type, DraftNode node, DraftEdit.Action action, DraftLimits limits) {
    var shape = type.shape();
    if (action instanceof DraftEdit.Repair) return blank(type, limits);
    if (action instanceof DraftEdit.SetRaw raw
        && (shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>))
      return new DraftNode.Scalar(raw.text());
    if (shape instanceof ValueShape.Sequence sequence && node instanceof DraftNode.Sequence values)
      return new DraftNode.Sequence(
          rows(
              values.rows(),
              action,
              sequence.maximumSize(),
              () -> blank(sequence.element(), limits)),
          values.unordered());
    if (shape instanceof ValueShape.Mapping mapping && node instanceof DraftNode.Mapping values)
      return new DraftNode.Mapping(
          rows(
              values.rows(),
              action,
              mapping.maximumSize(),
              () ->
                  new DraftNode.ObjectValue(
                      Map.of(
                          "key",
                          blank(mapping.key(), limits),
                          "value",
                          blank(mapping.value(), limits)))));
    if (shape instanceof ValueShape.OptionalValue<?> optional
        && node instanceof DraftNode.OptionalValue value
        && action instanceof DraftEdit.Presence presence)
      return new DraftNode.OptionalValue(
          presence.present(),
          presence.present() && value.value() instanceof DraftNode.Missing
              ? blank(optional.element(), limits)
              : value.value());
    if (shape instanceof ValueShape.Defaulted<?> defaults
        && node instanceof DraftNode.Defaulted value) {
      if (action instanceof DraftEdit.Override override)
        return value
            .withValue(
                override.overridden() && value.value() instanceof DraftNode.Missing
                    ? fallback(defaults, limits)
                    : value.value())
            .withOverride(override.overridden());
      if (action instanceof DraftEdit.RestoreOverride restore)
        return value.restoreRetained(restore.row());
      if (action instanceof DraftEdit.DiscardOverride discard)
        return value.discardRetained(discard.row());
    }
    if (shape instanceof ValueShape.Alternative alternatives
        && node instanceof DraftNode.Alternative value) {
      if (action instanceof DraftEdit.SelectVariant select) {
        var variant =
            alternatives
                .variant(select.id())
                .orElseThrow(() -> new IllegalArgumentException("Select a declared variant"));
        return value.branches().containsKey(select.id())
            ? value.select(select.id(), new DraftNode.Missing())
            : value.select(select.id(), blank(variant.type(), limits));
      }
      if (action instanceof DraftEdit.DiscardBranch discard)
        return value.discardInactive(discard.id());
      if (action instanceof DraftEdit.RemoveUnknown remove) {
        if (!value.unknownFields().containsKey(remove.field()))
          throw new IllegalArgumentException("Unknown field no longer exists");
        var fields = new LinkedHashMap<>(value.unknownFields());
        fields.remove(remove.field());
        return new DraftNode.Alternative(value.selected(), value.branches(), fields);
      }
    }
    if (shape instanceof ValueShape.ObjectValue object
        && node instanceof DraftNode.ObjectValue value
        && action instanceof DraftEdit.RemoveUnknown remove) {
      if (object.fields().stream().anyMatch(field -> field.id().equals(remove.field()))
          || !value.fields().containsKey(remove.field()))
        throw new IllegalArgumentException("Select an existing undeclared field");
      return value.without(remove.field());
    }
    throw new IllegalArgumentException("This action does not apply to the declared draft shape");
  }

  private static List<DraftNode.Row> rows(
      List<DraftNode.Row> rows,
      DraftEdit.Action action,
      int maximum,
      java.util.function.Supplier<DraftNode> seed) {
    var changed = new ArrayList<>(rows);
    if (action instanceof DraftEdit.InsertRow insert) {
      if (rows.size() >= maximum)
        throw new IllegalArgumentException("Collection is at its declared limit");
      changed.add(before(rows, insert.before()), DraftNode.Row.create(seed.get()));
    } else if (action instanceof DraftEdit.DuplicateRow duplicate) {
      if (rows.size() >= maximum)
        throw new IllegalArgumentException("Collection is at its declared limit");
      var original = rows.get(index(rows, duplicate.row())).value();
      changed.add(before(rows, duplicate.before()), DraftNode.Row.create(copyRows(original)));
    } else if (action instanceof DraftEdit.RemoveRow remove) {
      changed.remove(index(rows, remove.row()));
    } else if (action instanceof DraftEdit.MoveRow move) {
      int source = index(rows, move.row());
      if (move.before().filter(move.row()::equals).isPresent()) return rows;
      int destination = before(rows, move.before());
      var selected = changed.remove(source);
      changed.add(destination > source ? destination - 1 : destination, selected);
    } else throw new IllegalArgumentException("Select a collection row action");
    return changed;
  }

  private static int before(List<DraftNode.Row> rows, Optional<UUID> before) {
    return before.map(id -> index(rows, id)).orElse(rows.size());
  }

  private static int index(List<DraftNode.Row> rows, UUID id) {
    for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) return i;
    throw new IllegalArgumentException("Row no longer exists in this draft");
  }

  private static <T> DraftNode fallback(ValueShape.Defaulted<T> defaults, DraftLimits limits) {
    return DraftTree.from(defaults.element(), defaults.fallback(), limits).root();
  }

  /** Blank required leaves remain invalid until authored; no factory/domain work is invoked. */
  public static DraftNode blank(ValueType<?> type, DraftLimits limits) {
    var result = new Seed(limits).value(type, 1);
    limits.check(result);
    return result;
  }

  private static final class Seed {
    final DraftLimits limits;
    int nodes;
    long characters;

    Seed(DraftLimits limits) {
      this.limits = java.util.Objects.requireNonNull(limits);
    }

    DraftNode value(ValueType<?> type, int depth) {
      var shape = type.shape();
      if (shape instanceof ValueShape.Reference<?> reference)
        return value(reference.element(), depth);
      if (depth > limits.maximumDepth() || ++nodes > limits.maximumNodes())
        throw new IllegalArgumentException("Blank shape exceeds its declared depth/node budget");
      if (shape instanceof ValueShape.Scalar) return new DraftNode.Scalar("");
      if (shape instanceof ValueShape.ObjectValue object) {
        var fields = new LinkedHashMap<String, DraftNode>();
        for (var field : object.fields()) {
          characters += field.id().length();
          if (characters > limits.maximumCharacters())
            throw new IllegalArgumentException("Blank shape exceeds its text budget");
          fields.put(field.id(), value(field.type(), depth + 1));
        }
        return new DraftNode.ObjectValue(fields);
      }
      if (shape instanceof ValueShape.Sequence sequence)
        return new DraftNode.Sequence(
            List.of(), sequence.order() == ValueShape.CollectionOrder.UNORDERED);
      if (shape instanceof ValueShape.Mapping) return new DraftNode.Mapping(List.of());
      if (shape instanceof ValueShape.OptionalValue<?>)
        return new DraftNode.OptionalValue(false, new DraftNode.Missing());
      if (shape instanceof ValueShape.Defaulted<?>)
        return new DraftNode.Defaulted(false, new DraftNode.Missing());
      if (shape instanceof ValueShape.Alternative)
        return new DraftNode.Alternative("", Map.of(), Map.of());
      return new DraftNode.Missing();
    }
  }

  /** Duplicated nested rows/branches receive new identities; immutable leaf input is preserved. */
  private static DraftNode copyRows(DraftNode node) {
    if (node instanceof DraftNode.Sequence value)
      return new DraftNode.Sequence(
          value.rows().stream().map(row -> DraftNode.Row.create(copyRows(row.value()))).toList(),
          value.unordered());
    if (node instanceof DraftNode.Mapping value)
      return new DraftNode.Mapping(
          value.rows().stream().map(row -> DraftNode.Row.create(copyRows(row.value()))).toList());
    if (node instanceof DraftNode.ObjectValue value) {
      var fields = new LinkedHashMap<String, DraftNode>();
      value.fields().forEach((key, child) -> fields.put(key, copyRows(child)));
      return new DraftNode.ObjectValue(fields);
    }
    if (node instanceof DraftNode.OptionalValue value)
      return new DraftNode.OptionalValue(value.present(), copyRows(value.value()));
    if (node instanceof DraftNode.Defaulted value)
      return new DraftNode.Defaulted(
          value.overridden(),
          copyRows(value.value()),
          value.retainedOverrides().stream()
              .map(row -> DraftNode.Row.create(copyRows(row.value())))
              .toList());
    if (node instanceof DraftNode.Alternative value) {
      var branches = new LinkedHashMap<String, DraftNode>();
      value.branches().forEach((key, child) -> branches.put(key, copyRows(child)));
      var unknown = new LinkedHashMap<String, DraftNode>();
      value.unknownFields().forEach((key, child) -> unknown.put(key, copyRows(child)));
      return new DraftNode.Alternative(value.selected(), branches, unknown);
    }
    return node;
  }

  private DraftEdits() {}
}
