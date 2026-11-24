package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentReview;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Standalone native host for the same retained inspection used by a managed workspace. */
public final class ReviewScreen<T> extends FormHostScreen implements ClientInspection {
  interface Action {
    Component label();

    Optional<Component> blocked(DraftRevision target);

    Screen open(DraftRevision target, Runnable returned, Runnable abandoned);
  }

  /** Its recipient submits this exact revision through the existing owner service. */
  public record Submission(Component label, Consumer<DraftRevision> submit) {
    public Submission {
      label = Objects.requireNonNull(label).copy();
      Objects.requireNonNull(submit);
    }

    @Override
    public Component label() {
      return label.copy();
    }
  }

  private final ReviewView<T> view;
  private boolean borrowed, mounted;

  public ReviewScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      Optional<FieldPath> selected,
      BooleanSupplier available,
      Consumer<Optional<FieldPath>> returned,
      Runnable abandoned) {
    this(title, contextLabel, actions, selected, available, returned, abandoned, Optional.empty());
  }

  public ReviewScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      Optional<FieldPath> selected,
      BooleanSupplier available,
      Consumer<Optional<FieldPath>> returned,
      Runnable abandoned,
      Optional<Submission> submission) {
    super(title);
    view =
        new ReviewView<>(
            title, contextLabel, actions, selected, available, returned, abandoned, submission);
  }

  ReviewScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      BooleanSupplier available,
      Consumer<Optional<FieldPath>> returned,
      Runnable abandoned,
      DocumentReview captured,
      ValidationReport preflight) {
    super(title);
    view =
        new ReviewView<>(
            title, contextLabel, actions, available, returned, abandoned, captured, preflight);
  }

  void withActions(List<Action> entries) {
    view.withActions(entries);
  }

  public ReviewScreen<T> withFieldOrder(List<FieldPath> paths) {
    view.withFieldOrder(paths);
    return this;
  }

  public ReviewScreen<T> withFieldPresentation(FormPresentation presentation) {
    view.withFieldPresentation(presentation);
    return this;
  }

  @Override
  public Optional<DocumentReview> reviewedInput() {
    return view.reviewedInput();
  }

  @Override
  public Optional<FieldPath> selectedField() {
    return view.selectedField();
  }

  ReviewView<T> borrowView() {
    if (mounted || borrowed)
      throw new IllegalStateException("Inspection already has a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  boolean borrows(DocumentActions<?> actions) {
    return view.borrows(actions);
  }

  @Override
  protected void init() {
    if (borrowed) throw new IllegalStateException("Inspection belongs to its retained workspace");
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
