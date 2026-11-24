package design.inherent.reform.minecraft.client;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** Standalone host for the same bounded decision borrowed by its invoking workspace. */
public final class UnsavedChangesScreen extends FormHostScreen {
  public enum Choice {
    SAVE,
    DISCARD,
    KEEP_EDITING
  }

  private final UnsavedChangesView view;
  private boolean borrowed, mounted;

  /**
   * Readiness is cached only; the selected callback retains actual owner preflight and receipts.
   */
  public UnsavedChangesScreen(
      Component title,
      Component contextLabel,
      Component explanation,
      Component saveLabel,
      Supplier<Optional<Component>> saveBlocked,
      Consumer<Choice> selected,
      Runnable abandoned) {
    super(title);
    view =
        new UnsavedChangesView(
            title, contextLabel, explanation, saveLabel, saveBlocked, selected, abandoned);
  }

  UnsavedChangesView borrowView() {
    if (mounted || borrowed)
      throw new IllegalStateException("Close decision already has a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed)
      throw new IllegalStateException("Close decision belongs to its invoking workspace");
    mounted = true;
    suspendView();
    view.presentation(theme(), taskPresentation());
    mount(view);
  }

  @Override
  protected void beforeRebuild() {
    view.preserve();
  }
}
