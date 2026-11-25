package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.ui.UiObservation;
import design.inherent.reform.presentation.ui.UiRead;
import java.util.Objects;

/** View-local data only. Changing it never edits a document or invokes an owner operation. */
public final class ClientViewState<S> implements UiRead<S> {
  private ViewStateKey.Snapshot<S> saved;
  private final ClientViewContext context;
  private final Runnable changed;
  private UiObservation<S> observation;
  private long revision = 1;

  ClientViewState(ViewStateKey.Snapshot<S> initial, ClientViewContext context, Runnable changed) {
    saved = Objects.requireNonNull(initial);
    this.context = Objects.requireNonNull(context);
    this.changed = Objects.requireNonNull(changed);
    observation = new UiObservation.Available<>(saved, revision);
  }

  public S value() {
    context.requireCurrent();
    return saved.value();
  }

  public void replace(S value) {
    context.requireCurrent();
    var next = saved.key().capture(value);
    if (saved.sameData(next)) return;
    saved = next;
    observation = new UiObservation.Available<>(saved, ++revision);
    changed.run();
  }

  /** Existing retained presentation data, not a document or a new source read. */
  @Override
  public UiObservation<S> snapshot() {
    context.requireCurrent();
    return observation;
  }

  /** Refresh cached content after an explicit source notification; never a per-frame IO hook. */
  public void refresh() {
    context.requireCurrent();
    observation = new UiObservation.Available<>(saved, ++revision);
    changed.run();
  }
}
