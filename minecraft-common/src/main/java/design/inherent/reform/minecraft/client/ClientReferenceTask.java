package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceLimits;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** A shared catalogue task over one retained document, not another editor or persistence owner. */
public final class ClientReferenceTask<T, V> {
  @FunctionalInterface
  public interface Source<T, V> {
    /** Return the existing admitted source. Register cleanup for any newly owned partial work. */
    ReferenceActions<String, V> open(ClientViewContext scope, ClientEditor<T> editor);
  }

  public record Selection(FieldPath field, Component label) {
    public Selection {
      Objects.requireNonNull(field);
      label = caption(label, 1024, false);
    }

    @Override
    public Component label() {
      return label.copy();
    }
  }

  private final DocumentId route, source;
  private final DocumentDefinition<T> definition;
  private final Source<T, V> factory;
  private final ReferenceLimits limits;
  private final List<Selection> selections;
  private final Component help;
  private final Optional<ClientTaskGraphic<T, ?>> graphic;

  public ClientReferenceTask(
      DocumentId route,
      DocumentDefinition<T> definition,
      DocumentId source,
      ReferenceLimits limits,
      List<Selection> selections,
      Source<T, V> factory) {
    this(
        route,
        definition,
        source,
        limits,
        selections,
        factory,
        Component.empty(),
        Optional.empty());
  }

  private ClientReferenceTask(
      DocumentId route,
      DocumentDefinition<T> definition,
      DocumentId source,
      ReferenceLimits limits,
      List<Selection> selections,
      Source<T, V> factory,
      Component help,
      Optional<ClientTaskGraphic<T, ?>> graphic) {
    this.route = Objects.requireNonNull(route);
    this.definition = Objects.requireNonNull(definition);
    this.source = Objects.requireNonNull(source);
    this.limits = Objects.requireNonNull(limits);
    this.selections = List.copyOf(selections);
    this.factory = Objects.requireNonNull(factory);
    this.help = caption(help, 4096, true);
    this.graphic = Objects.requireNonNull(graphic);
    var paths = new HashSet<FieldPath>();
    if (selections.isEmpty()
        || selections.size() > 16
        || selections.stream().anyMatch(pick -> !paths.add(pick.field())))
      throw new IllegalArgumentException(
          "Declare between 1 and 16 distinct reference field actions");
  }

  public ClientReferenceTask<T, V> withHelp(Component help) {
    return new ClientReferenceTask<>(
        route, definition, source, limits, selections, factory, help, graphic);
  }

  public <S> ClientReferenceTask<T, V> withGraphic(ClientTaskGraphic<T, S> graphic) {
    return new ClientReferenceTask<>(
        route, definition, source, limits, selections, factory, help, Optional.of(graphic));
  }

  public DocumentId route() {
    return route;
  }

  public DocumentDefinition<T> definition() {
    return definition;
  }

  public DocumentId source() {
    return source;
  }

  public ReferenceLimits limits() {
    return limits;
  }

  public List<Selection> selections() {
    return selections;
  }

  public Component help() {
    return help.copy();
  }

  public Optional<ClientTaskGraphic<T, ?>> graphic() {
    return graphic;
  }

  ReferenceTaskView<T, V> open(
      ClientEditor<?> actual, ClientViewContext scope, ClientEditingWorkflow workflow) {
    var found = actual.actions().definition();
    // The source's exact ValueType is the typed binding, not merely a matching text ID.
    if (!found.id().equals(definition.id())
        || found.schemaVersion() != definition.schemaVersion()
        || found.type() != definition.type())
      throw new IllegalArgumentException(
          "Reference task requires its declared actual owner definition");
    @SuppressWarnings("unchecked")
    var editor = (ClientEditor<T>) actual;
    return new ReferenceTaskView<>(this, editor, scope, workflow);
  }

  ReferenceActions<String, V> openSource(ClientViewContext scope, ClientEditor<T> editor) {
    var references = Objects.requireNonNull(factory.open(scope, editor));
    if (!references.source().equals(source))
      throw new IllegalArgumentException("Reference task returned a different source");
    return references;
  }

  static Component caption(Component value, int maximum, boolean emptyAllowed) {
    var copy = Objects.requireNonNull(value).copy();
    if ((!emptyAllowed && copy.getString().isBlank()) || copy.getString().length() > maximum)
      throw new IllegalArgumentException("Task captions must be bounded");
    return copy;
  }
}
