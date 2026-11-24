package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentConflicts;
import design.inherent.reform.presentation.FormPresentation;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** Standalone native host of the same retained manual conflict view used by a workspace. */
public final class ConflictScreen<T> extends FormHostScreen implements ClientConflictInspection {
  private final ConflictView<T> view;
  private boolean mounted, borrowed;

  public ConflictScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      BooleanSupplier available,
      Consumer<Optional<DocumentConflicts.Resolution<T>>> returned,
      Runnable abandoned) {
    super(title);
    view = new ConflictView<>(title, contextLabel, actions, available, returned, abandoned);
  }

  @Override
  public Optional<DocumentConflicts.Preview> reviewedInput() {
    return view.reviewedInput();
  }

  @Override
  public Optional<AbstractWidget> choiceControl(FieldPath path) {
    return view.choiceControl(path);
  }

  public ConflictScreen<T> withFieldOrder(List<FieldPath> paths) {
    view.withFieldOrder(paths);
    return this;
  }

  public ConflictScreen<T> withFieldPresentation(FormPresentation presentation) {
    view.withFieldPresentation(presentation);
    return this;
  }

  boolean borrows(DocumentActions<?> actual) {
    return view.borrows(actual);
  }

  ConflictView<T> borrowView() {
    if (mounted || borrowed) throw new IllegalStateException("Conflict already has a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed) throw new IllegalStateException("Conflict belongs to its retained workspace");
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
