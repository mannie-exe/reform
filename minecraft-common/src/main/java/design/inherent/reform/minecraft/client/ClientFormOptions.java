package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.reference.ReferenceActions;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Presentation over an actual editor binding. Factories receive the exact definition at opening.
 */
public record ClientFormOptions<T>(
    FormPresentation presentation,
    Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
    Function<DocumentDefinition<T>, FieldEditors<T>> editors,
    Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
    Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information,
    Optional<DefaultsPresentation> defaults,
    Map<DocumentId, ReferenceTitles> referenceTitles,
    Map<FieldPath, ClientControlAdapter> controls,
    Map<FieldPath, ClientEntryPresentation> entries,
    Map<FieldPath, ClientFieldComponent> components,
    Map<design.inherent.reform.presentation.FormTarget.Section, ClientSectionComponent<T>>
        sections) {
  /** Presentation of the whole-document Defaults action; it changes no owner operation. */
  public record DefaultsPresentation(Component label, Component help) {
    public DefaultsPresentation {
      label = Objects.requireNonNull(label).copy();
      help = Objects.requireNonNull(help).copy();
    }

    @Override
    public Component label() {
      return label.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  public ClientFormOptions {
    Objects.requireNonNull(presentation);
    references = Map.copyOf(references);
    Objects.requireNonNull(editors);
    Objects.requireNonNull(imports);
    Objects.requireNonNull(information);
    Objects.requireNonNull(defaults);
    referenceTitles = Map.copyOf(referenceTitles);
    controls = Map.copyOf(controls);
    entries = Map.copyOf(entries);
    components = Map.copyOf(components);
    Objects.requireNonNull(sections);
    if (sections.size() > 64) throw new IllegalArgumentException("Too many section contributions");
    sections = Map.copyOf(sections);
    var sectionFields = new java.util.HashSet<FieldPath>();
    for (var target : sections.keySet())
      for (var path : ClientSectionParts.paths(presentation, target))
        if (!sectionFields.add(path) || components.containsKey(path) || controls.containsKey(path))
          throw new IllegalArgumentException(
              "A field may have only one component or native adapter");
    if (components.size() > 256 || components.keySet().stream().anyMatch(controls::containsKey))
      throw new IllegalArgumentException(
          "Declare at most one native adapter or component per field");
    if (entries.size() > 256)
      throw new IllegalArgumentException("Too many field slot declarations");
    if (controls.size() > 256) throw new IllegalArgumentException("Too many custom field controls");
    if (referenceTitles.size() > 128)
      throw new IllegalArgumentException("Too many titled reference sources");
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information,
      Optional<DefaultsPresentation> defaults,
      Map<DocumentId, ReferenceTitles> referenceTitles,
      Map<FieldPath, ClientControlAdapter> controls,
      Map<FieldPath, ClientEntryPresentation> entries,
      Map<FieldPath, ClientFieldComponent> components) {
    this(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        controls,
        entries,
        components,
        Map.of());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information,
      Optional<DefaultsPresentation> defaults,
      Map<DocumentId, ReferenceTitles> referenceTitles,
      Map<FieldPath, ClientControlAdapter> controls,
      Map<FieldPath, ClientEntryPresentation> entries) {
    this(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        controls,
        entries,
        Map.of());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information,
      Optional<DefaultsPresentation> defaults,
      Map<DocumentId, ReferenceTitles> referenceTitles,
      Map<FieldPath, ClientControlAdapter> controls) {
    this(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        controls,
        Map.of());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information,
      Optional<DefaultsPresentation> defaults,
      Map<DocumentId, ReferenceTitles> referenceTitles) {
    this(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        Map.of());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information,
      Optional<DefaultsPresentation> defaults) {
    this(presentation, references, editors, imports, information, defaults, Map.of());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports,
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> information) {
    this(presentation, references, editors, imports, information, Optional.empty());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references,
      Function<DocumentDefinition<T>, FieldEditors<T>> editors,
      Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports) {
    this(presentation, references, editors, imports, definition -> Optional.empty());
  }

  public ClientFormOptions(
      FormPresentation presentation,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> references) {
    this(
        presentation,
        references,
        definition -> FieldEditors.builder(definition).build(),
        definition -> Optional.empty());
  }

  public SettingsScreen<T> open(
      Screen parent,
      Component title,
      Component context,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      SettingsScreen.SubmissionLabels labels) {
    var form =
        new SettingsScreen<>(
            parent,
            title,
            context,
            definition,
            actions,
            references,
            presentation,
            editors.apply(definition),
            labels);
    imports.apply(definition).ifPresent(form::withImports);
    information.apply(definition).ifPresent(form::withInformation);
    defaults.ifPresent(value -> form.withDefaults(value.label(), value.help()));
    form.withReferenceTitles(referenceTitles);
    form.withControls(controls);
    form.withComponents(components);
    form.withSections(sections);
    form.withEntries(entries);
    return form;
  }

  public ClientFormOptions<T> withInformation(
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> factory) {
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        factory,
        defaults,
        referenceTitles,
        controls,
        entries,
        components,
        sections);
  }

  /** Label and visible help for document defaults, independent of per-field Default controls. */
  public ClientFormOptions<T> withDefaults(Component label, Component help) {
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        information,
        Optional.of(new DefaultsPresentation(label, help)),
        referenceTitles,
        controls,
        entries,
        components,
        sections);
  }

  /** Opt into titled selectors for this source; raw IDs remain explicitly editable. */
  public ClientFormOptions<T> withReferenceTitles(DocumentId source, ReferenceTitles titles) {
    var selected = new java.util.LinkedHashMap<>(referenceTitles);
    selected.put(Objects.requireNonNull(source), Objects.requireNonNull(titles));
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        selected,
        controls,
        entries,
        components,
        sections);
  }

  /** Replace or wrap one declared scalar control without replacing its owner or edit pipeline. */
  public ClientFormOptions<T> withControl(FieldPath path, ClientControlAdapter adapter) {
    var selected = new java.util.LinkedHashMap<>(controls);
    if (selected.putIfAbsent(Objects.requireNonNull(path), Objects.requireNonNull(adapter)) != null)
      throw new IllegalArgumentException("A field control may be declared only once");
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        selected,
        entries,
        components,
        sections);
  }

  /** Compose actual field parts through the shared kernel and the original native input. */
  public ClientFormOptions<T> withComponent(FieldPath path, ClientFieldComponent component) {
    var selected = new java.util.LinkedHashMap<>(components);
    if (selected.putIfAbsent(Objects.requireNonNull(path), Objects.requireNonNull(component))
        != null) throw new IllegalArgumentException("A field component may be declared only once");
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        controls,
        entries,
        selected,
        sections);
  }

  /** Compose all canonical fields in one actual generated group or range. */
  public ClientFormOptions<T> withSection(
      design.inherent.reform.presentation.FormTarget.Section target,
      ClientSectionComponent<T> component) {
    var selected = new java.util.LinkedHashMap<>(sections);
    if (selected.putIfAbsent(Objects.requireNonNull(target), Objects.requireNonNull(component))
        != null)
      throw new IllegalArgumentException("A section component may be declared only once");
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        controls,
        entries,
        components,
        selected);
  }

  /**
   * Declare optional slots for one actual scalar/reference field; no editing capability is added.
   */
  public ClientFormOptions<T> withEntry(FieldPath path, ClientEntryPresentation entry) {
    var selected = new java.util.LinkedHashMap<>(entries);
    if (selected.putIfAbsent(Objects.requireNonNull(path), Objects.requireNonNull(entry)) != null)
      throw new IllegalArgumentException("Field slots may be declared only once");
    return new ClientFormOptions<>(
        presentation,
        references,
        editors,
        imports,
        information,
        defaults,
        referenceTitles,
        controls,
        selected,
        components,
        sections);
  }
}
