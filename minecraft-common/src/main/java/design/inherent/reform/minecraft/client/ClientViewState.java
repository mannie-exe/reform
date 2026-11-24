package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.ViewStateKey;
import java.util.Objects;

/** View-local data only. Changing it never edits a document or invokes an owner operation. */
public final class ClientViewState<S> {
  private ViewStateKey.Snapshot<S> saved;
  private final ClientViewContext context;
  private final Runnable changed;

  ClientViewState(ViewStateKey.Snapshot<S> initial, ClientViewContext context, Runnable changed) {
    saved = Objects.requireNonNull(initial);
    this.context = Objects.requireNonNull(context);
    this.changed = Objects.requireNonNull(changed);
  }

  public S value() {
    context.requireCurrent();
    return saved.value();
  }

  public void replace(S value) {
    context.requireCurrent();
    var next = saved.key().capture(value);
    saved = next;
    changed.run();
  }

  /** Refresh cached content after an explicit source notification; never a per-frame IO hook. */
  public void refresh() {
    context.requireCurrent();
    changed.run();
  }
}
