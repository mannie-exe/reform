package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.validation.*;
import java.util.*;

/**
 * Immutable shell declaration; construction and validation never invoke owner or canvas factories.
 */
public record ClientWorkspacePresentation(
    WorkspaceIdentity identity,
    HeaderComposition header,
    Optional<HomePage> home,
    ThemePatch theme,
    Optional<DestinationId> initial,
    Map<ClientTheme.TextRole, net.minecraft.network.chat.Style> textStyles,
    TaskPresentation taskPresentation,
    Map<DestinationId, ClientReferenceTask<?, ?>> referenceTasks,
    Map<DestinationId, ClientCustomView<?>> views) {
  public ClientWorkspacePresentation {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(header);
    Objects.requireNonNull(home);
    Objects.requireNonNull(theme);
    Objects.requireNonNull(initial);
    textStyles = Map.copyOf(textStyles);
    Objects.requireNonNull(taskPresentation);
    var tasks = new LinkedHashMap<DestinationId, ClientReferenceTask<?, ?>>();
    referenceTasks.forEach(
        (key, value) -> tasks.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
    referenceTasks = Collections.unmodifiableMap(tasks);
    views = Collections.unmodifiableMap(new LinkedHashMap<>(views));
    for (var entry : views.entrySet()) {
      Objects.requireNonNull(entry.getKey());
      Objects.requireNonNull(entry.getValue());
      if (!entry.getKey().equals(entry.getValue().key().id())
          || referenceTasks.containsKey(entry.getKey()))
        throw new IllegalArgumentException("A category has one uniquely identified body");
    }
  }

  /** Preserve existing declarations without custom bodies. */
  public ClientWorkspacePresentation(
      WorkspaceIdentity identity,
      HeaderComposition header,
      Optional<HomePage> home,
      ThemePatch theme,
      Optional<DestinationId> initial,
      Map<ClientTheme.TextRole, net.minecraft.network.chat.Style> textStyles,
      TaskPresentation taskPresentation,
      Map<DestinationId, ClientReferenceTask<?, ?>> referenceTasks) {
    this(
        identity,
        header,
        home,
        theme,
        initial,
        textStyles,
        taskPresentation,
        referenceTasks,
        Map.of());
  }

  /**
   * Bind a lazy custom body to an existing category; it consumes the shared placement allowance.
   */
  public ClientWorkspacePresentation withView(ClientCustomView<?> view) {
    var next = new LinkedHashMap<>(views);
    if (next.putIfAbsent(view.key().id(), view) != null)
      throw new IllegalArgumentException("Duplicate custom view identity");
    return new ClientWorkspacePresentation(
        identity, header, home, theme, initial, textStyles, taskPresentation, referenceTasks, next);
  }

  public ClientWorkspacePresentation(
      WorkspaceIdentity identity,
      HeaderComposition header,
      Optional<HomePage> home,
      ThemePatch theme,
      Optional<DestinationId> initial) {
    this(identity, header, home, theme, initial, Map.of());
  }

  public ClientWorkspacePresentation(
      WorkspaceIdentity identity,
      HeaderComposition header,
      Optional<HomePage> home,
      ThemePatch theme,
      Optional<DestinationId> initial,
      Map<ClientTheme.TextRole, net.minecraft.network.chat.Style> textStyles) {
    this(identity, header, home, theme, initial, textStyles, TaskPresentation.standard());
  }

  public ClientWorkspacePresentation(
      WorkspaceIdentity identity,
      HeaderComposition header,
      Optional<HomePage> home,
      ThemePatch theme,
      Optional<DestinationId> initial,
      Map<ClientTheme.TextRole, net.minecraft.network.chat.Style> textStyles,
      TaskPresentation taskPresentation) {
    this(identity, header, home, theme, initial, textStyles, taskPresentation, Map.of());
  }

  /** Bind a shared task body to an existing category; registration enters no source or factory. */
  public ClientWorkspacePresentation withReferenceTask(
      DestinationId destination, ClientReferenceTask<?, ?> task) {
    var tasks = new LinkedHashMap<>(referenceTasks);
    if (tasks.putIfAbsent(Objects.requireNonNull(destination), Objects.requireNonNull(task))
        != null) throw new IllegalArgumentException("Each category has one task body");
    return new ClientWorkspacePresentation(
        identity, header, home, theme, initial, textStyles, taskPresentation, tasks, views);
  }

  public ClientWorkspacePresentation withTaskPresentation(TaskPresentation tasks) {
    return new ClientWorkspacePresentation(
        identity, header, home, theme, initial, textStyles, tasks, referenceTasks, views);
  }

  public static ClientWorkspacePresentation standard(WorkspaceIdentity identity) {
    return new ClientWorkspacePresentation(
        identity,
        HeaderComposition.standard(),
        Optional.of(HomePage.standard()),
        ThemePatch.empty(),
        Optional.empty());
  }

  public ClientWorkspacePresentation withHeader(HeaderComposition header) {
    return new ClientWorkspacePresentation(
        identity,
        header,
        home,
        theme,
        initial,
        textStyles,
        taskPresentation,
        referenceTasks,
        views);
  }

  public ClientWorkspacePresentation withHome(HomePage home) {
    return new ClientWorkspacePresentation(
        identity,
        header,
        Optional.of(home),
        theme,
        initial,
        textStyles,
        taskPresentation,
        referenceTasks,
        views);
  }

  public ClientWorkspacePresentation withoutHome() {
    return new ClientWorkspacePresentation(
        identity,
        header,
        Optional.empty(),
        theme,
        initial,
        textStyles,
        taskPresentation,
        referenceTasks,
        views);
  }

  public ClientWorkspacePresentation withTheme(ThemePatch patch) {
    return new ClientWorkspacePresentation(
        identity,
        header,
        home,
        patch,
        initial,
        textStyles,
        taskPresentation,
        referenceTasks,
        views);
  }

  public ClientWorkspacePresentation withInitialDestination(DestinationId id) {
    return new ClientWorkspacePresentation(
        identity,
        header,
        home,
        theme,
        Optional.of(id),
        textStyles,
        taskPresentation,
        referenceTasks,
        views);
  }

  public ClientWorkspacePresentation withTextStyle(
      ClientTheme.TextRole role, net.minecraft.network.chat.Style style) {
    var next =
        new EnumMap<ClientTheme.TextRole, net.minecraft.network.chat.Style>(
            ClientTheme.TextRole.class);
    next.putAll(textStyles);
    next.put(Objects.requireNonNull(role), Objects.requireNonNull(style));
    return new ClientWorkspacePresentation(
        identity, header, home, theme, initial, next, taskPresentation, referenceTasks, views);
  }

  public ClientTheme clientTheme() {
    return new ClientTheme(ThemeScope.root(theme), textStyles);
  }

  public ThemeValues values() {
    return ThemeScope.root(theme).values();
  }

  public ValidationReport validate(List<ClientForm> forms, ClientWorkflowLayout layout) {
    var issues = new ArrayList<Issue>();
    var ids = new HashSet<DestinationId>();
    if (home.isPresent()) ids.add(home.orElseThrow().id());
    if (layout == null) {
      for (var form : forms)
        if (!ids.add(new DestinationId(form.route())))
          issues.add(issue("Duplicate destination " + form.route()));
    } else {
      var categories = new HashSet<DocumentId>();
      for (int index = 0; index < layout.groups().size(); index++) {
        var group = layout.groups().get(index);
        if (group.path().isEmpty()) {
          if (!ids.add(DestinationId.parse("reform:workflow_group/" + index)))
            issues.add(issue("Duplicate group destination"));
        } else
          for (var category : group.path())
            if (categories.add(category.id()) && !ids.add(new DestinationId(category.id())))
              issues.add(issue("Duplicate category destination " + category.id()));
        for (var placement : group.placements()) {
          if (!ids.add(new DestinationId(placement.id())))
            issues.add(issue("Duplicate placement destination " + placement.id()));
          if (forms.stream().noneMatch(form -> form.route().equals(placement.route())))
            issues.add(issue("Placement names an absent owner " + placement.route()));
        }
      }
    }
    var taskCategories = new HashSet<DestinationId>();
    if (layout != null)
      for (var group : layout.groups())
        for (var category : group.path()) taskCategories.add(new DestinationId(category.id()));
    long placementCount =
        layout == null
            ? forms.size()
            : layout.groups().stream().mapToLong(group -> group.placements().size()).sum();
    if (placementCount + referenceTasks.size() + views.size()
        > ClientWorkflowLayout.MAXIMUM_PLACEMENTS)
      issues.add(issue("Task bodies and field placements exceed their shared allowance"));
    for (var id : views.keySet())
      if (!taskCategories.contains(id))
        issues.add(issue("Custom view requires a declared category: " + id));
    if (ids.isEmpty()) issues.add(issue("A workspace requires a declared destination"));
    design.inherent.reform.presentation.graphics.GraphicLimits graphicLimits = null;
    int graphicViews = 0;
    for (var task : referenceTasks.entrySet()) {
      if (!taskCategories.contains(task.getKey()))
        issues.add(issue("Reference task requires a declared category: " + task.getKey()));
      var body = task.getValue();
      if (forms.stream()
          .noneMatch(
              form ->
                  form.route().equals(body.route())
                      && form.document().equals(body.definition().id())))
        issues.add(issue("Reference task requires its declared owner: " + task.getKey()));
      if (body.graphic().isPresent()) {
        var policy = body.graphic().orElseThrow();
        if (graphicLimits != null
            && (!graphicLimits.equals(policy.aggregateLimits())
                || graphicViews != policy.maximumViews()))
          issues.add(issue("Task previews must share one workflow graphics allowance"));
        graphicLimits = policy.aggregateLimits();
        graphicViews = policy.maximumViews();
      }
    }
    initial
        .filter(id -> !ids.contains(id))
        .ifPresent(id -> issues.add(issue("Initial destination is absent: " + id)));
    home.ifPresent(
        page ->
            page.featured().stream()
                .filter(id -> !ids.contains(id) || id.equals(page.id()))
                .forEach(
                    id ->
                        issues.add(
                            issue("Featured destination is absent or refers to Home: " + id))));
    return new ValidationReport(issues, true);
  }

  private static Issue issue(String message) {
    return Issue.error("presentation.invalid_declaration", FieldPath.ROOT, message);
  }
}
