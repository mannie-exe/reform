package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.TaskPresentation;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/** Shared host composition for actual retained metadata tasks, never their domain services. */
abstract class MetadataView extends FormView {
  protected boolean composed, unified, visible;
  protected Consumer<FormPanel> prefix = ignored -> {}, suffix = ignored -> {};
  private ClientTheme scopedTheme;
  private TaskPresentation scopedTasks;

  MetadataView(Component title) {
    super(title);
  }

  final boolean currentHost() {
    return visible && isCurrent();
  }

  final void compose(boolean unified, Consumer<FormPanel> prefix, Consumer<FormPanel> suffix) {
    composed = true;
    this.unified = unified;
    this.prefix = Objects.requireNonNull(prefix);
    this.suffix = Objects.requireNonNull(suffix);
  }

  final void presentation(ClientTheme theme, TaskPresentation tasks) {
    scopedTheme = Objects.requireNonNull(theme);
    scopedTasks = Objects.requireNonNull(tasks);
  }

  @Override
  protected final ClientTheme theme() {
    return scopedTheme == null ? super.theme() : scopedTheme;
  }

  final TaskPresentation taskPresentation() {
    return scopedTasks == null ? TaskPresentation.standard() : scopedTasks;
  }

  final Component caption() {
    return title.copy();
  }

  abstract FormPanel primary();

  abstract void preserve();

  abstract void hide();

  abstract void retireForNavigation();
}
