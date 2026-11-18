package design.inherent.reform.edit;

import design.inherent.reform.presentation.NumericDisplay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable raw drafts. Row identity is retained for edits but is never serialized as user data.
 */
public sealed interface DraftNode
    permits DraftNode.Scalar,
        DraftNode.ObjectValue,
        DraftNode.Sequence,
        DraftNode.Mapping,
        DraftNode.Defaulted,
        DraftNode.OptionalValue,
        DraftNode.Alternative,
        DraftNode.Opaque,
        DraftNode.Missing,
        DraftNode.NullValue {
  record Size(int depth, int nodes, int characters) {
    public long historyCost() {
      return (long) characters + 32L * nodes;
    }
  }

  Size size();

  /** Private editor input, never a persisted unit field or a supplied canonical value. */
  record UnitInput(String unit, String text, NumericDisplay.State state) {
    public UnitInput {
      if (!Objects.requireNonNull(unit).matches("[a-z][a-z0-9_-]{0,63}"))
        throw new IllegalArgumentException("Invalid unit input identity");
      Objects.requireNonNull(text);
      Objects.requireNonNull(state);
    }
  }

  final class Scalar implements DraftNode {
    private final String raw;
    private final Optional<UnitInput> unitInput;

    public Scalar(String raw) {
      this(raw, Optional.empty());
    }

    public Scalar(String raw, UnitInput input) {
      this(raw, Optional.of(Objects.requireNonNull(input)));
    }

    private Scalar(String raw, Optional<UnitInput> input) {
      this.raw = Objects.requireNonNull(raw);
      unitInput = input;
    }

    public String raw() {
      return raw;
    }

    public Optional<UnitInput> unitInput() {
      return unitInput;
    }

    @Override
    public Size size() {
      return new Size(
          1,
          unitInput.isEmpty() ? 1 : 2,
          unitInput
              .map(
                  value ->
                      Math.addExact(
                          raw.length(),
                          Math.addExact(value.unit().length(), value.text().length())))
              .orElse(raw.length()));
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Scalar scalar
          && raw.equals(scalar.raw)
          && unitInput.equals(scalar.unitInput);
    }

    @Override
    public int hashCode() {
      return Objects.hash(raw, unitInput);
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

  /** An inactive present-value draft is retained locally without becoming serialized membership. */
  record OptionalValue(boolean present, DraftNode value) implements DraftNode {
    public OptionalValue {
      Objects.requireNonNull(value);
    }

    public OptionalValue withPresent(boolean next) {
      return new OptionalValue(next, value);
    }

    public OptionalValue withValue(DraftNode next) {
      return new OptionalValue(present, next);
    }

    @Override
    public Size size() {
      return measure(List.of(value), 0, 0);
    }
  }

  /** Only the selected branch is authored; inactive branches remain bounded session state. */
  final class Alternative implements DraftNode {
    private final Scalar selector;
    private final ObjectValue branches;
    private final ObjectValue unknownFields;
    private final Size size;

    public Alternative(
        String selected, Map<String, DraftNode> branches, Map<String, DraftNode> unknownFields) {
      selector = new Scalar(selected);
      this.branches = new ObjectValue(branches);
      this.unknownFields = new ObjectValue(unknownFields);
      size = measure(List.of(selector, this.branches, this.unknownFields), 0, 0);
    }

    public String selected() {
      return selector.raw();
    }

    public Scalar selector() {
      return selector;
    }

    public Map<String, DraftNode> branches() {
      return branches.fields();
    }

    public Map<String, DraftNode> unknownFields() {
      return unknownFields.fields();
    }

    public DraftNode selectedValue() {
      return branches.fields().getOrDefault(selected(), new Missing());
    }

    public Alternative withSelector(String next) {
      return new Alternative(next, branches.fields(), unknownFields.fields());
    }

    public Alternative select(String id, DraftNode seed) {
      var next = new LinkedHashMap<>(branches.fields());
      next.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(seed));
      return new Alternative(id, next, unknownFields.fields());
    }

    public Alternative withBranch(String id, DraftNode value) {
      if (!branches.fields().containsKey(id))
        throw new IllegalArgumentException("Branch no longer exists in this draft");
      return new Alternative(selected(), branches.with(id, value).fields(), unknownFields.fields());
    }

    public Alternative discardInactive(String id) {
      if (selected().equals(id) || !branches.fields().containsKey(id))
        throw new IllegalArgumentException("Only a retained inactive branch can be discarded");
      return new Alternative(selected(), branches.without(id).fields(), unknownFields.fields());
    }

    public Alternative withoutUnknownField(String id) {
      if (!unknownFields.fields().containsKey(id))
        throw new IllegalArgumentException("Unknown field no longer exists");
      return new Alternative(selected(), branches.fields(), unknownFields.without(id).fields());
    }

    @Override
    public Size size() {
      return size;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Alternative value
          && selector.equals(value.selector)
          && branches.equals(value.branches)
          && unknownFields.equals(value.unknownFields);
    }

    @Override
    public int hashCode() {
      return Objects.hash(selector, branches, unknownFields);
    }
  }

  /**
   * Inactive override input is local draft state; omitted fields retain their declared fallback.
   */
  final class Defaulted implements DraftNode {
    private final boolean overridden;
    private final DraftNode value;
    private final Sequence retained;
    private final Size size;

    public Defaulted(boolean overridden, DraftNode value) {
      this(overridden, value, List.of());
    }

    public Defaulted(boolean overridden, DraftNode value, List<Row> retainedOverrides) {
      this.overridden = overridden;
      this.value = Objects.requireNonNull(value);
      retained = new Sequence(retainedOverrides);
      size = measure(List.of(value, retained), 0, 0);
    }

    public boolean overridden() {
      return overridden;
    }

    public DraftNode value() {
      return value;
    }

    public List<Row> retainedOverrides() {
      return retained.rows();
    }

    public Defaulted withOverride(boolean explicit) {
      return new Defaulted(explicit, value, retained.rows());
    }

    public Defaulted withValue(DraftNode next) {
      return new Defaulted(overridden, next, retained.rows());
    }

    public Defaulted retainOverride(DraftNode previous) {
      if (value.equals(previous)
          || retained.rows().stream().anyMatch(row -> row.value().equals(previous))) return this;
      return new Defaulted(
          overridden, value, retained.insert(retained.rows().size(), Row.create(previous)).rows());
    }

    public Defaulted restoreRetained(UUID id) {
      var previous = retained.rows().get(retained.index(id)).value();
      return new Defaulted(overridden, previous, retained.remove(id).rows()).retainOverride(value);
    }

    public Defaulted discardRetained(UUID id) {
      return new Defaulted(overridden, value, retained.remove(id).rows());
    }

    @Override
    public Size size() {
      return size;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Defaulted field
          && overridden == field.overridden
          && value.equals(field.value)
          && retained.equals(field.retained);
    }

    @Override
    public int hashCode() {
      return Objects.hash(overridden, value, retained);
    }
  }

  final class Sequence implements DraftNode {
    private final List<Row> rows;
    private final boolean unordered;
    private final Size size;

    public Sequence(List<Row> rows) {
      this(rows, false);
    }

    /** Set drafts retain duplicate rows, while authored equality ignores their display order. */
    public Sequence(List<Row> rows, boolean unordered) {
      this.rows = List.copyOf(rows);
      this.unordered = unordered;
      var seen = new HashSet<UUID>();
      for (var row : rows)
        if (!seen.add(row.id())) throw new IllegalArgumentException("Duplicate session row ID");
      size = measure(rows.stream().map(Row::value).toList(), 0, rows.size());
    }

    public List<Row> rows() {
      return rows;
    }

    public boolean unordered() {
      return unordered;
    }

    public Sequence insert(int index, Row row) {
      var copy = new ArrayList<>(rows);
      copy.add(index, row);
      return new Sequence(copy, unordered);
    }

    public Sequence remove(UUID id) {
      var copy = new ArrayList<>(rows);
      copy.remove(index(id));
      return new Sequence(copy, unordered);
    }

    public Sequence move(UUID id, int target) {
      if (target < 0 || target >= rows.size()) throw new IndexOutOfBoundsException(target);
      var copy = new ArrayList<>(rows);
      var row = copy.remove(index(id));
      copy.add(target, row);
      return new Sequence(copy, unordered);
    }

    public int index(UUID id) {
      for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(id)) return i;
      throw new IllegalArgumentException("Row no longer exists in this draft");
    }

    public Sequence replace(UUID id, DraftNode value) {
      var copy = new ArrayList<>(rows);
      int index = index(id);
      copy.set(index, new Row(id, value));
      return new Sequence(copy, unordered);
    }

    @Override
    public Size size() {
      return size;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Sequence sequence
          && unordered == sequence.unordered
          && rows.equals(sequence.rows);
    }

    @Override
    public int hashCode() {
      return 31 * rows.hashCode() + Boolean.hashCode(unordered);
    }
  }

  /**
   * Rows retain raw key/value objects, including invalid or duplicate keys, before a Map exists.
   */
  final class Mapping implements DraftNode {
    private final Sequence entries;

    public Mapping(List<Row> rows) {
      entries = new Sequence(rows);
    }

    public List<Row> rows() {
      return entries.rows();
    }

    public int index(UUID id) {
      return entries.index(id);
    }

    public Mapping insert(int index, Row row) {
      return new Mapping(entries.insert(index, row).rows());
    }

    public Mapping remove(UUID id) {
      return new Mapping(entries.remove(id).rows());
    }

    public Mapping move(UUID id, int target) {
      return new Mapping(entries.move(id, target).rows());
    }

    public Mapping replace(UUID id, DraftNode value) {
      return new Mapping(entries.replace(id, value).rows());
    }

    @Override
    public Size size() {
      return entries.size();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Mapping map && entries.equals(map.entries);
    }

    @Override
    public int hashCode() {
      return entries.hashCode();
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
    if (first instanceof Scalar a && second instanceof Scalar b) {
      boolean failed =
          a.unitInput().filter(value -> value.state() != NumericDisplay.State.EXACT).isPresent()
              || b.unitInput()
                  .filter(value -> value.state() != NumericDisplay.State.EXACT)
                  .isPresent();
      return failed ? a.equals(b) : a.raw().equals(b.raw());
    }
    if (first instanceof Alternative a && second instanceof Alternative b)
      return a.selected().equals(b.selected())
          && sameValue(a.selectedValue(), b.selectedValue())
          && sameValue(a.unknownFields, b.unknownFields);
    if (first instanceof OptionalValue a && second instanceof OptionalValue b)
      return a.present() == b.present() && (!a.present() || sameValue(a.value(), b.value()));
    if (first instanceof Defaulted a && second instanceof Defaulted b)
      return a.overridden() == b.overridden()
          && (!a.overridden() || sameValue(a.value(), b.value()));
    if (first instanceof Mapping a && second instanceof Mapping b) {
      return sameUnorderedRows(a.rows(), b.rows());
    }
    if (first instanceof Sequence a && second instanceof Sequence b) {
      if (a.unordered != b.unordered) return false;
      if (a.unordered) return sameUnorderedRows(a.rows, b.rows);
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

  private static boolean sameUnorderedRows(List<Row> first, List<Row> second) {
    if (first.size() != second.size()) return false;
    record Entry(DraftNode value) {
      @Override
      public boolean equals(Object other) {
        return other instanceof Entry entry && sameValue(value, entry.value);
      }

      @Override
      public int hashCode() {
        return authoredHash(value);
      }
    }
    var counts = new java.util.HashMap<Entry, Integer>();
    for (var row : first) counts.merge(new Entry(row.value()), 1, Integer::sum);
    for (var row : second) {
      var key = new Entry(row.value());
      var count = counts.get(key);
      if (count == null) return false;
      if (count == 1) counts.remove(key);
      else counts.put(key, count - 1);
    }
    return counts.isEmpty();
  }

  private static int authoredHash(DraftNode node) {
    if (node instanceof Alternative value)
      return Objects.hash(
          value.selected(), authoredHash(value.selectedValue()), authoredHash(value.unknownFields));
    if (node instanceof OptionalValue value)
      return value.present() ? 71 + authoredHash(value.value()) : 71;
    if (node instanceof Defaulted value)
      return value.overridden() ? 67 + authoredHash(value.value()) : 67;
    if (node instanceof Mapping map) {
      int hash = 17;
      for (var row : map.rows()) hash += authoredHash(row.value());
      return hash;
    }
    if (node instanceof Sequence sequence) {
      int hash = 31;
      for (var row : sequence.rows())
        hash =
            sequence.unordered
                ? hash + authoredHash(row.value())
                : 31 * hash + authoredHash(row.value());
      return hash;
    }
    if (node instanceof ObjectValue object) {
      int hash = 47;
      for (var entry : object.fields.entrySet())
        hash += entry.getKey().hashCode() ^ authoredHash(entry.getValue());
      return hash;
    }
    return node.hashCode();
  }
}
