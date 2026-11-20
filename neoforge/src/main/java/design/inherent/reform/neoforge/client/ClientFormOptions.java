package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.neoforge.command.ImportCatalog;
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
    Function<DocumentDefinition<T>, Optional<ImportCatalog<T>>> imports) {
  public ClientFormOptions {
    Objects.requireNonNull(presentation);
    references = Map.copyOf(references);
    Objects.requireNonNull(editors);
    Objects.requireNonNull(imports);
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

  SettingsScreen<T> open(
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
    return form;
  }
}
