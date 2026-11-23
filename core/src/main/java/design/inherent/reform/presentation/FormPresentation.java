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
import java.util.Set;
import java.util.stream.Collectors;

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

  /** Owner-observed context availability for a declared field and its descendants. */
  public record UnavailableField(FieldPath field, String translationKey, String fallback) {
    public UnavailableField {
      FormDependencies.path(field);
      if (field.equals(FieldPath.ROOT)
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(fallback).isBlank()
          || fallback.length() > 4096)
        throw new IllegalArgumentException("Unavailable fields need a bounded path and reason");
    }
  }

  private final List<Range> ranges;
  private final Map<FieldPath, Range> endpoints;
  private final FormDependencies dependencies;
  private final FormCatalog catalog;
  private final FormLayout layout;
  private final List<UnavailableField> unavailableFields;
  private final Set<FieldPath> unavailablePaths;

  public FormPresentation(List<Range> ranges) {
    this(ranges, FormDependencies.NONE);
  }

  public FormPresentation(List<Range> ranges, FormDependencies dependencies) {
    this(ranges, dependencies, FormCatalog.NONE);
  }

  public FormPresentation(List<Range> ranges, FormDependencies dependencies, FormCatalog catalog) {
    this(ranges, dependencies, catalog, List.of(), FormLayout.AUTO);
  }

  private FormPresentation(
      List<Range> ranges,
      FormDependencies dependencies,
      FormCatalog catalog,
      List<UnavailableField> unavailableFields,
      FormLayout layout) {
    this.ranges = List.copyOf(ranges);
    this.dependencies = Objects.requireNonNull(dependencies);
    this.catalog = Objects.requireNonNull(catalog);
    this.layout = Objects.requireNonNull(layout);
    if (ranges.size() > 128) throw new IllegalArgumentException("Too many form relationships");
    if (unavailableFields.size() > 128)
      throw new IllegalArgumentException("Too many unavailable fields");
    this.unavailableFields = List.copyOf(unavailableFields);
    unavailablePaths =
        this.unavailableFields.stream()
            .map(UnavailableField::field)
            .collect(Collectors.toUnmodifiableSet());
    if (unavailablePaths.size() != this.unavailableFields.size())
      throw new IllegalArgumentException("Duplicate unavailable field declaration");
    var ids = new HashSet<String>();
    var fields = new LinkedHashMap<FieldPath, Range>();
    for (var range : this.ranges) {
      if (!ids.add(range.id())
          || fields.putIfAbsent(range.minimum(), range) != null
          || fields.putIfAbsent(range.maximum(), range) != null)
        throw new IllegalArgumentException("Range identity or endpoint is already registered");
    }
    endpoints = Map.copyOf(fields);
    for (var row : layout.rows())
      for (var field : row.fields())
        if (endpoints.containsKey(field))
          throw new IllegalArgumentException("A range already declares this field's related row");
    for (var range : ranges)
      if (!layout.group(range.minimum()).equals(layout.group(range.maximum())))
        throw new IllegalArgumentException("Range endpoints cannot cross layout group boundaries");
  }

  public List<Range> ranges() {
    return ranges;
  }

  public Optional<Range> range(FieldPath endpoint) {
    return Optional.ofNullable(endpoints.get(endpoint));
  }

  public FormDependencies dependencies() {
    return dependencies;
  }

  public FormCatalog catalog() {
    return catalog;
  }

  public FormLayout layout() {
    return layout;
  }

  public FormPresentation withLayout(FormLayout layout) {
    return new FormPresentation(ranges, dependencies, catalog, unavailableFields, layout);
  }

  public FormPresentation withCatalog(FormCatalog catalog) {
    return new FormPresentation(ranges, dependencies, catalog, unavailableFields, layout);
  }

  /**
   * Compose a bounded immutable observation for this form. The actual owner still guards effects.
   */
  public FormPresentation withUnavailableFields(List<UnavailableField> unavailable) {
    return new FormPresentation(ranges, dependencies, catalog, unavailable, layout);
  }

  public Optional<UnavailableField> unavailableAt(FieldPath path) {
    Objects.requireNonNull(path);
    UnavailableField nearest = null;
    for (var entry : unavailableFields) {
      if (path.startsWith(entry.field())
          && (nearest == null
              || entry.field().segments().size() > nearest.field().segments().size()))
        nearest = entry;
    }
    return Optional.ofNullable(nearest);
  }

  /**
   * Checks declared shapes only, without decoding defaults, reading a source or constructing
   * values.
   */
  public void requireCompatible(ValueType<?> root) {
    bind(root);
  }

  public FormDependencies.Bound bind(ValueType<?> root) {
    catalog.requireCompatible(root);
    layout.requireCompatible(root);
    for (var range : ranges) {
      requireNumeric(DraftTypes.declaredAt(root, range.minimum()));
      requireNumeric(DraftTypes.declaredAt(root, range.maximum()));
    }
    for (var path : unavailablePaths) DraftTypes.declaredAt(root, path);
    return dependencies.bind(root, unavailablePaths);
  }

  private static void requireNumeric(ValueType<?> type) {
    if (!(type.shape() instanceof ValueShape.Scalar scalar)
        || scalar.kind() == ValueShape.ScalarKind.BOOLEAN
        || scalar.kind() == ValueShape.ScalarKind.TEXT
        || scalar.kind() == ValueShape.ScalarKind.CHOICE)
      throw new IllegalArgumentException("Range endpoints need declared numeric scalar fields");
  }
}
