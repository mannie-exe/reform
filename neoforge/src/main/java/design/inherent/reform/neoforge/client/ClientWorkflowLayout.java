package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationHandle;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** Optional grouped navigation and scoped review over an explicit set of client forms. */
public record ClientWorkflowLayout(List<Group> groups, List<ReviewScope> reviews) {
  public ClientWorkflowLayout {
    groups = List.copyOf(groups);
    reviews = List.copyOf(reviews);
    var placements = groups.stream().flatMap(group -> group.placements().stream()).toList();
    var actions = groups.stream().flatMap(group -> group.actions().stream()).toList();
    if (groups.isEmpty()
        || groups.size() > 32
        || reviews.size() > 32
        || placements.size() > 128
        || actions.size() > 32
        || placements.stream().map(Placement::id).distinct().count() != placements.size()
        || actions.stream().map(Action::id).distinct().count() != actions.size()
        || reviews.stream().map(ReviewScope::id).distinct().count() != reviews.size())
      throw new IllegalArgumentException(
          "Workflow groups, placements, operations and reviews require unique bounded identities");
  }

  /** Navigation only. Multiple placements can open the same retained document and draft. */
  public record Placement(
      DocumentId id, DocumentId route, Component title, Optional<FieldPath> field) {
    public Placement {
      Objects.requireNonNull(id);
      Objects.requireNonNull(route);
      title = Objects.requireNonNull(title).copy();
      Objects.requireNonNull(field);
    }

    @Override
    public Component title() {
      return title.copy();
    }
  }

  public record Group(Component title, List<Placement> placements, List<Action<?>> actions) {
    public Group {
      title = Objects.requireNonNull(title).copy();
      placements = List.copyOf(placements);
      actions = List.copyOf(actions);
    }

    @Override
    public Component title() {
      return title.copy();
    }
  }

  /** An explicit subset, including unchanged dependencies that must be checked before effects. */
  public record ReviewScope(
      DocumentId id,
      Component title,
      Set<DocumentId> writable,
      Set<DocumentId> checked,
      boolean requiredOnSave,
      DocumentBatch.Validator validate) {
    public ReviewScope {
      Objects.requireNonNull(id);
      title = Objects.requireNonNull(title).copy();
      writable = Set.copyOf(writable);
      checked = Set.copyOf(checked);
      Objects.requireNonNull(validate);
      if (writable.isEmpty()
          || writable.size() + checked.size() > 32
          || writable.stream().anyMatch(checked::contains))
        throw new IllegalArgumentException(
            "Review scope needs writable owners and distinct read dependencies");
    }

    @Override
    public Component title() {
      return title.copy();
    }
  }

  /** Display an owner's actual explicit operation using the common retained result view. */
  public record Action<O>(
      DocumentId id,
      Component title,
      Component context,
      Supplier<Optional<Component>> blocked,
      Supplier<OperationHandle<O>> start,
      BooleanSupplier mayObserve,
      Function<O, OperationScreen.Outcome> describe) {
    public Action {
      Objects.requireNonNull(id);
      title = Objects.requireNonNull(title).copy();
      context = Objects.requireNonNull(context).copy();
      Objects.requireNonNull(blocked);
      Objects.requireNonNull(start);
      Objects.requireNonNull(mayObserve);
      Objects.requireNonNull(describe);
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component context() {
      return context.copy();
    }
  }
}
