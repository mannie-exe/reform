package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.ContextRecipeOwner;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotContext;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;

/** An explicit read-only operation over one captured recipe and its real native source. */
final class RecipePreview<T, P, O> implements SettingsScreen.PreviewEntry {
  private record Selected<O>(
      DraftRevision draft, OperationHandle<WorldRecipe.PreviewResult<O>> operation) {}

  private final DocumentActions<T> actions;
  private final ContextRecipeOwner<T, WorldCreationContext, P>.Session source;
  private final WorldRecipe.Preview<P, O> preview;
  private final OperationScope scope;
  private final OperationDescriptor<DraftRevision, WorldRecipe.PreviewResult<O>> descriptor;
  private Selected<O> selected;

  RecipePreview(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentActions<T> actions,
      ContextRecipeOwner<T, WorldCreationContext, P>.Session source,
      WorldRecipe.Preview<P, O> preview) {
    this.actions = actions;
    this.source = source;
    this.preview = preview;
    scope =
        new OperationScope(
            context,
            access,
            new OperationScope.Limits(1, 1, 4, OperationScope.Limits.STANDARD.input()));
    descriptor =
        new OperationDescriptor<>(
            DocumentId.parse("reform:recipe_preview"),
            DraftRevision.type(),
            OwnerContext.Right.READ,
            Set.of(OperationDescriptor.Effect.INSPECT),
            Set.of(EntryPoint.GUI),
            Optional.of(actions.definition().id()));
    scope.register(
        descriptor,
        (execution, target) -> ClientSettingsRuntime.preview(() -> analyze(execution, target)));
    scope.freeze();
  }

  private CompletionStage<WorldRecipe.PreviewResult<O>> analyze(
      OperationScope.Execution execution, DraftRevision target) {
    return DocumentActions.result(actions.explain(EntryPoint.INTERNAL))
        .thenCompose(
            inspected -> {
              execution.checkpoint();
              if (!target.matches(inspected.snapshot()))
                throw new PolicyRefusal(
                    "editor.review_changed", "The draft changed before the preview began");
              execution.progress(
                  new OperationSnapshot.Progress("Compiling recipe", 0, OptionalLong.empty(), ""));
              return source.preview(
                  target.sourceRevision(),
                  inspected.snapshot().draft(),
                  execution::checkpoint,
                  compiled -> {
                    execution.progress(
                        new OperationSnapshot.Progress(
                            "Analyzing recipe", 0, OptionalLong.empty(), ""));
                    var value =
                        Objects.requireNonNull(preview.analyze().apply(compiled, execution));
                    execution.checkpoint();
                    var snapshots = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
                    return new WorldRecipe.PreviewResult<>(
                        target,
                        snapshots.copy(value, () -> preview.snapshots().freeze(value, snapshots)));
                  });
            })
        .thenApply(
            result -> {
              execution.checkpoint();
              execution.progress(
                  new OperationSnapshot.Progress(
                      "Preview ready", 1, OptionalLong.of(1), "preview"));
              return result;
            });
  }

  @Override
  public boolean retained() {
    return selected != null && readable(selected);
  }

  @Override
  public Screen open(Runnable returned, Runnable abandoned) {
    return screen(retained() ? selected : start(), returned, abandoned);
  }

  private Selected<O> start() {
    if (blocked().isPresent())
      throw new PolicyRefusal(
          "preview.unavailable",
          "Finish the current operation and validate the draft before previewing");
    var target = DraftRevision.of(actions.view().snapshot().orElseThrow());
    source.requireSource(target.sourceRevision());
    selected = new Selected<>(target, scope.submit(descriptor, EntryPoint.GUI, target));
    return selected;
  }

  private Optional<Component> blocked() {
    if (selected != null && !selected.operation().snapshot().terminal())
      return Optional.of(text("running", "A preview is still running."));
    var view = actions.view();
    if (view.availability() != EditorActor.Availability.READY
        || view.snapshot().isEmpty()
        || !view.validation().valid()
        || view.pending().isPresent()
        || view.review().isPresent())
      return Optional.of(text("unavailable", "Validate the current draft before previewing."));
    return Optional.empty();
  }

  private boolean readable(Selected<O> capture) {
    if (selected != capture || !actions.isOpen()) return false;
    try {
      source.requireSource(capture.draft().sourceRevision());
      return scope
          .find(capture.operation().snapshot().id())
          .filter(value -> value == capture.operation())
          .isPresent();
    } catch (PolicyRefusal unavailable) {
      return false;
    }
  }

  private Screen screen(Selected<O> capture, Runnable returned, Runnable abandoned) {
    return new OperationScreen<>(
        preview.title(),
        text(
            "context",
            "Preview of draft %s · use Apply recipe separately to change this world recipe.",
            capture.draft().version()),
        capture.operation(),
        () -> readable(capture),
        result -> {
          var shown = preview.describe().apply(result);
          boolean currentDraft =
              actions.view().snapshot().filter(capture.draft()::matches).isPresent();
          return currentDraft
              ? shown
              : new OperationScreen.Outcome(
                  text("earlier", "This result describes an earlier draft.")
                      .copy()
                      .append(" ")
                      .append(shown.summary()),
                  shown.details());
        },
        List.of(
            new OperationScreen.Action(
                "new_preview",
                text("again", "Run a new preview"),
                this::blocked,
                () -> {
                  try {
                    Minecraft.getInstance().setScreen(screen(start(), returned, abandoned));
                  } catch (PolicyRefusal refused) {
                    returned.run();
                  }
                })),
        returned,
        abandoned);
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.recipe_preview." + key, fallback, arguments);
  }
}
