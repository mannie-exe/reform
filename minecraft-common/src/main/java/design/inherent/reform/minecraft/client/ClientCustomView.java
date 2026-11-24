package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.ViewKey;
import design.inherent.reform.presentation.ViewStateKey;
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

  public ClientCustomView(ViewKey<S> key, S initial, Factory<S> factory) {
    this.key = Objects.requireNonNull(key);
    this.initial = key.state().capture(initial);
    this.factory = Objects.requireNonNull(factory);
  }

  public ViewKey<S> key() {
    return key;
  }

  CustomView open(ClientViewContext context, Runnable changed) {
    var state = new ClientViewState<>(initial, context, changed);
    var content = Objects.requireNonNull(factory.open(context, state));
    return new CustomView(context, content);
  }
}
