package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.*;

/** Bounded authored theme scopes over existing form identities; no setting values or new owners. */
public final class FormTheme {
  public static final FormTheme EMPTY = new FormTheme(ThemePatch.empty(), List.of(), List.of());

  /** Static declarations, independent of how many fields a current draft projects. */
  public record Limits(int maximumSections, int maximumFields) {
    public static final Limits STANDARD = new Limits(256, 512);

    public Limits {
      if (maximumSections < 1 || maximumFields < 1)
        throw new IllegalArgumentException("Theme declaration limits must be positive");
    }
  }

  public enum SectionKind {
    GROUP,
    RANGE
  }

  public record Section(
      SectionKind kind, String id, ThemePatch patch, Optional<ComponentVariant> variant) {
    public Section {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(patch);
      Objects.requireNonNull(variant);
      if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9._/-]{0,127}"))
        throw new IllegalArgumentException("Invalid themed section identity");
      if (variant.isPresent()
          && variant.orElseThrow().kind() != ComponentVariant.Kind.GROUPED_SECTION)
        throw new IllegalArgumentException("A section needs a grouped-section variant");
    }

    public String boxId() {
      return (kind == SectionKind.GROUP ? "group:" : "range:") + id;
    }
  }

  public record Field(FieldPath path, ThemePatch patch, Optional<ComponentVariant> variant) {
    public Field {
      FormDependencies.path(path);
      Objects.requireNonNull(patch);
      Objects.requireNonNull(variant);
      if (path.equals(FieldPath.ROOT))
        throw new IllegalArgumentException("Use the root theme for the whole form");
      if (variant.isPresent()
          && (variant.orElseThrow().kind() == ComponentVariant.Kind.GROUPED_SECTION
              || variant.orElseThrow().kind() == ComponentVariant.Kind.DESTINATION_CHOICE))
        throw new IllegalArgumentException("This variant does not present a field");
    }
  }

  private final ThemePatch root;
  private final List<Section> sections;
  private final List<Field> fields;
  private final Map<String, Section> bySection;
  private final Map<FieldPath, Field> byField;
  private final Limits limits;

  public FormTheme(ThemePatch root, List<Section> sections, List<Field> fields) {
    this(root, sections, fields, Limits.STANDARD);
  }

  public FormTheme(ThemePatch root, List<Section> sections, List<Field> fields, Limits limits) {
    this.root = Objects.requireNonNull(root);
    this.limits = Objects.requireNonNull(limits);
    if (sections.size() > limits.maximumSections() || fields.size() > limits.maximumFields())
      throw new IllegalArgumentException("Theme scopes exceed their declared admission");
    this.sections = List.copyOf(sections);
    this.fields = List.copyOf(fields);
    var boxes = new LinkedHashMap<String, Section>();
    for (var section : sections)
      if (boxes.putIfAbsent(section.boxId(), section) != null)
        throw new IllegalArgumentException("Duplicate themed section");
    var paths = new LinkedHashMap<FieldPath, Field>();
    for (var field : fields)
      if (paths.putIfAbsent(field.path(), field) != null)
        throw new IllegalArgumentException("Duplicate themed field");
    bySection = Map.copyOf(boxes);
    byField = Map.copyOf(paths);
  }

  public ThemePatch root() {
    return root;
  }

  public Limits limits() {
    return limits;
  }

  public List<Section> sections() {
    return sections;
  }

  public List<Field> fields() {
    return fields;
  }

  public Optional<Section> section(String boxId) {
    return Optional.ofNullable(bySection.get(boxId));
  }

  public Optional<Field> field(FieldPath path) {
    return Optional.ofNullable(byField.get(path));
  }

  public FormTheme withRoot(ThemePatch patch) {
    return new FormTheme(patch, sections, fields, limits);
  }

  /** Replace metadata admission without widening catalogue, draft, or owner bounds. */
  public FormTheme withLimits(Limits limits) {
    return new FormTheme(root, sections, fields, limits);
  }

  public FormTheme withGroup(String id, ThemePatch patch) {
    return withSection(new Section(SectionKind.GROUP, id, patch, Optional.empty()));
  }

  public FormTheme withSection(Section section) {
    var next = new ArrayList<>(sections);
    next.add(section);
    return new FormTheme(root, next, fields, limits);
  }

  public FormTheme withField(FieldPath path, ThemePatch patch) {
    return withField(new Field(path, patch, Optional.empty()));
  }

  public FormTheme withField(Field field) {
    var next = new ArrayList<>(fields);
    next.add(field);
    return new FormTheme(root, sections, next, limits);
  }

  void requireSections(FormLayout layout, List<FormPresentation.Range> ranges) {
    for (var section : sections) {
      boolean found =
          section.kind() == SectionKind.GROUP
              ? layout.groups().stream().anyMatch(group -> group.id().equals(section.id()))
              : ranges.stream().anyMatch(range -> range.id().equals(section.id()));
      if (!found)
        throw new IllegalArgumentException("Themed section is not declared: " + section.id());
    }
  }

  void requireCompatible(ValueType<?> type) {
    for (var field : fields) {
      var shape = DraftTypes.declaredAt(type, field.path()).shape();
      while (true) {
        if (shape instanceof ValueShape.OptionalValue<?> optional)
          shape = optional.element().shape();
        else if (shape instanceof ValueShape.Defaulted<?> defaulted)
          shape = defaulted.element().shape();
        else break;
      }
      if (field.variant().filter(v -> v.kind() == ComponentVariant.Kind.TITLE_REFERENCE).isPresent()
          && !(shape instanceof ValueShape.Reference<?>))
        throw new IllegalArgumentException(
            "A title-reference variant needs a declared reference: " + field.path());
    }
  }
}
