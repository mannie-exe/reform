package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.UiComponent;
import design.inherent.reform.presentation.ViewKey;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiLimits;
import java.util.Objects;
import java.util.function.Supplier;

/** A lazy custom body for one declared category, within the existing workflow and its bounds. */
public final class ClientCustomView<S> {
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

  public ClientCustomView(ViewKey<S> key, S initial, Factory<S> factory) {
    this.key = Objects.requireNonNull(key);
    this.initial = key.state().capture(initial);
    this.factory = Objects.requireNonNull(factory);
    component = null;
    componentLimits = null;
  }

  private ClientCustomView(
      ViewKey<S> key, S initial, UiLimits limits, UiComponent<ClientViewState<S>> component) {
    this.key = Objects.requireNonNull(key);
    this.initial = key.state().capture(initial);
    this.component = Objects.requireNonNull(component);
    componentLimits = Objects.requireNonNull(limits);
    factory = null;
  }

  /** A mount-once semantic component using the same workflow, state, panel, and native lifetime. */
  public static <S> ClientCustomView<S> component(
      ViewKey<S> key, S initial, UiLimits limits, UiComponent<ClientViewState<S>> component) {
    return new ClientCustomView<>(key, initial, limits, component);
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
      java.util.function.Consumer<FormPanel> focus) {
    if (component != null) {
      // The component host drains these cached observations on its update boundary. Do not rebuild
      // the native tree reentrantly inside an in-progress semantic action.
      var state = new ClientViewState<>(initial, context, () -> {});
      return new CustomView(
          context, MountedUiTree.open(context, componentLimits, component, state), focus);
    }
    var state = new ClientViewState<>(initial, context, changed);
    var content = Objects.requireNonNull(factory.open(context, state));
    return new CustomView(context, content, graphics, focus);
  }
}
