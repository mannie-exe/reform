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
  public static final int MAXIMUM_CATEGORY_DEPTH = 4;

  /** Home, every category prefix, and every placement; fields have their own owner admission. */
  public static final int MAXIMUM_NAVIGATION_DESTINATIONS =
      1 + MAXIMUM_GROUPS * MAXIMUM_CATEGORY_DEPTH + MAXIMUM_PLACEMENTS;

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
    var sections = new HashMap<DocumentId, Section>();
    var complete = new HashSet<List<Category>>();
    var prefixes = new HashMap<DocumentId, List<Category>>();
    for (var group : groups) {
      var closedSections = new HashMap<DocumentId, Set<DocumentId>>();
      var currentSections = new HashMap<DocumentId, DocumentId>();
      for (var placement : group.placements()) {
        if (!placements.add(placement.id()))
          throw new IllegalArgumentException("Duplicate workflow placement");
        var section = placement.section().orElse(null);
        if (section != null) {
          var before = sections.putIfAbsent(section.id(), section);
          if (before != null && !before.equals(section))
            throw new IllegalArgumentException("A content section needs one stable title and help");
        }
        var id = section == null ? null : section.id();
        var previous = currentSections.get(placement.route());
        if (!Objects.equals(previous, id)) {
          var closed =
              closedSections.computeIfAbsent(placement.route(), ignored -> new HashSet<>());
          if (previous != null) closed.add(previous);
          if (id != null && closed.contains(id))
            throw new IllegalArgumentException(
                "Content sections must be contiguous within an owner page");
          currentSections.put(placement.route(), id);
        }
      }
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

  /** A content box, independent of navigation and source ownership. */
  public record Section(DocumentId id, Component title, Component help, Component eyebrow) {
    public Section(DocumentId id, Component title, Component help) {
      this(id, title, help, Component.empty());
    }

    public Section {
      Objects.requireNonNull(id);
      title = Objects.requireNonNull(title).copy();
      help = Objects.requireNonNull(help).copy();
      eyebrow = Objects.requireNonNull(eyebrow).copy();
      if (title.getString().isBlank()
          || title.getString().length() > 1024
          || help.getString().length() > 4096
          || eyebrow.getString().length() > 1024)
        throw new IllegalArgumentException("Section captions must be bounded");
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component help() {
      return help.copy();
    }

    @Override
    public Component eyebrow() {
      return eyebrow.copy();
    }

    public Section withEyebrow(Component eyebrow) {
      return new Section(id, title, help, eyebrow);
    }
  }

  /** Multiple placements can compose the same retained document, optionally in content boxes. */
  public record Placement(
      DocumentId id,
      DocumentId route,
      Component title,
      Optional<FieldPath> field,
      Component help,
      Set<ApplicationEffects.Timing> timings,
      Optional<Section> section) {
    public Placement(
        DocumentId id,
        DocumentId route,
        Component title,
        Optional<FieldPath> field,
        Component help,
        Set<ApplicationEffects.Timing> timings) {
      this(id, route, title, field, help, timings, Optional.empty());
    }

    public Placement(DocumentId id, DocumentId route, Component title, Optional<FieldPath> field) {
      this(id, route, title, field, Component.empty(), Set.of(), Optional.empty());
    }

    public Placement {
      Objects.requireNonNull(id);
      Objects.requireNonNull(route);
      title = Objects.requireNonNull(title).copy();
      Objects.requireNonNull(field);
      help = Objects.requireNonNull(help).copy();
      timings = Set.copyOf(timings);
      Objects.requireNonNull(section);
    }

    public Placement inSection(Section section) {
      return new Placement(id, route, title, field, help, timings, Optional.of(section));
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
      if (path.size() > MAXIMUM_CATEGORY_DEPTH
          || path.stream().map(Category::id).distinct().count() != path.size())
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
