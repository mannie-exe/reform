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
    Map<FieldPath, ClientControlAdapter> controls) {
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
    return form;
  }

  public ClientFormOptions<T> withInformation(
      Function<DocumentDefinition<T>, Optional<FormInformation<T>>> factory) {
    return new ClientFormOptions<>(
        presentation, references, editors, imports, factory, defaults, referenceTitles, controls);
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
        controls);
  }

  /** Opt into titled selectors for this source; raw IDs remain explicitly editable. */
  public ClientFormOptions<T> withReferenceTitles(DocumentId source, ReferenceTitles titles) {
    var selected = new java.util.LinkedHashMap<>(referenceTitles);
    selected.put(Objects.requireNonNull(source), Objects.requireNonNull(titles));
    return new ClientFormOptions<>(
        presentation, references, editors, imports, information, defaults, selected, controls);
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
        selected);
  }
}
