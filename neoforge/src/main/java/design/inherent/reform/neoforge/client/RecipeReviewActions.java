package design.inherent.reform.neoforge.client;

import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor;
import design.inherent.reform.operation.OperationDescriptor.Effect;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.ContextRecipeOwner;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;

/** Binds explicitly reviewed persistence/export services to one native creation editor. */
final class RecipeReviewActions<T, P> {
  private record Selection<O>(DraftRevision target, OperationHandle<O> handle) {}

  private final OwnerContext context;
  private final OwnerContext.Access access;
  private final DocumentActions<T> actions;
  private final ContextRecipeOwner<T, WorldCreationContext, P>.Session source;
  private final OperationScope scope;
  private final List<ReviewScreen.Action> entries = new ArrayList<>();

  RecipeReviewActions(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentActions<T> actions,
      ContextRecipeOwner<T, WorldCreationContext, P>.Session source) {
    this.context = context;
    this.access = access;
    this.actions = actions;
    this.source = source;
    scope =
        new OperationScope(
            context,
            access,
            new OperationScope.Limits(4, 1, 8, OperationScope.Limits.STANDARD.input()));
  }

  <O> void register(WorldRecipe.ReviewedAction<P, O> action) {
    var binding = new Binding<>(action);
    scope.register(binding.descriptor, binding::run);
    entries.add(binding);
  }

  List<ReviewScreen.Action> freeze() {
    scope.freeze();
    return List.copyOf(entries);
  }

  private final class Binding<O> implements ReviewScreen.Action {
    private final WorldRecipe.ReviewedAction<P, O> action;
    private final OperationDescriptor<DraftRevision, O> descriptor;
    private Selection<O> selected;

    Binding(WorldRecipe.ReviewedAction<P, O> action) {
      this.action = action;
      descriptor =
          new OperationDescriptor<>(
              action.id(),
              DraftRevision.type(),
              action.effect() == Effect.PERSIST
                  ? OwnerContext.Right.WRITE
                  : OwnerContext.Right.READ,
              Set.of(action.effect()),
              Set.of(EntryPoint.GUI),
              Optional.of(actions.definition().id()));
    }

    @Override
    public Component label() {
      return action.title();
    }

    @Override
    public Optional<Component> blocked(DraftRevision target) {
      try {
        requireDraft(target);
        if (retained(target)) return Optional.empty();
        return startBlocked();
      } catch (PolicyRefusal refused) {
        return Optional.of(Component.literal(refused.getMessage()));
      }
    }

    private Optional<Component> startBlocked() {
      if (scope.hasActiveOperations())
        return Optional.of(text("busy", "Another reviewed recipe action is still running."));
      return Objects.requireNonNull(action.unavailable().get()).map(Component::copy);
    }

    private void requireDraft(DraftRevision target) {
      access.require(descriptor.right());
      source.requireSource(target.sourceRevision());
      var view = actions.view();
      if (view.availability() != EditorActor.Availability.READY
          || view.snapshot().filter(target::matches).isEmpty()
          || !view.validation().valid()
          || view.pending().isPresent()
          || view.review().isPresent())
        throw new PolicyRefusal("editor.review_changed", "Review the current valid recipe again");
    }

    private void requireEffect(DraftRevision target, OperationScope.Execution execution) {
      context.requireOwner();
      execution.checkpoint();
      Objects.requireNonNull(action.unavailable().get())
          .ifPresent(
              reason -> {
                throw new PolicyRefusal("recipe.action_unavailable", reason.getString());
              });
      requireDraft(target);
    }

    private CompletionStage<O> run(OperationScope.Execution execution, DraftRevision target) {
      requireEffect(target, execution);
      return DocumentActions.result(actions.explain(EntryPoint.INTERNAL))
          .thenCompose(
              inspected -> {
                execution.checkpoint();
                if (!target.matches(inspected.snapshot()))
                  throw new PolicyRefusal("editor.review_changed", "The reviewed recipe changed");
                progress(execution, "Compiling complete recipe");
                return ClientSettingsRuntime.preview(
                    () ->
                        source.preview(
                            target.sourceRevision(),
                            inspected.snapshot().draft(),
                            execution::checkpoint,
                            Function.identity()));
              })
          .thenCompose(
              compiled ->
                  context
                      .onOwner(() -> perform(target, compiled, execution))
                      .thenCompose(Function.identity()))
          .thenApply(
              result -> {
                var snapshots = new SnapshotContext(SnapshotContext.Limits.CONFIGURATION);
                return snapshots.copy(result, () -> action.snapshots().freeze(result, snapshots));
              });
    }

    private CompletionStage<O> perform(
        DraftRevision target, P compiled, OperationScope.Execution execution) {
      requireEffect(target, execution);
      progress(execution, "Waiting for the owner's result");
      var open = new AtomicBoolean(true);
      var input =
          new WorldRecipe.ReviewedInput<>(
              target,
              compiled,
              () -> {
                if (!open.get())
                  throw new PolicyRefusal(
                      "recipe.action_finished", "The reviewed action has ended");
                requireEffect(target, execution);
              });
      try {
        return Objects.requireNonNull(action.run().apply(input, execution))
            .whenComplete((result, failed) -> open.set(false));
      } catch (RuntimeException | Error failed) {
        open.set(false);
        throw failed;
      }
    }

    @Override
    public Screen open(DraftRevision target, Runnable returned, Runnable abandoned) {
      return screen(retained(target) ? selected : start(target), returned, abandoned);
    }

    private Selection<O> start(DraftRevision target) {
      requireDraft(target);
      startBlocked()
          .ifPresent(
              reason -> {
                throw new PolicyRefusal("recipe.action_unavailable", reason.getString());
              });
      selected = new Selection<>(target, scope.submit(descriptor, EntryPoint.GUI, target));
      return selected;
    }

    private boolean retained(DraftRevision target) {
      return selected != null && selected.target().equals(target) && readable(selected);
    }

    private boolean readable(Selection<O> capture) {
      if (selected != capture || !actions.isOpen()) return false;
      try {
        source.requireSource(capture.target().sourceRevision());
        var handle = capture.handle();
        return scope.find(handle.snapshot().id()).filter(value -> value == handle).isPresent();
      } catch (PolicyRefusal unavailable) {
        return false;
      }
    }

    private Optional<Component> repeatBlocked(Selection<O> capture) {
      try {
        requireDraft(capture.target());
        return startBlocked();
      } catch (PolicyRefusal refused) {
        return Optional.of(Component.literal(refused.getMessage()));
      }
    }

    private Screen screen(Selection<O> capture, Runnable returned, Runnable abandoned) {
      return new OperationScreen<>(
          label(),
          text(
              "context",
              "Complete recipe · draft %s · %s",
              capture.target().version(),
              action.effect() == Effect.PERSIST
                  ? text("persist", "owner-managed persistence")
                  : text("export", "owner-managed export")),
          capture.handle(),
          () -> readable(capture),
          action.describe(),
          List.of(
              new OperationScreen.Action(
                  "repeat_recipe_action",
                  text("again", "Run again"),
                  () -> repeatBlocked(capture),
                  () -> {
                    try {
                      Minecraft.getInstance()
                          .setScreen(screen(start(capture.target()), returned, abandoned));
                    } catch (PolicyRefusal refused) {
                      returned.run();
                    }
                  })),
          returned,
          abandoned);
    }
  }

  private static void progress(OperationScope.Execution execution, String phase) {
    execution.progress(new OperationSnapshot.Progress(phase, 0, OptionalLong.empty(), ""));
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.recipe_action." + key, fallback, arguments);
  }
}
