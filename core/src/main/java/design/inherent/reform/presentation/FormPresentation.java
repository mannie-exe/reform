package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit local form relationships; storage, validation and effects stay owner-declared. */
public final class FormPresentation {
  public static final FormPresentation EMPTY = new FormPresentation(List.of());

  /** Two sibling numeric leaves. The owner's complete validator owns their actual invariant. */
  public record Range(
      String id,
      String translationKey,
      String label,
      String help,
      FieldPath minimum,
      FieldPath maximum) {
    public Range {
      if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9._/-]{0,127}")
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(label).isBlank()
          || label.length() > 1024
          || Objects.requireNonNull(help).length() > 4096)
        throw new IllegalArgumentException("Range identity/captions must be bounded");
      Objects.requireNonNull(minimum);
      Objects.requireNonNull(maximum);
      var a = minimum.segments();
      var b = maximum.segments();
      if (a.isEmpty()
          || a.size() > 128
          || a.size() != b.size()
          || minimum.equals(maximum)
          || !(a.getLast() instanceof FieldPath.Field)
          || !(b.getLast() instanceof FieldPath.Field)
          || !a.subList(0, a.size() - 1).equals(b.subList(0, b.size() - 1)))
        throw new IllegalArgumentException("A range needs two distinct sibling field identities");
    }
  }

  private final List<Range> ranges;
  private final Map<FieldPath, Range> endpoints;

  public FormPresentation(List<Range> ranges) {
    this.ranges = List.copyOf(ranges);
    if (ranges.size() > 128) throw new IllegalArgumentException("Too many form relationships");
    var ids = new HashSet<String>();
    var fields = new LinkedHashMap<FieldPath, Range>();
    for (var range : this.ranges) {
      if (!ids.add(range.id())
          || fields.putIfAbsent(range.minimum(), range) != null
          || fields.putIfAbsent(range.maximum(), range) != null)
        throw new IllegalArgumentException("Range identity or endpoint is already registered");
    }
    endpoints = Map.copyOf(fields);
  }

  public List<Range> ranges() {
    return ranges;
  }

  public Optional<Range> range(FieldPath endpoint) {
    return Optional.ofNullable(endpoints.get(endpoint));
  }

  /**
   * Checks declared shapes only, without decoding defaults, reading a source or constructing
   * values.
   */
  public void requireCompatible(ValueType<?> root) {
    for (var range : ranges) {
      requireNumeric(DraftTypes.declaredAt(root, range.minimum()));
      requireNumeric(DraftTypes.declaredAt(root, range.maximum()));
    }
  }

  private static void requireNumeric(ValueType<?> type) {
    if (!(type.shape() instanceof ValueShape.Scalar scalar)
        || scalar.kind() == ValueShape.ScalarKind.BOOLEAN
        || scalar.kind() == ValueShape.ScalarKind.TEXT
        || scalar.kind() == ValueShape.ScalarKind.CHOICE)
      throw new IllegalArgumentException("Range endpoints need declared numeric scalar fields");
  }
}
