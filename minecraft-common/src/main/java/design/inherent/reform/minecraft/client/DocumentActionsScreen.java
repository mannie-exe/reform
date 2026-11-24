package design.inherent.reform.minecraft.client;

import design.inherent.reform.operation.DocumentActions;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.network.chat.Component;

/** Native standalone host; a matching workspace borrows this same owner-action view. */
final class DocumentActionsScreen extends FormHostScreen {
  record Action(Component title, Component help, BooleanSupplier enabled, Runnable run) {
    Action {
      title = Objects.requireNonNull(title).copy();
      help = Objects.requireNonNull(help).copy();
      Objects.requireNonNull(enabled);
      Objects.requireNonNull(run);
    }
  }

  record Group(Component title, List<Action> actions) {
    Group {
      title = Objects.requireNonNull(title).copy();
      actions = List.copyOf(actions);
      if (actions.isEmpty() || actions.size() > 16)
        throw new IllegalArgumentException("Action groups must be bounded");
    }
  }

  private final DocumentActionsView view;
  private boolean borrowed, mounted;

  DocumentActionsScreen(
      DocumentActions<?> actions,
      Component owner,
      Component context,
      List<Group> groups,
      BooleanSupplier available,
      Runnable back,
      Runnable abandoned) {
    super(
        Component.translatableWithFallback(
            "reform.settings.document_actions_title", "Document actions"));
    view = new DocumentActionsView(actions, owner, context, groups, available, back, abandoned);
  }

  boolean borrows(DocumentActions<?> actions) {
    return view.borrows(actions);
  }

  DocumentActionsView borrowView() {
    if (borrowed || mounted)
      throw new IllegalStateException("Document actions already have a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed)
      throw new IllegalStateException("Document actions belong to their retained workspace");
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
