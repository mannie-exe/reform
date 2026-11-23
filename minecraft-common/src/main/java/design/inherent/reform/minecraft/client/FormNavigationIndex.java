package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormComposition;
import design.inherent.reform.presentation.FormModel;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.FormTarget;
import design.inherent.reform.value.ValueShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Bounded cached section/field metadata; native navigation and widgets belong to the workspace. */
final class FormNavigationIndex {
  record FieldMatch(FieldPath path, String label, String help, String search) {}

  record Target(
      FormTarget id,
      Optional<FormTarget> parent,
      FieldPath scope,
      Component title,
      Component help,
      boolean searchOnly,
      String search) {}

  private final FormCatalog definition;
  private final FormPresentation presentation;
  private final DocumentId document;
  private FormCatalog.Snapshot projection;
  private List<FieldMatch> fields = List.of();
  private List<Target> targets = List.of();
  private boolean fieldsUnavailable, forceAll;
  private FieldPath selection;
  private FormModel model;
  private Language language;
  private UUID session;
  private long generation;
  private Component failure;

  FormNavigationIndex(FormPresentation presentation, DocumentId document) {
    this.presentation = Objects.requireNonNull(presentation);
    this.definition = presentation.catalog();
    this.document = Objects.requireNonNull(document);
  }

  /** Report metadata changes without reading an owner, decoding values or creating widgets. */
  boolean project(FormModel model, UUID session, long generation) {
    var currentLanguage = Language.getInstance();
    if (this.model == model
        && this.language == currentLanguage
        && Objects.equals(this.session, session)
        && this.generation == generation) return false;
    var previous = selectedPath();
    var previousFailure = failure;
    boolean hadProjection = projection != null;
    if (!Objects.equals(this.session, session) || this.generation != generation) selection = null;
    this.model = model;
    this.session = session;
    this.generation = generation;
    language = currentLanguage;
    projection = null;
    fields = List.of();
    targets = List.of();
    fieldsUnavailable = false;
    failure = null;
    if (model != null) {
      try {
        projection = definition.snapshot(model, presentation).relabel(this::label);
        try {
          fields = indexFields(model);
        } catch (IllegalArgumentException unadmitted) {
          fieldsUnavailable = true;
        }
        targets = indexTargets(model);
        if (selection == null)
          selection =
              definition.sections().stream()
                  .findFirst()
                  .map(FormCatalog.Section::path)
                  .orElse(FieldPath.ROOT);
        selection = projection.nearest(selection).path();
      } catch (RuntimeException unavailable) {
        projection = null;
        fields = List.of();
        targets = List.of();
        failure =
            Component.literal(
                visible(
                    Objects.toString(
                        unavailable.getMessage(), "The complete catalog is unavailable"),
                    256));
      }
    }
    return hadProjection != (projection != null)
        || !previous.equals(selectedPath())
        || !Objects.equals(previousFailure, failure);
  }

  private Optional<FormCatalog.Entry> selected() {
    return projection == null
        ? Optional.empty()
        : forceAll ? projection.entry(FieldPath.ROOT) : projection.entry(selection);
  }

  List<Target> targets() {
    return targets;
  }

  int maximumTargets() {
    return (int)
        Math.min(
            Integer.MAX_VALUE,
            2L * definition.limits().maximumEntries()
                + presentation.layout().groups().size()
                + presentation.ranges().size()
                + 1);
  }

  Optional<Component> searchProblem() {
    if (failure != null) return Optional.of(failure.copy());
    return fieldsUnavailable
        ? Optional.of(text("catalog_field_limit", "Complete field search exceeds its limit."))
        : Optional.empty();
  }

  boolean allFields() {
    return forceAll;
  }

  void showAllFields() {
    forceAll = true;
  }

  Optional<FieldPath> selectedPath() {
    return selected().map(FormCatalog.Entry::path);
  }

  Optional<FormCatalog.Entry> nearest(FieldPath path) {
    return projection == null ? Optional.empty() : Optional.of(projection.nearest(path));
  }

  boolean contains(FieldPath path) {
    return projection != null && projection.entry(path).isPresent();
  }

  Optional<FormModel.Node> parent() {
    return selected()
        .flatMap(FormCatalog.Entry::parent)
        .flatMap(path -> projection.entry(path))
        .flatMap(FormCatalog.Entry::node);
  }

  boolean select(FieldPath path) {
    if (!contains(path)) return false;
    selection = path;
    forceAll = false;
    return true;
  }

  Component caption(FieldPath path) {
    return projection == null
        ? Component.empty()
        : projection
            .entry(path)
            .map(entry -> Component.literal(visible(entry.label(), 160)))
            .orElse(Component.empty());
  }

  Component unavailable() {
    return failure == null
        ? text("catalog_inactive", "This section is absent or inactive in the current draft.")
        : text("catalog_unavailable", "Catalog unavailable: ").copy().append(failure);
  }

  Optional<Component> help() {
    return selected().stream()
        .flatMap(entry -> projection.breadcrumbs(entry.path()).reversed().stream())
        .flatMap(entry -> entry.section().stream())
        .filter(CatalogHelp::present)
        .findFirst()
        .map(CatalogHelp::describe);
  }

  Component narration() {
    var selected = selected();
    if (selected.isEmpty()) return unavailable();
    var entry = selected.orElseThrow();
    var result =
        Component.literal(
            String.join(
                " / ",
                projection.breadcrumbs(entry.path()).stream()
                    .map(FormCatalog.Entry::label)
                    .toList()));
    help().ifPresent(value -> result.append(". ").append(value));
    return entry.available() ? result : result.append(". ").append(unavailable());
  }

  void close() {
    projection = null;
    selection = null;
    model = null;
    fields = List.of();
    targets = List.of();
    fieldsUnavailable = false;
    failure = null;
  }

  private List<FieldMatch> indexFields(FormModel model) {
    var result = new ArrayList<FieldMatch>();
    var stack = new java.util.ArrayDeque<FormModel.Node>();
    stack.push(model.root());
    long characters = 0;
    var bounds = definition.limits();
    while (!stack.isEmpty()) {
      var node = stack.pop();
      for (var child :
          FormComposition.fields(FormComposition.resolve(node.children(), presentation)).reversed())
        stack.push(child);
      if (node.role() != FormModel.Role.FIELD || projection.entry(node.path()).isPresent())
        continue;
      var shape = node.type().orElseThrow().shape();
      if (!(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>))
        continue;
      String label =
          FieldLabels.input(document, node.path(), Component.literal(node.name())).getString();
      String helpKey = FieldLabels.key(document, node.path()) + ".help";
      String help = language.has(helpKey) ? Component.translatable(helpKey).getString() : "";
      String search = (label + " " + node.name() + " " + help).toLowerCase(Locale.ROOT);
      characters += search.length();
      if (result.size() >= bounds.maximumEntries()
          || search.length() > bounds.maximumLabelCharacters()
          || characters > bounds.maximumTotalLabelCharacters())
        throw new IllegalArgumentException("Localized field search exceeds its admission");
      result.add(new FieldMatch(node.path(), label, help, search));
    }
    return List.copyOf(result);
  }

  private List<Target> indexTargets(FormModel model) {
    var paths = new java.util.LinkedHashSet<FieldPath>();
    projection.entries().forEach(entry -> paths.add(entry.path()));
    fields.forEach(field -> paths.add(field.path()));
    var matches = new java.util.HashMap<FieldPath, FieldMatch>();
    fields.forEach(field -> matches.put(field.path(), field));
    var graph =
        FormComposition.navigation(model, presentation, List.copyOf(paths), maximumTargets());
    var result = new ArrayList<Target>();
    for (var entry : graph.nodes()) {
      if (entry.id() instanceof FormTarget.Field field) {
        var known = projection.entry(field.path());
        var match = matches.get(field.path());
        Component title =
            Component.literal(known.isPresent() ? known.orElseThrow().label() : match.label());
        Component help =
            known.isPresent()
                ? known.orElseThrow().section().map(CatalogHelp::describe).orElse(Component.empty())
                : Component.literal(match.help());
        result.add(
            new Target(
                field,
                entry.parent(),
                field.path(),
                title,
                help,
                known.isEmpty(),
                match == null ? title.getString() + " " + help.getString() : match.search()));
      } else {
        var box = (FormComposition.Box) entry.target().orElseThrow();
        var title = Component.translatableWithFallback(box.translationKey(), box.label());
        var help =
            language.has(box.translationKey() + ".help") || !box.help().isBlank()
                ? Component.translatableWithFallback(box.translationKey() + ".help", box.help())
                : Component.empty();
        var ancestors = graph.ancestry(entry.id());
        var scope =
            ancestors.stream()
                .map(design.inherent.reform.presentation.DestinationGraph.Node::id)
                .filter(FormTarget.Field.class::isInstance)
                .map(FormTarget.Field.class::cast)
                .map(FormTarget.Field::path)
                .reduce((first, last) -> last)
                .orElse(FieldPath.ROOT);
        result.add(
            new Target(
                entry.id(),
                entry.parent(),
                scope,
                title,
                help,
                false,
                title.getString() + " " + help.getString()));
      }
    }
    return List.copyOf(result);
  }

  private String label(FormCatalog.Entry entry) {
    if (entry.section().isPresent()) {
      var section = entry.section().orElseThrow();
      return Component.translatableWithFallback(section.translationKey(), section.label())
          .getString();
    }
    if (entry.caption().isPresent()) {
      var value = entry.caption().orElseThrow();
      return value.isBlank() ? text("catalog_blank", "(blank name)").getString() : value;
    }
    if (entry.path().equals(FieldPath.ROOT)) return text("catalog_all", "All settings").getString();
    var node = entry.node().orElseThrow();
    if (node.role() == FormModel.Role.ROW || node.role() == FormModel.Role.MAP_ROW)
      return Component.translatableWithFallback("reform.settings.entry", "Entry %s", node.name())
          .getString();
    if (node.role() == FormModel.Role.VARIANT)
      return Component.translatableWithFallback(
              FieldLabels.key(document, entry.path()), FieldLabels.readable(node.name()))
          .getString();
    return FieldLabels.input(document, entry.path(), Component.literal(entry.label())).getString();
  }

  static String visible(String value, int maximum) {
    var text = new StringBuilder();
    int examined = 0;
    for (int i = 0; i < value.length() && examined < maximum; examined++) {
      int cp = value.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '\n') text.append("\\n");
      else if (cp == '\r') text.append("\\r");
      else if (cp == '\t') text.append("\\t");
      else if (Character.isISOControl(cp)) text.append('?');
      else text.appendCodePoint(cp);
    }
    return text + (value.codePointCount(0, value.length()) > examined ? "…" : "");
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }
}
