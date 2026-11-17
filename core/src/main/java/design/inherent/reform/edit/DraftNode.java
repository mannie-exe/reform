package design.inherent.reform.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable raw drafts. Row identity is retained for edits but is never serialized as user data.
 */
public sealed interface DraftNode
    permits DraftNode.Scalar,
        DraftNode.ObjectValue,
        DraftNode.Sequence,
        DraftNode.Opaque,
        DraftNode.Missing,
        DraftNode.NullValue {
  record Size(int depth, int nodes, int characters) {
    public long historyCost() {
      return (long) characters + 32L * nodes;
    }
  }

  Size size();

  final class Scalar implements DraftNode {
    private final String raw;

    public Scalar(String raw) {
      this.raw = Objects.requireNonNull(raw);
    }

    public String raw() {
      return raw;
    }

    @Override
    public Size size() {
      return new Size(1, 1, raw.length());
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Scalar scalar && raw.equals(scalar.raw);
    }

    @Override
    public int hashCode() {
      return raw.hashCode();
    }
  }

  final class ObjectValue implements DraftNode {
    private final Map<String, DraftNode> fields;
    private final Size size;

    public ObjectValue(Map<String, DraftNode> fields) {
      var copy = new LinkedHashMap<String, DraftNode>();
      fields.forEach(
          (key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
      this.fields = Collections.unmodifiableMap(copy);
      var measured =
          measure(
              copy.values(),
              copy.keySet().stream().mapToInt(String::length).reduce(0, Math::addExact),
              0);
      size = measured;
    }

    public Map<String, DraftNode> fields() {
      return fields;
    }

    public ObjectValue with(String field, DraftNode value) {
      var copy = new LinkedHashMap<>(fields);
      copy.put(field, value);
      return new ObjectValue(copy);
    }

    public ObjectValue without(String field) {
      var copy = new LinkedHashMap<>(fields);
      copy.remove(field);
      return new ObjectValue(copy);
    }

    @Override
    public Size size() {
      return size;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ObjectValue value && fields.equals(value.fields);
    }

    @Override
    public int hashCode() {
      return fields.hashCode();
    }
  }

  record Row(UUID id, DraftNode value) {
    public Row {
      Objects.requireNonNull(id);
      Objects.requireNonNull(value);
    }

    public static Row create(DraftNode value) {
      return new Row(UUID.randomUUID(), value);
    }
  }

  final class Sequence implements DraftNode {
    private final List<Row> rows;
    private final Size size;

    public Sequence(List<Row> rows) {
      this.rows = List.copyOf(rows);
      var seen = new HashSet<UUID>();
      for (var row : rows)
        if (!seen.add(row.id())) throw new IllegalArgumentException("Duplicate session row ID");
      size = measure(rows.stream().map(Row::value).toList(), 0, rows.size());
    }

    public List<Row> rows() {
      return rows;
    }

    public Sequence insert(int index, Row row) {
      var copy = new ArrayList<>(rows);
      copy.add(index, row);
      return new Sequence(copy);
    }

    public Sequence remove(UUID id) {
      var copy = new ArrayList<>(rows);
      copy.remove(index(id));
      return new Sequence(copy);
    }

    public Sequence move(UUID id, int target) {
      if (target < 0 || target >= rows.size()) throw new IndexOutOfBoundsException(target);
      var copy = new ArrayList<>(rows);
      var row = copy.remove(index(id));
      copy.add(target, row);
      return new Sequence(copy);
    }

    public int index(UUID id) {
      for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) return i;
      throw new IllegalArgumentException("Row no longer exists in this draft");
    }

    public Sequence replace(UUID id, DraftNode value) {
      var copy = new ArrayList<>(rows);
      int index = index(id);
      copy.set(index, new Row(id, value));
      return new Sequence(copy);
    }

    @Override
    public Size size() {
      return size;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Sequence sequence && rows.equals(sequence.rows);
    }

    @Override
    public int hashCode() {
      return rows.hashCode();
    }
  }

  final class Opaque implements DraftNode {
    private final String json;

    public Opaque(String json) {
      this.json = Objects.requireNonNull(json);
    }

    public String json() {
      return json;
    }

    @Override
    public Size size() {
      return new Size(1, 1, json.length());
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Opaque opaque && json.equals(opaque.json);
    }

    @Override
    public int hashCode() {
      return json.hashCode();
    }
  }

  record Missing() implements DraftNode {
    @Override
    public Size size() {
      return new Size(1, 1, 0);
    }
  }

  record NullValue() implements DraftNode {
    @Override
    public Size size() {
      return new Size(1, 1, 4);
    }
  }

  private static Size measure(Iterable<DraftNode> children, int characters, int rowCount) {
    int depth = 1, nodes = Math.addExact(1, rowCount);
    for (var child : children) {
      depth = Math.max(depth, Math.addExact(1, child.size().depth()));
      nodes = Math.addExact(nodes, child.size().nodes());
      characters = Math.addExact(characters, child.size().characters());
    }
    return new Size(depth, nodes, characters);
  }

  /** Authored equality ignores session IDs; identical user data must not become a fake edit. */
  static boolean sameValue(DraftNode first, DraftNode second) {
    if (first instanceof Sequence a && second instanceof Sequence b) {
      if (a.rows.size() != b.rows.size()) return false;
      for (int i = 0; i < a.rows.size(); i++)
        if (!sameValue(a.rows.get(i).value(), b.rows.get(i).value())) return false;
      return true;
    }
    if (first instanceof ObjectValue a && second instanceof ObjectValue b) {
      if (!a.fields.keySet().equals(b.fields.keySet())) return false;
      return a.fields.entrySet().stream()
          .allMatch(entry -> sameValue(entry.getValue(), b.fields.get(entry.getKey())));
    }
    return first.equals(second);
  }
}
