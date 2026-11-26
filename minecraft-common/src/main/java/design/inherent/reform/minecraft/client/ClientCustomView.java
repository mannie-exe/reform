package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ui.UiComponent;
import design.inherent.reform.minecraft.client.ui.ViewSpec;
import design.inherent.reform.presentation.ViewKey;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiScope;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** A lazy custom body for one declared category, within the existing workflow and its bounds. */
public final class ClientCustomView<S> {
  @FunctionalInterface
  public interface DocumentComponent<T, S> {
    /** The workflow supplies its exact retained, loaded editor; this does not create an owner. */
    ViewSpec build(UiScope scope, ClientEditor<T> editor, ClientViewState<S> state);
  }

  record DocumentOwner(DocumentId route, DocumentDefinition<?> definition) {
    DocumentOwner {
      Objects.requireNonNull(route);
      Objects.requireNonNull(definition);
    }
  }

  @FunctionalInterface
  private interface OwnedComponent<S> {
    ViewSpec build(UiScope scope, ClientEditor<?> editor, ClientViewState<S> state);
  }

  @FunctionalInterface
  public interface Factory<S> {
    /**
     * Called once on native admission. Register partial-resource cleanup on context first. The
     * returned projection reads cached state only; it may run on native reflow.
     */
    Supplier<ClientViewContent> open(ClientViewContext context, ClientViewState<S> state);
  }

  private final ViewKey<S> key;
  private final ViewStateKey.Snapshot<S> initial;
  private final Factory<S> factory;
  private final UiComponent<ClientViewState<S>> component;
  private final UiLimits componentLimits;
  private final Optional<DocumentOwner> owner;
  private final OwnedComponent<S> ownedComponent;

  public ClientCustomView(ViewKey<S> key, S initial, Factory<S> factory) {
    this.key = Objects.requireNonNull(key);
    this.initial = key.state().capture(initial);
    this.factory = Objects.requireNonNull(factory);
    component = null;
    componentLimits = null;
    owner = Optional.empty();
    ownedComponent = null;
  }

  private ClientCustomView(
      ViewKey<S> key, S initial, UiLimits limits, UiComponent<ClientViewState<S>> component) {
    this.key = Objects.requireNonNull(key);
    this.initial = key.state().capture(initial);
    this.component = Objects.requireNonNull(component);
    componentLimits = Objects.requireNonNull(limits);
    factory = null;
    owner = Optional.empty();
    ownedComponent = null;
  }

  private ClientCustomView(
      ViewKey<S> key,
      S initial,
      UiLimits limits,
      DocumentOwner owner,
      OwnedComponent<S> component) {
    this.key = Objects.requireNonNull(key);
    this.initial = key.state().capture(initial);
    componentLimits = Objects.requireNonNull(limits);
    this.owner = Optional.of(owner);
    ownedComponent = Objects.requireNonNull(component);
    this.component = null;
    factory = null;
  }

  /** A mount-once semantic component using the same workflow, state, panel, and native lifetime. */
  public static <S> ClientCustomView<S> component(
      ViewKey<S> key, S initial, UiLimits limits, UiComponent<ClientViewState<S>> component) {
    return new ClientCustomView<>(key, initial, limits, component);
  }

  /**
   * Associate a lazy component with one declared form route and exact document definition.
   * Navigation reuses the existing editor/load lane; construction enters no owner or source.
   * Discarding that editor ends this component lifetime, including its retained local state.
   */
  public static <T, S> ClientCustomView<S> documentComponent(
      ViewKey<S> key,
      S initial,
      UiLimits limits,
      DocumentId route,
      DocumentDefinition<T> definition,
      DocumentComponent<T, S> component) {
    Objects.requireNonNull(component);
    return new ClientCustomView<>(
        key,
        initial,
        limits,
        new DocumentOwner(route, definition),
        (scope, editor, state) -> component.build(scope, typed(editor, definition), state));
  }

  private static <T> ClientEditor<T> typed(
      ClientEditor<?> editor, DocumentDefinition<T> definition) {
    if (editor.actions().definition() != definition)
      throw new IllegalArgumentException(
          "Use the exact definition registered by this workflow owner");
    @SuppressWarnings("unchecked") // Exact definition identity binds the actor's value type.
    var typed = (ClientEditor<T>) editor;
    return typed;
  }

  Optional<DocumentOwner> owner() {
    return owner;
  }

  public ViewKey<S> key() {
    return key;
  }

  CustomView open(
      ClientViewContext context,
      Runnable changed,
      java.util.function.BiFunction<
              design.inherent.reform.presentation.graphics.GraphicLimits,
              Integer,
              ClientGraphicHost>
          graphics,
      java.util.function.Consumer<FormPanel> focus,
      ClientEditor<?> editor) {
    if (component != null || ownedComponent != null) {
      if (ownedComponent != null) {
        Objects.requireNonNull(editor).actions().requireInspection();
        if (editor.view().availability()
            != design.inherent.reform.owner.EditorActor.Availability.READY)
          throw new design.inherent.reform.owner.PolicyRefusal(
              "client.document_unavailable", "Open a ready document before entering this view.");
      }
      // The component host drains these cached observations on its update boundary. Do not rebuild
      // the native tree reentrantly inside an in-progress semantic action.
      var state = new ClientViewState<>(initial, context, () -> {});
      return new CustomView(
          context,
          MountedUiTree.open(
              context,
              componentLimits,
              component != null
                  ? component
                  : (scope, value) -> ownedComponent.build(scope, editor, value),
              state),
          focus);
    }
    var state = new ClientViewState<>(initial, context, changed);
    var content = Objects.requireNonNull(factory.open(context, state));
    return new CustomView(context, content, graphics, focus);
  }
}
