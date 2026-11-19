package design.inherent.reform.presentation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Pure, complete catalog projections over an existing draft; labels and navigation own no edits.
 */
public final class FormCatalog {
  public record Limits(
      int maximumEntries,
      int maximumLabelCharacters,
      int maximumTotalLabelCharacters,
      int maximumQueryCharacters,
      int maximumHistoryEntries) {
    public static final Limits STANDARD = new Limits(512, 32768, 131072, 256, 64);

    public Limits {
      if (maximumEntries < 1
          || maximumLabelCharacters < 1
          || maximumTotalLabelCharacters < maximumLabelCharacters
          || maximumQueryCharacters < 1
          || maximumHistoryEntries < 1)
        throw new IllegalArgumentException("Invalid catalog admission limits");
    }
  }

  /** An actual declared section; captions do not rename or grant access to its data. */
  public record Section(FieldPath path, String translationKey, String label, String help) {
    public Section {
      Objects.requireNonNull(path);
      if (path.segments().size() > 128
          || path.segments().stream().anyMatch(FieldPath.Row.class::isInstance)
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(label).isBlank()
          || label.length() > 1024
          || Objects.requireNonNull(help).length() > 4096)
        throw new IllegalArgumentException(
            "Catalog sections require bounded declared identities/captions");
    }
  }

  /** Called only while building an admitted projection, never while rendering or filtering. */
  @FunctionalInterface
  public interface RowLabels {
    Optional<String> label(FormModel.Node member);
  }

  public record Entry(
      FieldPath path,
      Optional<FieldPath> parent,
      Optional<Section> section,
      Optional<FormModel.Node> node,
      Optional<String> caption,
      String label) {
    public Entry {
      Objects.requireNonNull(path);
      Objects.requireNonNull(parent);
      Objects.requireNonNull(section);
      Objects.requireNonNull(node);
      Objects.requireNonNull(caption);
      Objects.requireNonNull(label);
      if (parent.filter(value -> value.equals(path) || !path.startsWith(value)).isPresent()
          || section.filter(value -> !value.path().equals(path)).isPresent()
          || node.filter(value -> !value.path().equals(path)).isPresent())
        throw new IllegalArgumentException("Catalog entries retain actual structural identities");
    }

    public boolean available() {
      return node.isPresent();
    }
  }

  public static final FormCatalog NONE = new FormCatalog(List.of());
  private final List<Section> sections;
  private final Map<FieldPath, Section> declared;
  private final Limits limits;
  private final RowLabels rowLabels;

  public FormCatalog(List<Section> sections) {
    this(sections, Limits.STANDARD, ignored -> Optional.empty());
  }

  public FormCatalog(List<Section> sections, Limits limits, RowLabels rowLabels) {
    this.sections = List.copyOf(sections);
    this.limits = Objects.requireNonNull(limits);
    this.rowLabels = Objects.requireNonNull(rowLabels);
    if (sections.size() > Math.min(128, limits.maximumEntries()))
      throw new IllegalArgumentException("Catalog section admission exhausted");
    var unique = new LinkedHashMap<FieldPath, Section>();
    for (var section : this.sections)
      if (unique.putIfAbsent(section.path(), section) != null)
        throw new IllegalArgumentException("A catalog section is already declared at this path");
    declared = Collections.unmodifiableMap(unique);
  }

  public boolean enabled() {
    return !sections.isEmpty();
  }

  public Limits limits() {
    return limits;
  }

  public List<Section> sections() {
    return sections;
  }

  /** Inspect declarations without codecs, default construction, IO or domain resolution. */
  public void requireCompatible(ValueType<?> root) {
    for (var section : sections) DraftTypes.declaredAt(root, section.path());
  }

  public Snapshot snapshot(FormModel model) {
    requireCompatible(model.root().type().orElseThrow());
    var builder = new Builder();
    builder.add(model.root(), Optional.empty());
    builder.walk(model.root(), FieldPath.ROOT, declared.containsKey(FieldPath.ROOT));
    for (var section : sections)
      if (!builder.entries.containsKey(section.path())) {
        var parent = nearest(builder.entries, section.path());
        builder.add(
            new Entry(
                section.path(),
                Optional.of(parent.path()),
                Optional.of(section),
                Optional.empty(),
                Optional.empty(),
                section.label()));
      }
    return new Snapshot(builder.entries, limits);
  }

  /** Immutable complete result; exhausted projections are refused before publication. */
  public static final class Snapshot {
    private final Map<FieldPath, Entry> entries;
    private final Map<FieldPath, String> search;
    private final Limits limits;

    private Snapshot(Map<FieldPath, Entry> entries, Limits limits) {
      this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
      this.limits = limits;
      var index = new LinkedHashMap<FieldPath, String>();
      long total = 0;
      if (entries.size() > limits.maximumEntries() || !entries.containsKey(FieldPath.ROOT))
        throw new IllegalArgumentException("Catalog entries exceed their complete admission");
      for (var entry : entries.values()) {
        total += entry.label().length();
        if (entry.label().length() > limits.maximumLabelCharacters()
            || total > limits.maximumTotalLabelCharacters()
            || entry.parent().filter(parent -> !entries.containsKey(parent)).isPresent())
          throw new IllegalArgumentException("Catalog labels/parents exceed their admission");
        index.put(entry.path(), entry.label().toLowerCase(Locale.ROOT));
      }
      search = Collections.unmodifiableMap(index);
    }

    public List<Entry> entries() {
      return List.copyOf(entries.values());
    }

    public Optional<Entry> entry(FieldPath path) {
      return Optional.ofNullable(entries.get(Objects.requireNonNull(path)));
    }

    public Entry nearest(FieldPath path) {
      return FormCatalog.nearest(entries, Objects.requireNonNull(path));
    }

    public List<Entry> breadcrumbs(FieldPath path) {
      var chain = new ArrayList<Entry>();
      var entry = nearest(path);
      while (true) {
        chain.add(entry);
        if (entry.parent().isEmpty()) break;
        entry = entries.get(entry.parent().orElseThrow());
      }
      Collections.reverse(chain);
      return List.copyOf(chain);
    }

    /** Matching labels only; selection and breadcrumbs remain independent of the filter. */
    public List<Entry> search(String query) {
      if (Objects.requireNonNull(query).length() > limits.maximumQueryCharacters())
        throw new IllegalArgumentException("Catalog query admission exhausted");
      String folded = query.strip().toLowerCase(Locale.ROOT);
      return entries.values().stream()
          .filter(entry -> search.get(entry.path()).contains(folded))
          .toList();
    }

    /**
     * Capture bounded localized labels once; native frontends rebuild on actual resource reload.
     */
    public Snapshot relabel(Function<Entry, String> labels) {
      Objects.requireNonNull(labels);
      var updated = new LinkedHashMap<FieldPath, Entry>();
      long total = 0;
      for (var entry : entries.values()) {
        String label = Objects.requireNonNull(labels.apply(entry));
        total += label.length();
        if (label.length() > limits.maximumLabelCharacters()
            || total > limits.maximumTotalLabelCharacters())
          throw new IllegalArgumentException("Localized catalog labels exceed their admission");
        updated.put(
            entry.path(),
            new Entry(
                entry.path(),
                entry.parent(),
                entry.section(),
                entry.node(),
                entry.caption(),
                label));
      }
      return new Snapshot(updated, limits);
    }
  }

  /** Bounded history retains requested row identities through removal, undo and branch changes. */
  public static final class Navigation {
    private Snapshot snapshot;
    private final List<FieldPath> history = new ArrayList<>(List.of(FieldPath.ROOT));
    private int cursor;

    public Navigation(Snapshot snapshot) {
      this.snapshot = Objects.requireNonNull(snapshot);
    }

    public void update(Snapshot snapshot) {
      if (!this.snapshot.limits.equals(Objects.requireNonNull(snapshot).limits))
        throw new IllegalArgumentException("A navigation lifetime retains its admission limits");
      this.snapshot = snapshot;
    }

    public Entry selected() {
      return snapshot.nearest(history.get(cursor));
    }

    public FieldPath requested() {
      return history.get(cursor);
    }

    public void select(FieldPath path) {
      if (snapshot.entry(path).isEmpty())
        throw new IllegalArgumentException("Select an existing catalog identity");
      if (history.get(cursor).equals(path)) return;
      history.subList(cursor + 1, history.size()).clear();
      history.add(path);
      if (history.size() > snapshot.limits.maximumHistoryEntries()) history.removeFirst();
      cursor = history.size() - 1;
    }

    public boolean canBack() {
      return cursor > 0;
    }

    public boolean canForward() {
      return cursor + 1 < history.size();
    }

    public void back() {
      if (canBack()) cursor--;
    }

    public void forward() {
      if (canForward()) cursor++;
    }
  }

  private static Entry nearest(Map<FieldPath, Entry> entries, FieldPath path) {
    var selected = entries.get(FieldPath.ROOT);
    for (var entry : entries.values())
      if (path.startsWith(entry.path())
          && entry.path().segments().size() > selected.path().segments().size()) selected = entry;
    return selected;
  }

  private final class Builder {
    final Map<FieldPath, Entry> entries = new LinkedHashMap<>();
    private long characters;

    void add(Entry entry) {
      characters += entry.label().length();
      if (entries.size() >= limits.maximumEntries()
          || entry.label().length() > limits.maximumLabelCharacters()
          || characters > limits.maximumTotalLabelCharacters()
          || entries.putIfAbsent(entry.path(), entry) != null)
        throw new IllegalArgumentException("Catalog projection exceeds its complete admission");
    }

    void add(FormModel.Node node, Optional<FieldPath> parent) {
      if (entries.size() >= limits.maximumEntries())
        throw new IllegalArgumentException("Catalog entry admission exhausted before labels");
      var section = Optional.ofNullable(declared.get(node.path()));
      Optional<String> caption = Optional.empty();
      if (node.role() == FormModel.Role.ROW || node.role() == FormModel.Role.MAP_ROW) {
        caption = Objects.requireNonNull(rowLabels.label(node));
        if (caption.isEmpty() && node.role() == FormModel.Role.MAP_ROW)
          caption =
              node.children().stream()
                  .filter(child -> child.role() == FormModel.Role.KEY)
                  .map(FormModel.Node::input)
                  .filter(DraftNode.Scalar.class::isInstance)
                  .map(DraftNode.Scalar.class::cast)
                  .map(DraftNode.Scalar::raw)
                  .findFirst();
      }
      String label =
          section.isPresent()
              ? section.orElseThrow().label()
              : node.path().equals(FieldPath.ROOT) ? "All settings" : captionLabel(node, caption);
      add(new Entry(node.path(), parent, section, Optional.of(node), caption, label));
    }

    void walk(FormModel.Node node, FieldPath parent, boolean inside) {
      for (var child : node.children()) {
        boolean explicit = declared.containsKey(child.path());
        boolean included = explicit || inside && boundary(child);
        FieldPath next = parent;
        if (included) {
          add(child, Optional.of(parent));
          next = child.path();
        }
        walk(child, next, inside || explicit);
      }
    }
  }

  private static String captionLabel(FormModel.Node node, Optional<String> caption) {
    if (caption.isPresent()) return caption.orElseThrow();
    return node.role() == FormModel.Role.ROW || node.role() == FormModel.Role.MAP_ROW
        ? "Entry " + node.name()
        : node.name();
  }

  private static boolean boundary(FormModel.Node node) {
    if (node.role() == FormModel.Role.ROW
        || node.role() == FormModel.Role.MAP_ROW
        || node.role() == FormModel.Role.VARIANT) return true;
    if (node.role() != FormModel.Role.FIELD || node.type().isEmpty()) return false;
    var shape = node.type().orElseThrow().shape();
    return !(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>);
  }
}
