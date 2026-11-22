package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.ApplicationEffects;
import java.util.HashMap;
import java.util.HashSet;
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
  public static final int MAXIMUM_PLACEMENTS = 512;
  public static final int MAXIMUM_GROUPS = 128;

  public ClientWorkflowLayout {
    groups = List.copyOf(groups);
    reviews = List.copyOf(reviews);
    if (groups.isEmpty()
        || groups.size() > MAXIMUM_GROUPS
        || reviews.size() > 32
        || groups.stream().mapToLong(group -> group.placements().size()).sum() > MAXIMUM_PLACEMENTS
        || groups.stream().mapToLong(group -> group.actions().size()).sum() > 32
        || reviews.stream().map(ReviewScope::id).distinct().count() != reviews.size())
      throw new IllegalArgumentException(
          "Workflow groups, placements, operations and reviews require unique bounded identities");
    var placements = new HashSet<DocumentId>();
    var actions = new HashSet<DocumentId>();
    var complete = new HashSet<List<Category>>();
    var prefixes = new HashMap<DocumentId, List<Category>>();
    for (var group : groups) {
      for (var placement : group.placements())
        if (!placements.add(placement.id()))
          throw new IllegalArgumentException("Duplicate workflow placement");
      for (var action : group.actions())
        if (!actions.add(action.id()))
          throw new IllegalArgumentException("Duplicate workflow action");
      var path = group.path();
      if (!path.isEmpty() && !complete.add(path))
        throw new IllegalArgumentException("Each group path may declare one destination");
      for (int i = 0; i < path.size(); i++) {
        var prefix = List.copyOf(path.subList(0, i + 1));
        var before = prefixes.putIfAbsent(path.get(i).id(), prefix);
        if (before != null && !before.equals(prefix))
          throw new IllegalArgumentException(
              "Each group category needs one stable parent, title and description");
      }
    }
  }

  /** Navigation only. Multiple placements can open the same retained document and draft. */
  public record Placement(
      DocumentId id,
      DocumentId route,
      Component title,
      Optional<FieldPath> field,
      Component help,
      Set<ApplicationEffects.Timing> timings) {
    public Placement(DocumentId id, DocumentId route, Component title, Optional<FieldPath> field) {
      this(id, route, title, field, Component.empty(), Set.of());
    }

    public Placement {
      Objects.requireNonNull(id);
      Objects.requireNonNull(route);
      title = Objects.requireNonNull(title).copy();
      Objects.requireNonNull(field);
      help = Objects.requireNonNull(help).copy();
      timings = Set.copyOf(timings);
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  public record Category(DocumentId id, Component title, Component help) {
    public Category {
      Objects.requireNonNull(id);
      title = Objects.requireNonNull(title).copy();
      help = Objects.requireNonNull(help).copy();
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }
  }

  public record Group(
      Component title, List<Placement> placements, List<Action<?>> actions, List<Category> path) {
    public Group(Component title, List<Placement> placements, List<Action<?>> actions) {
      this(title, placements, actions, List.of());
    }

    public Group(List<Category> path, List<Placement> placements, List<Action<?>> actions) {
      this(path.getLast().title(), placements, actions, path);
    }

    public Group {
      title = Objects.requireNonNull(title).copy();
      placements = List.copyOf(placements);
      actions = List.copyOf(actions);
      path = List.copyOf(path);
      if (path.size() > 4 || path.stream().map(Category::id).distinct().count() != path.size())
        throw new IllegalArgumentException("Group paths have at most four distinct categories");
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
