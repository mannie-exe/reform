package design.inherent.reform.minecraft.client;

import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** Borrowed, identified operation feedback. Back leaves work running; cancellation is explicit. */
public final class OperationScreen<O> extends FormHostScreen implements ClientOperationInspection {
  public record Detail(Component label, Component value) {
    public Detail {
      label = Objects.requireNonNull(label).copy();
      value = Objects.requireNonNull(value).copy();
    }

    @Override
    public Component label() {
      return label.copy();
    }

    @Override
    public Component value() {
      return value.copy();
    }
  }

  /** Complete bounded projection, not a second result or success receipt. */
  public record Outcome(Component summary, List<Detail> details) {
    public Outcome(List<Detail> details) {
      this(Component.empty(), details);
    }

    public Outcome {
      summary = Objects.requireNonNull(summary).copy();
      details = List.copyOf(details);
      if (details.size() > 512)
        throw new IllegalArgumentException("Complete operation details exceed 512 rows");
      long characters = summary.getString().length();
      if (characters > 4096)
        throw new IllegalArgumentException("Operation summary exceeds its narration limit");
      for (var detail : details)
        characters += detail.label().getString().length() + detail.value().getString().length();
      if (characters > 524288)
        throw new IllegalArgumentException("Complete operation details exceed their text limit");
    }

    @Override
    public Component summary() {
      return summary.copy();
    }
  }

  /** An explicit handoff out of this view; invoke retains the actual service's effect preflight. */
  public record Action(
      String id, Component label, Supplier<Optional<Component>> blocked, Runnable invoke) {
    public Action {
      Objects.requireNonNull(id);
      if (id.isBlank() || id.length() > 128 || id.equals("back") || id.equals("cancel"))
        throw new IllegalArgumentException("Invalid operation action identity");
      label = Objects.requireNonNull(label).copy();
      Objects.requireNonNull(blocked);
      Objects.requireNonNull(invoke);
    }

    @Override
    public Component label() {
      return label.copy();
    }
  }

  private final OperationView<O> view;
  private boolean borrowed, mounted;

  public OperationScreen(
      Component title,
      Component contextLabel,
      OperationHandle<O> operation,
      BooleanSupplier mayObserve,
      Function<O, Outcome> describe,
      List<Action> actions,
      Runnable returned,
      Runnable abandoned) {
    super(Objects.requireNonNull(title).copy());
    view =
        new OperationView<>(
            title, contextLabel, operation, mayObserve, describe, actions, returned, abandoned);
  }

  @Override
  public Optional<OperationSnapshot<O>> observedOperation() {
    return view.observedOperation();
  }

  OperationHandle<O> handle() {
    return view.handle();
  }

  OperationView<O> borrowView() {
    if (mounted || borrowed) throw new IllegalStateException("Operation already has a native host");
    borrowed = true;
    view.presentation(theme(), taskPresentation());
    sealPresentation();
    return view;
  }

  @Override
  protected void init() {
    if (borrowed) throw new IllegalStateException("Operation belongs to its retained workspace");
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
