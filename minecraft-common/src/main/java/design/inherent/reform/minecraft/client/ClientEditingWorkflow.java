package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormTarget;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/**
 * One explicitly opened client workflow across separately owned documents. Views retain the same
 * actual sessions; this controller neither replaces backends nor promises a cross-file transaction.
 */
public final class ClientEditingWorkflow implements AutoCloseable {
  public static final int MAXIMUM_DOCUMENTS = 32;

  /** Cached presentation only, never source readiness, authorization or a submission receipt. */
  public record DocumentState(
      DocumentId document,
      Component title,
      boolean opened,
      boolean available,
      boolean dirty,
      boolean busy,
      Component summary,
      DocumentId ownerDocument) {
    public DocumentState(
        DocumentId document,
        Component title,
        boolean opened,
        boolean available,
        boolean dirty,
        boolean busy,
        Component summary) {
      this(document, title, opened, available, dirty, busy, summary, document);
    }

    public DocumentState {
      Objects.requireNonNull(document);
      Objects.requireNonNull(ownerDocument);
      title = Objects.requireNonNull(title).copy();
      summary = Objects.requireNonNull(summary).copy();
    }

    @Override
    public Component title() {
      return title.copy();
    }

    @Override
    public Component summary() {
      return summary.copy();
    }
  }

  private static final class Contributed<O> {
    final ClientWorkflowLayout.Action<O> action;
    OperationHandle<O> latest;

    Contributed(ClientWorkflowLayout.Action<O> action) {
      this.action = action;
    }

    boolean busy() {
      return latest != null && !latest.snapshot().terminal();
    }
  }

  private record ContributionState(boolean busy, Optional<Component> blocked, boolean observable) {}

  private static final class Entry {
    final ClientForm form;
    ClientEditor<?> editor;
    ClientForm.PendingEditor opening;
    CompletableFuture<ClientEditor<?>> waiting;
    boolean retiring;

    Entry(ClientForm form) {
      this.form = form;
    }
  }

  private record TaskEntry(
      OwnerContext lifetime, ReferenceTaskView<?, ?> view, ClientEditor<?> owner) {}

  private final Map<DestinationId, TaskEntry> taskViews = new LinkedHashMap<>();

  private record CustomEntry(OwnerContext lifetime, CustomView view) {}

  private final Map<DestinationId, CustomEntry> customViews = new LinkedHashMap<>();

  private CustomView customView(DestinationId id) {
    var existing = customViews.get(id);
    if (existing != null) return existing.view();
    var declaration = presentation.views().get(id);
    if (declaration == null) throw missingDestination();
    var client = Minecraft.getInstance();
    var owner =
        new OwnerContext(
            client::execute,
            ClientSettingsRuntime.workers(),
            ClientSettingsRuntime::requireClientThread,
            OwnerContext.Limits.STANDARD);
    var closing = lifetime.onClose(owner::close);
    try {
      var view =
          declaration.open(
              new ClientViewContext(owner),
              () -> {
                var retained = customViews.get(id);
                if (retained == null) return;
                retained.view().refresh();
                if (isOpen()
                    && overview.selectedKey != null
                    && overview.selectedKey.equals(overview.category(id.value()))
                    && NativeScreens.current(client) == overview) overview.rebuildWidgets();
              });
      requireOpen();
      owner.requireOwner();
      customViews.put(id, new CustomEntry(owner, view));
      owner.onClose(closing::close);
      return view;
    } catch (RuntimeException | Error failure) {
      try {
        owner.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      closing.close();
      throw failure;
    }
  }

  private ReferenceTaskView<?, ?> taskView(DestinationId id) {
    var existing = taskViews.get(id);
    if (existing != null) return existing.view();
    var declaration = presentation.referenceTasks().get(id);
    if (declaration == null) throw missingDestination();
    var actual = entry(declaration.route()).editor;
    if (actual == null || !actual.isOpen())
      throw new PolicyRefusal("client.editor_unavailable", "Open this task's owner first");
    var client = Minecraft.getInstance();
    var owner =
        new OwnerContext(
            client::execute,
            ClientSettingsRuntime.workers(),
            ClientSettingsRuntime::requireClientThread,
            OwnerContext.Limits.STANDARD);
    var closing = lifetime.onClose(owner::close);
    try {
      var view = declaration.open(actual, new ClientViewContext(owner), this);
      requireOpen();
      owner.requireOwner();
      taskViews.put(id, new TaskEntry(owner, view, actual));
      owner.onClose(closing::close);
      return view;
    } catch (RuntimeException | Error failure) {
      try {
        owner.close();
      } catch (RuntimeException | Error cleanup) {
        failure.addSuppressed(cleanup);
      }
      closing.close();
      throw failure;
    }
  }

  private final LinkedHashMap<DocumentId, Entry> entries = new LinkedHashMap<>();
  private final Map<DocumentId, Entry> ownerEntries = new LinkedHashMap<>();
  private final Map<DocumentId, ClientWorkflowLayout.Placement> placements = new LinkedHashMap<>();
  private final Map<DocumentId, ClientWorkflowLayout.ReviewScope> reviews = new LinkedHashMap<>();
  private final Map<DocumentId, ClientWorkflowLayout.ReviewScope> guarded = new LinkedHashMap<>();
  private final Map<DocumentId, Contributed<?>> contributions = new LinkedHashMap<>();
  private final ClientWorkflowLayout layout;
  private final ClientWorkspacePresentation presentation;
  private final OwnerContext lifetime;
  private final OwnerContext.Access batchAccess;
  private final Overview overview;
  private final DocumentBatch.Validator validator;
  private DocumentBatch batch;
  private BatchReviewScreen batchView;
  private boolean closed;
  private ClientGraphicHost graphics;
  private final ClientNavigation navigation = new ClientNavigation(this);
  private final UUID navigationIdentity = UUID.randomUUID();
  private NavigationAttempt pendingNavigation;
  private final Map<UUID, NavigationBookmark> navigationBookmarks = new LinkedHashMap<>();

  /** Typed presentation links over this workflow's exact retained owners. */
  public ClientNavigation navigation() {
    ClientSettingsRuntime.requireClientThread();
    return navigation;
  }

  /** Cached capture from the retained inspection, independent of the native screen wrapper. */
  public Optional<ClientInspection> inspection() {
    ClientSettingsRuntime.requireClientThread();
    return isOpen() ? Optional.ofNullable(overview.inspection) : Optional.empty();
  }

  private ClientDestination destinationFor(WorkspaceNavigation.Key key) {
    if (key.kind() == WorkspaceNavigation.Kind.INSPECTION)
      return new ClientDestination.Inspection(key.placement());
    if (key.kind() == WorkspaceNavigation.Kind.HOME) return new ClientDestination.Home();
    if (key.kind() == WorkspaceNavigation.Kind.CATEGORY) {
      var id = new DestinationId(key.placement());
      return presentation.views().containsKey(id) || presentation.referenceTasks().containsKey(id)
          ? new ClientDestination.View(id)
          : new ClientDestination.Category(id);
    }
    var placement =
        overview.allPlacements().stream()
            .filter(item -> item.id().equals(key.placement()))
            .findFirst()
            .orElseThrow(ClientEditingWorkflow::missingDestination);
    var alias = Optional.of(new DestinationId(placement.id()));
    var target = key.target().or(() -> placement.field().map(FormTarget.Field::new));
    return target.orElse(null) instanceof FormTarget.Field field
        ? new ClientDestination.Field(placement.route(), field.path(), alias)
        : target.orElse(null) instanceof FormTarget.Section section
            ? new ClientDestination.Section(placement.route(), section, alias)
            : new ClientDestination.Placement(alias.orElseThrow());
  }

  Optional<ClientDestination> currentDestination() {
    ClientSettingsRuntime.requireClientThread();
    if (isOpen() && overview.inspection != null)
      return Optional.of(new ClientDestination.Inspection(overview.inspectionRoute));
    return !isOpen() || overview.selectedKey == null
        ? Optional.empty()
        : Optional.of(destinationFor(overview.selectedKey));
  }

  Optional<NavigationAttempt> navigateUp() {
    requireOverview();
    if (overview.inspection != null)
      return Optional.of(openDestination(destinationFor(overview.selectedKey)));
    var current =
        overview.navigationEntries(true).stream()
            .filter(node -> node.key().equals(overview.selectedKey))
            .findFirst();
    var parent = current.flatMap(WorkspaceNavigation.Entry::parent);
    if (parent.isPresent())
      return Optional.of(openDestination(destinationFor(parent.orElseThrow())));
    return presentation.home().isPresent() && !overview.isHome()
        ? Optional.of(openDestination(new ClientDestination.Home()))
        : Optional.empty();
  }

  NavigationBookmark navigationBookmark() {
    requireOverview();
    overview.beforeRebuild();
    var inspected = overview.inspection == null ? null : overview.inspection.bookmark();
    var position = overview.navigation.position(overview.selectedKey).orElse(null);
    return new NavigationBookmark(
        navigationIdentity,
        currentDestination().orElseThrow(),
        Optional.ofNullable(position == null ? null : position.scroll()),
        Optional.ofNullable(position == null ? null : position.focus()),
        Optional.of(
            new NavigationBookmark.NavigationState(
                overview.selectedFilter.name(), overview.navigation.uiState(), inspected)));
  }

  NavigationAttempt restoreBookmark(NavigationBookmark bookmark) {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen()) return openDestination(bookmark.destination());
    if (!navigationIdentity.equals(bookmark.workflow())) {
      var refused = new NavigationAttempt(bookmark.destination());
      refused.finish(
          NavigationAttempt.Outcome.REFUSED,
          new PolicyRefusal(
                  "navigation.bookmark_context",
                  "Return to the workflow that created this bookmark.")
              .report(),
          null);
      return refused;
    }
    return openDestination(bookmark.destination(), bookmark);
  }

  private record NavigationSelection(
      WorkspaceNavigation.Key key,
      List<ClientWorkflowLayout.Category> category,
      Optional<DocumentId> route,
      Optional<FormTarget> target) {}

  private static PolicyRefusal missingDestination() {
    return new PolicyRefusal(
        "navigation.target_missing", "That destination is absent from this workflow or draft.");
  }

  private NavigationSelection resolveDestination(ClientDestination target) {
    if (target instanceof ClientDestination.Inspection inspection)
      target = new ClientDestination.Document(inspection.route());
    if (target instanceof ClientDestination.View view) {
      if (!presentation.referenceTasks().containsKey(view.id())
          && !presentation.views().containsKey(view.id())) throw missingDestination();
      target = new ClientDestination.Category(view.id());
    }
    if (target instanceof ClientDestination.Home) {
      if (presentation.home().isEmpty()) throw missingDestination();
      return new NavigationSelection(
          overview.homeKey(), List.of(), Optional.empty(), Optional.empty());
    }
    if (target instanceof ClientDestination.Category category) {
      if (layout == null) throw missingDestination();
      for (int index = 0; index < layout.groups().size(); index++) {
        var group = layout.groups().get(index);
        if (group.path().isEmpty()
            && overview.groupKey(index).placement().equals(category.id().value()))
          return new NavigationSelection(
              overview.groupKey(index), List.of(), Optional.empty(), Optional.empty());
        for (int depth = 0; depth < group.path().size(); depth++)
          if (group.path().get(depth).id().equals(category.id().value()))
            return new NavigationSelection(
                overview.category(category.id().value()),
                List.copyOf(group.path().subList(0, depth + 1)),
                Optional.ofNullable(presentation.referenceTasks().get(category.id()))
                    .map(ClientReferenceTask::route),
                Optional.empty());
      }
      throw missingDestination();
    }
    DocumentId route;
    Optional<DestinationId> alias = Optional.empty();
    Optional<FormTarget> focus = Optional.empty();
    if (target instanceof ClientDestination.Placement placement) {
      var declared =
          overview.allPlacements().stream()
              .filter(item -> item.id().equals(placement.id().value()))
              .findFirst()
              .orElseThrow(ClientEditingWorkflow::missingDestination);
      route = declared.route();
      alias = Optional.of(placement.id());
      focus = declared.field().map(FormTarget.Field::new);
    } else if (target instanceof ClientDestination.Document document) route = document.route();
    else if (target instanceof ClientDestination.Field field) {
      route = field.route();
      alias = field.placement();
      focus = Optional.of(new FormTarget.Field(field.path()));
    } else if (target instanceof ClientDestination.Section section) {
      route = section.route();
      alias = section.placement();
      focus = Optional.of(section.section());
    } else throw missingDestination();
    if (!entries.containsKey(route)) throw missingDestination();
    final var requestedAlias = alias;
    final var requestedFocus = focus;
    var placement =
        overview.allPlacements().stream()
            .filter(item -> item.route().equals(route))
            .filter(
                item ->
                    requestedAlias.isEmpty()
                        || item.id().equals(requestedAlias.orElseThrow().value()))
            .filter(
                item ->
                    !(requestedFocus.orElse(null) instanceof FormTarget.Field field)
                        || item.field().isEmpty()
                        || field.path().startsWith(item.field().orElseThrow()))
            .findFirst()
            .orElseThrow(ClientEditingWorkflow::missingDestination);
    List<ClientWorkflowLayout.Category> path = List.of();
    if (layout != null)
      for (var group : layout.groups())
        if (group.placements().contains(placement)) {
          path = group.path();
          break;
        }
    var key =
        focus.isPresent()
            ? WorkspaceNavigation.Key.form(placement.id(), focus.orElseThrow())
            : overview.destination(placement.id());
    return new NavigationSelection(key, path, Optional.of(route), focus);
  }

  DestinationAvailability inspectDestination(ClientDestination target) {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen())
      return availability(
          DestinationAvailability.Kind.CLOSED, "navigation.closed", "This workflow is closed.");
    if (target instanceof ClientDestination.Operation operation)
      return contributions.containsKey(operation.id())
          ? availability(DestinationAvailability.Kind.READY, null, null)
          : availability(
              DestinationAvailability.Kind.MISSING,
              "navigation.target_missing",
              "That operation is not declared here.");
    if (target instanceof ClientDestination.OperationResult result) {
      var entry = contributions.get(result.id());
      if (entry == null
          || entry.latest == null
          || !entry.latest.snapshot().id().equals(result.handle()))
        return availability(
            DestinationAvailability.Kind.MISSING,
            "navigation.result_missing",
            "That operation result is no longer retained.");
      return entry.action.mayObserve().getAsBoolean()
          ? availability(DestinationAvailability.Kind.READY, null, null)
          : availability(
              DestinationAvailability.Kind.UNAVAILABLE,
              "navigation.result_unavailable",
              "This context cannot inspect that operation result.");
    }
    try {
      var selected = resolveDestination(target);
      if (selected.route().isEmpty())
        return availability(DestinationAvailability.Kind.READY, null, null);
      var entry = entry(selected.route().orElseThrow());
      if (entry.retiring)
        return availability(
            DestinationAvailability.Kind.CLOSED,
            "client.editor_retiring",
            "This editing session is closing.");
      if (entry.editor == null) {
        var cached = entry.form.availability();
        if (!cached.ready())
          return availability(
              DestinationAvailability.Kind.UNAVAILABLE,
              "client.document_unavailable",
              cached.message().getString());
        return availability(
            entry.opening == null
                ? DestinationAvailability.Kind.UNOPENED
                : DestinationAvailability.Kind.PENDING,
            null,
            null);
      }
      if (!entry.editor.isOpen())
        return availability(
            DestinationAvailability.Kind.CLOSED, "editor.closed", "This editing context ended.");
      var observed = entry.editor.view();
      if (observed.availability() == EditorActor.Availability.LOADING)
        return availability(DestinationAvailability.Kind.PENDING, null, null);
      if (observed.availability() != EditorActor.Availability.READY)
        return new DestinationAvailability(
            DestinationAvailability.Kind.UNAVAILABLE,
            observed.observationReport(),
            navigationRecovery());
      if (selected.target().orElse(null) instanceof FormTarget.Field field) {
        if (observed
            .snapshot()
            .filter(snapshot -> snapshot.draft().contains(field.path()))
            .isEmpty()) throw missingDestination();
        if (!observed.snapshot().orElseThrow().draft().activeAt(field.path()))
          return availability(
              DestinationAvailability.Kind.UNAVAILABLE,
              "navigation.target_inactive",
              "That retained field is inactive in the current draft.");
      }
      // Layout-section existence is unknown until this actual form has projected its model.
      if (selected.target().orElse(null) instanceof FormTarget.Section section) {
        var known = entry.editor.presentation().navigationTargets();
        if (known.isEmpty()) return availability(DestinationAvailability.Kind.UNOPENED, null, null);
        if (known.stream().noneMatch(node -> node.id().equals(section))) throw missingDestination();
      }
      return availability(DestinationAvailability.Kind.READY, null, null);
    } catch (PolicyRefusal missing) {
      return new DestinationAvailability(
          DestinationAvailability.Kind.MISSING, missing.report(), navigationRecovery());
    }
  }

  private DestinationAvailability availability(
      DestinationAvailability.Kind kind, String code, String message) {
    var report = code == null ? ValidationReport.VALID : new PolicyRefusal(code, message).report();
    return new DestinationAvailability(kind, report, navigationRecovery());
  }

  private java.util.Set<DestinationAvailability.Recovery> navigationRecovery() {
    return isOpen() && overview.navigation.canBack()
        ? java.util.Set.of(DestinationAvailability.Recovery.BACK)
        : java.util.Set.of();
  }

  private void supersedeNavigation() {
    if (pendingNavigation == null) return;
    var old = pendingNavigation;
    pendingNavigation = null;
    old.finish(
        isOpen() ? NavigationAttempt.Outcome.SUPERSEDED : NavigationAttempt.Outcome.CLOSED,
        new PolicyRefusal(
                isOpen() ? "navigation.superseded" : "navigation.closed",
                isOpen() ? "A newer destination was selected." : "This workflow is closed.")
            .report(),
        null);
  }

  NavigationAttempt openDestination(ClientDestination target) {
    return openDestination(target, null);
  }

  private NavigationAttempt openDestination(ClientDestination target, NavigationBookmark bookmark) {
    ClientSettingsRuntime.requireClientThread();
    var attempt = new NavigationAttempt(target);
    attempt
        .completion()
        .thenAccept(
            result -> {
              navigationBookmarks.remove(attempt.id());
              if (!isOpen() || overview.navigationRequest != attempt.id()) return;
              if (result.outcome() == NavigationAttempt.Outcome.REVEALED
                  || result.outcome() == NavigationAttempt.Outcome.HANDED_OFF) return;
              if (result.outcome() != NavigationAttempt.Outcome.SUPERSEDED) {
                overview.notice =
                    ClientStatusScreen.reason(
                        result.report(), text("unavailable", "That destination is unavailable."));
                if (NativeScreens.current(Minecraft.getInstance()) == overview)
                  overview.rebuildWidgets();
              }
            });
    boolean restoring = overview.navigation.restoring();
    if (!restoring) overview.navigation.cancelRestoration();
    overview.navigationRequest = attempt.id();
    supersedeNavigation();
    if (overview.navigationRequest != attempt.id()) {
      attempt.finish(
          NavigationAttempt.Outcome.SUPERSEDED,
          new PolicyRefusal("navigation.superseded", "A newer destination was selected.").report(),
          null);
      return attempt;
    }
    if (bookmark != null) navigationBookmarks.put(attempt.id(), bookmark);
    try {
      requireOverview();
      var available = inspectDestination(target);
      if (available.kind() == DestinationAvailability.Kind.MISSING
          || available.kind() == DestinationAvailability.Kind.UNAVAILABLE
          || available.kind() == DestinationAvailability.Kind.CLOSED) {
        var outcome =
            available.kind() == DestinationAvailability.Kind.MISSING
                ? NavigationAttempt.Outcome.MISSING
                : available.kind() == DestinationAvailability.Kind.CLOSED
                    ? NavigationAttempt.Outcome.CLOSED
                    : NavigationAttempt.Outcome.UNAVAILABLE;
        overview.navigation.cancelRestoration();
        attempt.finish(outcome, available.report(), null);
        return attempt;
      }
      if (target instanceof ClientDestination.Operation
          || target instanceof ClientDestination.OperationResult) {
        pendingNavigation = attempt;
        finishOperationNavigation(attempt);
        return attempt;
      }
      var selected = resolveDestination(target);
      pendingNavigation = attempt;
      if (selected.route().isEmpty()) {
        finishReveal(attempt, selected, null, restoring, null);
      } else {
        var retained = entry(selected.route().orElseThrow()).editor;
        if (retained != null
            && retained.isOpen()
            && retained.view().availability() == EditorActor.Availability.READY)
          finishReveal(attempt, selected, retained, restoring, null);
        else
          openEditor(selected.route().orElseThrow())
              .thenCompose(
                  editor ->
                      DocumentActions.result(editor.actions().ensureLoaded(EntryPoint.GUI))
                          .thenApply(ignored -> editor))
              .whenComplete(
                  (editor, failure) ->
                      Minecraft.getInstance()
                          .execute(
                              () -> finishReveal(attempt, selected, editor, restoring, failure)));
      }
    } catch (RuntimeException failed) {
      if (pendingNavigation == attempt) pendingNavigation = null;
      finishNavigationFailure(attempt, failed);
    }
    return attempt;
  }

  private void finishReveal(
      NavigationAttempt attempt,
      NavigationSelection selected,
      ClientEditor<?> editor,
      boolean restoring,
      Throwable failure) {
    if (pendingNavigation != attempt || overview.navigationRequest != attempt.id()) return;
    if (overview.deferNavigation(() -> finishReveal(attempt, selected, editor, restoring, failure)))
      return;
    pendingNavigation = null;
    try {
      requireOverview();
      if (failure != null) throw new CompletionException(failure);
      if (editor != null) editor.actions().requireInspection();
      if (attempt.target() instanceof ClientDestination.Inspection
          && !Objects.requireNonNull(editor).presentation().acceptsInspection())
        throw new PolicyRefusal(
            "inspection.input_unaccepted",
            "Finish or repair the current field input before opening inspection.");
      var available = inspectDestination(attempt.target());
      if (available.kind() == DestinationAvailability.Kind.MISSING
          || available.kind() == DestinationAvailability.Kind.UNAVAILABLE) {
        overview.navigation.cancelRestoration();
        attempt.finish(
            available.kind() == DestinationAvailability.Kind.MISSING
                ? NavigationAttempt.Outcome.MISSING
                : NavigationAttempt.Outcome.UNAVAILABLE,
            available.report(),
            null);
        return;
      }
      if (selected.target().isPresent()
          && !Objects.requireNonNull(editor)
              .presentation()
              .navigationTargetExists(selected.target().orElseThrow())) throw missingDestination();
      var bookmark = navigationBookmarks.get(attempt.id());
      overview.mountDestination(
          selected,
          editor,
          restoring,
          bookmark == null ? null : bookmark.navigation().orElse(null),
          attempt.target() instanceof ClientDestination.Inspection);
      if (attempt.target() instanceof ClientDestination.Inspection requested) {
        overview.revealInspection(requested.route(), Objects.requireNonNull(editor));
      }
      if (bookmark != null) overview.restorePosition(bookmark);
      attempt.finish(NavigationAttempt.Outcome.REVEALED, ValidationReport.VALID, null);
    } catch (RuntimeException failed) {
      finishNavigationFailure(attempt, failed);
    }
  }

  private void finishOperationNavigation(NavigationAttempt attempt) {
    if (pendingNavigation != attempt || overview.navigationRequest != attempt.id()) return;
    if (overview.deferNavigation(() -> finishOperationNavigation(attempt))) return;
    pendingNavigation = null;
    try {
      requireOverview();
      var available = inspectDestination(attempt.target());
      if (available.kind() != DestinationAvailability.Kind.READY) {
        attempt.finish(
            available.kind() == DestinationAvailability.Kind.MISSING
                ? NavigationAttempt.Outcome.MISSING
                : NavigationAttempt.Outcome.UNAVAILABLE,
            available.report(),
            null);
        return;
      }
      if (attempt.target() instanceof ClientDestination.Operation operation) {
        overview.revealOperation(contributions.get(operation.id()));
        attempt.finish(NavigationAttempt.Outcome.REVEALED, ValidationReport.VALID, null);
      } else {
        var result = (ClientDestination.OperationResult) attempt.target();
        inspectOperation(contributions.get(result.id()));
        attempt.finish(NavigationAttempt.Outcome.HANDED_OFF, ValidationReport.VALID, null);
      }
    } catch (RuntimeException failed) {
      finishNavigationFailure(attempt, failed);
    }
  }

  private void finishNavigationFailure(NavigationAttempt attempt, Throwable failure) {
    overview.navigation.cancelRestoration();
    while (failure instanceof CompletionException && failure.getCause() != null)
      failure = failure.getCause();
    var outcome =
        !isOpen()
            ? NavigationAttempt.Outcome.CLOSED
            : failure instanceof PolicyRefusal refused
                ? refused.report().issues().stream()
                        .anyMatch(
                            issue ->
                                java.util.Set.of(
                                        "editor.closed",
                                        "owner.closed",
                                        "client.workflow_closed",
                                        "client.editor_unavailable")
                                    .contains(issue.code()))
                    ? NavigationAttempt.Outcome.CLOSED
                    : refused.report().issues().stream()
                            .anyMatch(issue -> issue.code().equals("navigation.target_missing"))
                        ? NavigationAttempt.Outcome.MISSING
                        : NavigationAttempt.Outcome.REFUSED
                : NavigationAttempt.Outcome.FAILED;
    var report =
        failure instanceof PolicyRefusal refused
            ? refused.report()
            : new PolicyRefusal(
                    "navigation.failed",
                    "The destination could not be opened; the previous view is retained.")
                .report();
    attempt.finish(outcome, report, failure);
  }

  /** One explicit aggregate graphics budget and preparation lane for this workflow. */
  public ClientGraphicHost graphics(
      design.inherent.reform.presentation.graphics.GraphicLimits limits, int maximumViews) {
    requireOpen();
    if (graphics == null)
      graphics =
          new ClientGraphicHost(
              lifetime,
              editor -> ownerEntries.values().stream().anyMatch(entry -> entry.editor == editor),
              limits,
              maximumViews);
    else if (!graphics.limits().equals(limits) || graphics.maximumViews() != maximumViews)
      throw new IllegalArgumentException("A workflow's graphics budget cannot change after entry");
    return graphics;
  }

  private ClientEditingWorkflow(
      Screen parent,
      Component title,
      List<ClientForm> forms,
      DocumentBatch.Validator validator,
      ClientWorkflowLayout layout,
      ClientWorkspacePresentation presentation) {
    ClientSettingsRuntime.requireClientThread();
    Objects.requireNonNull(title);
    this.validator = Objects.requireNonNull(validator);
    this.layout = layout;
    this.presentation = Objects.requireNonNull(presentation);
    var declaration = presentation.validate(forms, layout);
    if (!declaration.valid()) throw new PolicyRefusal(declaration);
    var declared = List.copyOf(forms);
    if (declared.isEmpty() || declared.size() > MAXIMUM_DOCUMENTS)
      throw new IllegalArgumentException("A client workflow requires between 1 and 32 documents");
    for (var form : declared) {
      var entry = new Entry(form);
      if (entries.putIfAbsent(form.route(), entry) != null
          || ownerEntries.putIfAbsent(form.document(), entry) != null)
        throw new IllegalArgumentException(
            "Register each workflow route and owner once. Use placements for additional views.");
    }
    if (layout != null) admitLayout(layout);
    var client = Minecraft.getInstance();
    lifetime =
        new OwnerContext(
            client::execute,
            ClientSettingsRuntime.workers(),
            ClientSettingsRuntime::requireClientThread,
            OwnerContext.Limits.STANDARD);
    overview = new Overview(parent, title.copy());
    try {
      ClientSettingsRuntime.manageContext(lifetime);
      batchAccess = lifetime.access(() -> true, () -> true);
      lifetime.onClose(this::retire);
    } catch (RuntimeException | Error failed) {
      lifetime.close();
      throw failed;
    }
  }

  /**
   * Call on the native client thread when entering the UI, not during mod metadata registration.
   */
  public static ClientEditingWorkflow open(Screen parent, Component title, List<ClientForm> forms) {
    return open(parent, title, forms, ignored -> DocumentBatch.Check.VALID);
  }

  public static ClientEditingWorkflow open(
      Screen parent, Component title, List<ClientForm> forms, DocumentBatch.Validator validator) {
    return new ClientEditingWorkflow(
        parent,
        title,
        forms,
        validator,
        null,
        ClientWorkspacePresentation.standard(
            new WorkspaceIdentity(DocumentId.parse("reform:workspace"), title)));
  }

  public static ClientEditingWorkflow open(
      Screen parent, Component title, List<ClientForm> forms, ClientWorkflowLayout layout) {
    return new ClientEditingWorkflow(
        parent,
        title,
        forms,
        ignored -> DocumentBatch.Check.VALID,
        Objects.requireNonNull(layout),
        ClientWorkspacePresentation.standard(
            new WorkspaceIdentity(DocumentId.parse("reform:workspace"), title)));
  }

  public static ClientEditingWorkflow open(
      Screen parent, List<ClientForm> forms, ClientWorkspacePresentation presentation) {
    return open(parent, forms, null, presentation);
  }

  public static ClientEditingWorkflow open(
      Screen parent,
      List<ClientForm> forms,
      ClientWorkflowLayout layout,
      ClientWorkspacePresentation presentation) {
    return new ClientEditingWorkflow(
        parent,
        presentation.identity().title(),
        forms,
        ignored -> DocumentBatch.Check.VALID,
        layout,
        presentation);
  }

  public void showHome() {
    requireOverview();
    overview.selectHome();
  }

  private void admitLayout(ClientWorkflowLayout layout) {
    for (var group : layout.groups()) {
      for (var view : group.placements()) {
        entry(view.route());
        placements.put(view.id(), view);
      }
      for (var action : group.actions()) contributions.put(action.id(), new Contributed<>(action));
    }
    for (var scope : layout.reviews()) {
      reviews.put(scope.id(), scope);
      scope.checked().forEach(this::entry);
      for (var route : scope.writable()) {
        entry(route);
        if (scope.requiredOnSave() && guarded.putIfAbsent(route, scope) != null)
          throw new IllegalArgumentException("An owner can require only one save scope");
      }
    }
    for (var scope : reviews.values())
      for (var route : scope.writable())
        if (guarded.getOrDefault(route, scope) != scope)
          throw new IllegalArgumentException(
              "A review cannot bypass an owner's required save scope");
    if (!placements.values().stream()
        .map(ClientWorkflowLayout.Placement::route)
        .collect(java.util.stream.Collectors.toSet())
        .containsAll(entries.keySet()))
      throw new IllegalArgumentException("Every registered owner needs a placement");
  }

  public boolean isOpen() {
    return !closed && lifetime.isOpen();
  }

  public Screen screen() {
    requireOpen();
    return overview;
  }

  /** Read-authorized displayed information; this does not refresh a source or authorize effects. */
  public Optional<design.inherent.reform.operation.FieldInformation> inspectedField() {
    requireOpen();
    return overview.information.observation();
  }

  /** Navigate from this workflow's overview, keeping its actual owner/session lifetime. */
  public void show(DocumentId document) {
    requireOpen();
    if (NativeScreens.current(Minecraft.getInstance()) != overview)
      throw new PolicyRefusal("client.workflow_view", "Return to this workflow's overview first");
    overview.openDocument(document, Optional.empty());
  }

  public void showPlacement(DocumentId id) {
    requireOverview();
    var placement = placements.get(Objects.requireNonNull(id));
    if (placement == null)
      throw new IllegalArgumentException("This placement is not in this workflow");
    overview.selectPlacement(placement);
  }

  /** Typed access checks the exact registered definition, rather than trusting only its ID. */
  public <T> ClientEditor<T> editor(
      design.inherent.reform.definition.DocumentDefinition<T> definition) {
    var editor = editor(Objects.requireNonNull(definition).id());
    if (editor.actions().definition() != definition)
      throw new IllegalArgumentException(
          "Use the exact definition registered by this workflow owner");
    @SuppressWarnings("unchecked") // Exact definition identity binds the actor's value type.
    var typed = (ClientEditor<T>) editor;
    return typed;
  }

  /** Open at most one real editor per document. Independent workflows keep independent actors. */
  public ClientEditor<?> editor(DocumentId document) {
    requireOpen();
    var entry = entry(document);
    if (entry.retiring)
      throw new PolicyRefusal("client.editor_retiring", "This editing session is closing.");
    if (entry.editor != null) {
      if (!entry.editor.isOpen())
        throw new PolicyRefusal(
            "client.editor_unavailable",
            "This editing context ended; explicitly discard it before opening another");
      return entry.editor;
    }
    return retain(entry, entry.form.open(overview));
  }

  /** Open an actual native owner asynchronously when a host admission is needed. */
  public CompletionStage<ClientEditor<?>> openEditor(DocumentId document) {
    requireOpen();
    var entry = entry(document);
    if (entry.editor != null || !entry.form.asynchronous())
      return CompletableFuture.completedStage(editor(document));
    if (entry.waiting != null) return entry.waiting.minimalCompletionStage();
    var request = entry.form.begin(overview);
    var result = new CompletableFuture<ClientEditor<?>>();
    var client = Minecraft.getInstance();
    entry.opening = request;
    entry.waiting = result;
    request
        .result()
        .whenComplete(
            (opened, failed) ->
                client.execute(
                    () -> {
                      if (entry.opening == request) {
                        entry.opening = null;
                        entry.waiting = null;
                      }
                      if (failed != null) {
                        try {
                          request.cancel().run();
                        } catch (RuntimeException | Error cleanup) {
                          if (cleanup != failed) failed.addSuppressed(cleanup);
                        }
                        result.completeExceptionally(failed);
                      } else {
                        try {
                          result.complete(retain(entry, opened));
                        } catch (RuntimeException | Error rejected) {
                          result.completeExceptionally(rejected);
                        }
                      }
                    }));
    return result.minimalCompletionStage();
  }

  private ClientEditor<?> retain(Entry entry, ClientEditor<?> opened) {
    try {
      requireOpen();
      opened.bindHost(overview);
      opened.onAbandoned(this::close);
      var required = guarded.get(entry.form.route());
      if (required != null)
        opened
            .presentation()
            .withScopedSave(required.title(), () -> beginReview(required, overview));
      entry.editor = opened;
      return opened;
    } catch (RuntimeException | Error failed) {
      opened.close();
      throw failed;
    }
  }

  /**
   * Explicitly retire one draft. This does not undo a completed write or cancel an entered effect;
   * ordinary navigation refuses retirement while a known operation is pending.
   */
  public void discard(DocumentId document) {
    requireOpen();
    if (batch != null && batch.hasActiveOperations())
      throw new PolicyRefusal("client.batch_busy", "Wait for the batch operation to settle");
    var entry = entry(document);
    if (entry.retiring)
      throw new PolicyRefusal("client.editor_retiring", "This editing session is closing.");
    if (entry.editor == null) return;
    if (entry.editor.isOpen() && entry.editor.busy())
      throw new PolicyRefusal("client.editor_busy", "Wait for this document's pending operation");
    var previous = entry.editor;
    entry.retiring = true;
    Throwable failure = null;
    try {
      overview.retireInspection(previous.document());
      try {
        previous.close();
      } catch (RuntimeException | Error failed) {
        failure = failed;
      }
      for (var item : List.copyOf(taskViews.entrySet())) {
        if (item.getValue().owner() != previous) continue;
        taskViews.remove(item.getKey());
        try {
          item.getValue().lifetime().close();
        } catch (RuntimeException | Error failed) {
          if (failure == null) failure = failed;
          else if (failure != failed) failure.addSuppressed(failed);
        }
      }
    } finally {
      entry.editor = null;
      entry.retiring = false;
    }
    if (failure != null) {
      var refused =
          new PolicyRefusal(
              "client.document_retirement_failed",
              "The editing session closed, but some resources could not be released. Reopen the document before editing again.");
      refused.initCause(failure);
      throw refused;
    }
  }

  /** Local cached views only; no validation, source IO, source capture or permission lookup. */
  public List<DocumentState> documents() {
    requireOpen();
    return entries.values().stream().map(this::state).toList();
  }

  private DocumentState state(Entry entry) {
    var editor = entry.editor;
    if (editor == null) {
      if (entry.opening != null)
        return new DocumentState(
            entry.form.route(),
            entry.form.title(),
            false,
            false,
            false,
            true,
            text("opening", "Checking the actual editing context…"),
            entry.form.document());
      var available = entry.form.availability();
      return new DocumentState(
          entry.form.route(),
          entry.form.title(),
          false,
          available.ready(),
          false,
          false,
          available.ready()
              ? text("unopened", "Not opened; saved and effective values have not been inspected.")
              : available.message(),
          entry.form.document());
    }
    var view = editor.view();
    Component summary;
    if (!editor.isOpen())
      summary =
          text(
              "context_ended",
              "Editing context ended; opening a new one requires discarding this draft.");
    else if (editor.busy())
      summary = text("pending", "Operation pending; wait for its actual result.");
    else if (view.availability() != EditorActor.Availability.READY)
      summary =
          ClientStatusScreen.reason(
              view.observationReport(), text("unavailable", "Document unavailable"));
    else if (view.review().isPresent())
      summary = text("conflict", "Source changed; review this document's conflict.");
    else if (!view.observationReport().valid())
      summary =
          ClientStatusScreen.reason(
              view.observationReport(), text("unavailable", "Document unavailable"));
    else if (!view.operationReport().valid())
      summary =
          ClientStatusScreen.reason(
              view.operationReport(), text("refused", "The last operation was refused"));
    else if (!view.validation().valid())
      summary =
          ClientStatusScreen.reason(view.validation(), text("invalid", "Invalid draft retained"));
    else
      summary =
          editor.dirty()
              ? text("dirty", "Unsaved draft retained")
              : text("clean", "No unsaved edits");
    if (view.lastReceipt().isPresent())
      summary =
          summary
              .copy()
              .append(". ")
              .append(
                  Component.translatableWithFallback(
                      "reform.workflow.receipt",
                      "Recorded receipt: %s",
                      CommitFeedback.summary(view.lastReceipt().orElseThrow())));
    return new DocumentState(
        entry.form.route(),
        entry.form.title(),
        true,
        editor.isOpen(),
        editor.dirty(),
        editor.isOpen() && editor.busy(),
        summary,
        entry.form.document());
  }

  private Entry entry(DocumentId id) {
    var entry = entries.get(Objects.requireNonNull(id));
    if (entry == null) throw new IllegalArgumentException("This document is not in this workflow");
    return entry;
  }

  private void requireOpen() {
    ClientSettingsRuntime.requireClientThread();
    if (!isOpen())
      throw new PolicyRefusal("client.workflow_closed", "This editing workflow is closed");
  }

  private void requireOverview() {
    requireOpen();
    if (NativeScreens.current(Minecraft.getInstance()) != overview)
      throw new PolicyRefusal("client.workflow_view", "Return to this workflow's overview first");
  }

  /** Forced lifetime shutdown. An effect already entered may still finish; this is no rollback. */
  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    if (!closed) lifetime.close();
  }

  private void retire() {
    if (closed) return;
    closed = true;
    overview.retireInspection();
    overview.cancelDeferredNavigation();
    supersedeNavigation();
    Throwable failure = null;
    for (var task : List.copyOf(taskViews.values())) {
      try {
        task.lifetime().close();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else if (failure != failed) failure.addSuppressed(failed);
      }
    }
    taskViews.clear();
    for (var custom : List.copyOf(customViews.values())) {
      try {
        custom.lifetime().close();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else if (failure != failed) failure.addSuppressed(failed);
      }
    }
    customViews.clear();
    try {
      if (graphics != null) graphics.close();
    } catch (RuntimeException | Error failed) {
      if (failure == null) failure = failed;
      else if (failure != failed) failure.addSuppressed(failed);
    }
    try {
      overview.header.close();
    } catch (RuntimeException | Error failed) {
      if (failure == null) failure = failed;
      else if (failure != failed) failure.addSuppressed(failed);
    }
    try {
      if (batch != null) batch.close();
    } catch (RuntimeException | Error failed) {
      if (failure == null) failure = failed;
      else if (failure != failed) failure.addSuppressed(failed);
    }
    for (var entry : entries.values()) {
      try {
        if (entry.opening != null) entry.opening.cancel().run();
        if (entry.editor != null) entry.editor.close();
      } catch (RuntimeException | Error failed) {
        if (failure == null) failure = failed;
        else if (failure != failed) failure.addSuppressed(failed);
      }
      entry.editor = null;
      entry.opening = null;
      entry.waiting = null;
    }
    entries.clear();
    ownerEntries.clear();
    placements.clear();
    reviews.clear();
    guarded.clear();
    contributions.clear();
    batch = null;
    batchView = null;
    if (failure instanceof RuntimeException failed) throw failed;
    if (failure instanceof Error failed) throw failed;
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.workflow." + key, fallback);
  }

  /**
   * Explicitly enter every declared owner and review the complete vector, not a filtered subset.
   */
  public void reviewAll() {
    requireOverview();
    if (layout != null)
      throw new PolicyRefusal("client.review_scope", "Choose an explicit review scope");
    beginReview(
        text("review_all", "Review all documents"),
        DocumentId.parse("reform:client_workflow"),
        route -> true,
        route -> true,
        validator,
        overview);
  }

  public void review(DocumentId id) {
    requireOverview();
    var scope = reviews.get(Objects.requireNonNull(id));
    if (scope == null)
      throw new IllegalArgumentException("This review scope is not in this workflow");
    beginReview(scope, overview);
  }

  private void beginReview(ClientWorkflowLayout.ReviewScope scope, Screen from) {
    beginReview(
        scope.title(),
        scope.id(),
        route -> scope.writable().contains(route) || scope.checked().contains(route),
        scope.writable()::contains,
        scope.validate(),
        from);
  }

  private void beginReview(
      Component title,
      DocumentId id,
      Predicate<DocumentId> included,
      Predicate<DocumentId> mayWrite,
      DocumentBatch.Validator validate,
      Screen from) {
    requireOpen();
    if (NativeScreens.current(Minecraft.getInstance()) != from)
      throw new PolicyRefusal("client.workflow_view", "This workflow view is no longer current");
    var navigationToken = new Object();
    overview.navigationRequest = navigationToken;
    overview.cancelDeferredNavigation();
    overview.navigation.cancelRestoration();
    supersedeNavigation();
    if (overview.navigationRequest != navigationToken) return;
    if (batch != null && batch.hasActiveOperations())
      throw new PolicyRefusal("client.batch_busy", "Wait for the batch operation to settle");
    var admission =
        entries.keySet().stream()
            .filter(included)
            .filter(route -> entry(route).form.asynchronous() && entry(route).editor == null)
            .map(this::openEditor)
            .map(CompletionStage::toCompletableFuture)
            .toArray(CompletableFuture<?>[]::new);
    if (admission.length > 0) {
      var client = Minecraft.getInstance();
      var request = new Object();
      overview.navigationRequest = request;
      supersedeNavigation();
      if (overview.navigationRequest != request) return;
      overview.notice =
          text("opening_scope", "Checking the actual policy contexts for this review…");
      if (from == overview) overview.rebuildWidgets();
      else NativeScreens.open(client, overview);
      CompletableFuture.allOf(admission)
          .whenComplete(
              (ignored, failed) ->
                  client.execute(
                      () -> {
                        if (!isOpen()
                            || NativeScreens.current(client) != overview
                            || overview.navigationRequest != request) return;
                        if (failed == null) {
                          overview.notice = null;
                          try {
                            beginReview(title, id, included, mayWrite, validate, overview);
                          } catch (PolicyRefusal refused) {
                            overview.showOpeningFailure(refused);
                          }
                        } else overview.showOpeningFailure(failed);
                      }));
      return;
    }
    var members = new ArrayList<DocumentBatch.Member<?>>();
    for (var route : entries.keySet()) {
      if (!included.test(route)) continue;
      var selected = editor(route);
      if (selected.busy())
        throw new PolicyRefusal(
            "client.editor_busy", "Wait for pending document work before review");
      if (selected.presentation().retainedInput())
        throw new PolicyRefusal(
            "client.unaccepted_input",
            "Return to the document and retry or explicitly revert unaccepted input before batch review");
      members.add(
          DocumentBatch.member(
              selected.actions(),
              mayWrite.test(route) && selected.dirty()
                  ? DocumentBatch.Intent.COMMIT
                  : DocumentBatch.Intent.CHECK));
    }
    if (batch != null) batch.close();
    batch =
        new DocumentBatch(
            lifetime, batchAccess, id, members, validate, DocumentBatch.Limits.STANDARD);
    batchView =
        new BatchReviewScreen(
            title,
            batch,
            this::isOpen,
            owner -> ownerEntries.get(owner).form.title(),
            this::inspectBatch,
            () -> NativeScreens.open(Minecraft.getInstance(), overview),
            this::close);
    if (from == overview) overview.navigate(batchView);
    else NativeScreens.open(Minecraft.getInstance(), batchView);
  }

  private void inspectBatch(DocumentBatch.Entry captured, Screen parent) {
    var bound = ownerEntries.get(captured.document());
    var selected = editor(bound.form.route());
    var report = captured.preflight();
    var cross =
        batchView.reviewedInput().orElseThrow().check().documents().get(captured.document());
    if (cross != null) report = report.and(cross);
    var screen =
        new ReviewScreen<>(
            text("review_fields", "Reviewed source and fields"),
            bound.form.title(),
            selected.actions(),
            () -> isOpen() && selected.isOpen(),
            path -> {
              if (path.isPresent()) {
                NativeScreens.open(Minecraft.getInstance(), overview);
                overview.openDocument(bound.form.route(), path);
              } else NativeScreens.open(Minecraft.getInstance(), parent);
            },
            this::close,
            captured.review(),
            report);
    screen.withFieldOrder(selected.form().fieldOrder());
    screen.withFieldPresentation(selected.form().fieldPresentation());
    NativeScreens.open(Minecraft.getInstance(), screen);
  }

  private <O> void openOperation(Contributed<O> entry, boolean restart) {
    requireOverview();
    overview.navigation.cancelRestoration();
    var navigationToken = new Object();
    overview.navigationRequest = navigationToken;
    overview.cancelDeferredNavigation();
    supersedeNavigation();
    if (overview.navigationRequest != navigationToken) return;
    var action = entry.action;
    boolean requested = false;
    if (entry.latest == null || restart) {
      if (entry.busy())
        throw new PolicyRefusal("client.operation_busy", "This operation is already running");
      var blocked = action.blocked().get();
      if (blocked.isPresent())
        throw new PolicyRefusal("client.operation_unavailable", blocked.orElseThrow().getString());
      entry.latest = Objects.requireNonNull(action.start().get());
      requested = true;
    }
    // An entered callback may legitimately navigate or close its workflow. It cannot bring an
    // obsolete inspector back over that newer choice.
    if (!isOpen()
        || overview.navigationRequest != navigationToken
        || NativeScreens.current(Minecraft.getInstance()) != overview) return;
    try {
      inspectOperation(entry);
    } catch (PolicyRefusal refused) {
      if (!requested) throw refused;
      var acknowledged =
          new PolicyRefusal(
              new PolicyRefusal(
                      "client.operation_requested_inspection_unavailable",
                      "The operation was requested, but its status cannot be inspected here. Check its result before requesting it again.")
                  .report()
                  .and(refused.report()));
      acknowledged.initCause(refused);
      throw acknowledged;
    }
  }

  private <O> void inspectOperation(Contributed<O> entry) {
    requireOverview();
    var action = entry.action;
    if (entry.latest == null) throw missingDestination();
    if (!action.mayObserve().getAsBoolean())
      throw new PolicyRefusal(
          "navigation.result_unavailable", "This context cannot inspect that result.");
    overview.navigate(
        new OperationScreen<>(
            action.title(),
            action.context(),
            entry.latest,
            () -> isOpen() && action.mayObserve().getAsBoolean(),
            action.describe(),
            List.of(),
            () -> NativeScreens.open(Minecraft.getInstance(), overview),
            this::close));
  }

  private final class Overview extends FormHostScreen {
    private ReviewView<?> inspection;
    private DocumentId inspectionRoute;
    private final Map<DocumentId, ReviewView<?>> inspections = new LinkedHashMap<>();

    private void hideInspection() {
      if (inspection != null) inspection.hide();
      inspection = null;
      inspectionRoute = null;
      navigation.task(null);
    }

    private WorkspaceNavigation.Key inspectionKey(DocumentId route) {
      return new WorkspaceNavigation.Key(
          WorkspaceNavigation.Kind.INSPECTION, route, Optional.empty());
    }

    private void revealInspection(DocumentId route, ClientEditor<?> editor) {
      var retained = inspections.get(editor.document());
      if (retained != null && retained.borrows(editor.actions())) {
        beforeRebuild();
        hideInspection();
        inspection = retained;
        inspectionRoute = route;
        navigation.task(inspectionKey(route));
        information.clear();
        drawerOpen = false;
        rebuildWidgets();
      } else editor.presentation().openInspection(false);
      if (inspection == null || !inspection.isCurrent())
        throw new PolicyRefusal(
            "inspection.input_unaccepted",
            "Finish or repair the current field input before opening inspection.");
    }

    private void retireInspection() {
      for (var retained : inspections.values()) retained.retireForNavigation();
      inspections.clear();
      inspection = null;
      inspectionRoute = null;
    }

    private void retireInspection(DocumentId document) {
      var retained = inspections.remove(document);
      if (retained == null) return;
      retained.retireForNavigation();
      if (inspection == retained) {
        inspection = null;
        inspectionRoute = null;
      }
    }

    @Override
    protected boolean managedNavigation(Screen next) {
      if (next instanceof ReviewScreen<?> review) {
        var admitted =
            entries.values().stream()
                .filter(
                    bound ->
                        bound.editor != null
                            && bound.editor.isOpen()
                            && review.borrows(bound.editor.actions()))
                .toList();
        if (admitted.isEmpty())
          return false; // A genuinely foreign owner keeps its explicit handoff.
        beforeRebuild();
        var borrowed = review.borrowView();
        var route =
            admitted.stream()
                .map(bound -> bound.form.route())
                .filter(id -> id.equals(actionRoute))
                .findFirst()
                .orElse(admitted.getFirst().form.route());
        hideInspection();
        var replaced = inspections.put(borrowed.document(), borrowed);
        if (replaced != null) replaced.retireForNavigation();
        inspection = borrowed;
        inspectionRoute = route;
        navigation.task(inspectionKey(route));
        information.clear();
        drawerOpen = false;
        rebuildWidgets();
        return true;
      }
      if (next == this
          && NativeScreens.current(Minecraft.getInstance()) == this
          && inspection != null) {
        hideInspection();
        resetList = true;
        rebuildWidgets();
        return true;
      }
      return false;
    }

    private enum Filter {
      ALL,
      MODIFIED,
      INVALID,
      RESTART
    }

    private final Screen parent;
    private final WorkspaceNavigation navigation =
        new WorkspaceNavigation(
            () -> isOpen() && minecraft != null && NativeScreens.current(minecraft) == this,
            this::rebuildWidgets,
            this::revealNavigation,
            this::clearTemporaryReveal);
    private WorkspaceNavigation.Widgets navigationWidgets;
    private final WorkspaceInformation information =
        new WorkspaceInformation(
            this::rebuildWidgets,
            this::informationDestination,
            () -> isOpen() && minecraft != null && NativeScreens.current(minecraft) == this);
    private FormPanel informationPanel;
    private net.minecraft.client.gui.components.CycleButton<Filter> viewFilter;
    private FormPanel panel;
    private List<DocumentState> displayed = List.of();
    private List<ContributionState> displayedOperations = List.of();
    private Language language;
    private List<ClientWorkflowLayout.Category> categoryPath;
    private DocumentId selectedRoute = entries.keySet().iterator().next();
    private WorkspaceNavigation.Key selectedKey;
    private WorkspaceNavigation.Key displayedKey;
    private WorkspaceNavigation.Key temporaryReveal;
    private boolean editingVisible;
    private Filter selectedFilter = Filter.ALL;
    private final Map<DocumentId, Throwable> openingFailures = new LinkedHashMap<>();
    private Component notice;
    private Object generation;
    private Object navigationRequest;
    private boolean drawerOpen, resetList, restoringDestination;
    private String retainedFocus;
    private design.inherent.reform.presentation.SectionNavigation.Bookmark retainedScroll;
    private DocumentId actionRoute;
    private int statusTop, statusWidth;
    private FormGeometry.ScreenFrame outerFrame;
    private final WorkspaceHeader header =
        new WorkspaceHeader(presentation, presentation.clientTheme());

    Overview(Screen parent, Component title) {
      super(title);
      withTheme(presentation.clientTheme());
      withTaskPresentation(presentation.taskPresentation());
      this.parent = parent;
      categoryPath = layout == null ? List.of() : layout.groups().getFirst().path();
      selectedKey =
          layout == null
              ? destination(selectedRoute)
              : categoryPath.isEmpty() ? groupKey(0) : category(categoryPath.getLast().id());
      if (presentation.home().isPresent()) selectedKey = homeKey();
      presentation
          .initial()
          .ifPresent(
              id -> {
                if (presentation.home().filter(home -> home.id().equals(id)).isPresent())
                  selectedKey = homeKey();
                else if (layout == null) {
                  selectedRoute = id.value();
                  selectedKey = destination(id.value());
                } else
                  for (int index = 0; index < layout.groups().size(); index++) {
                    var group = layout.groups().get(index);
                    if (group.path().isEmpty() && groupKey(index).placement().equals(id.value())) {
                      selectedKey = groupKey(index);
                      categoryPath = List.of();
                    }
                    for (var placement : group.placements())
                      if (placement.id().equals(id.value())) {
                        selectedKey = destination(placement.id());
                        selectedRoute = placement.route();
                        categoryPath = group.path();
                      }
                    for (int depth = 0; depth < group.path().size(); depth++)
                      if (group.path().get(depth).id().equals(id.value())) {
                        selectedKey = category(id.value());
                        categoryPath = group.path().subList(0, depth + 1);
                      }
                  }
              });
    }

    @Override
    protected void init() {
      if (!isOpen()) return;
      navigation.visit(
          selectedKey, design.inherent.reform.presentation.FormCatalog.Limits.STANDARD);
      suspendView();
      informationPanel = null;
      generation = new Object();
      language = Language.getInstance();
      var token = generation;
      var page = pagePlacements();
      var selected = new LinkedHashMap<DocumentId, List<SettingsView.Selection>>();
      for (var item : page) {
        var selections = selected.computeIfAbsent(item.route(), ignored -> new ArrayList<>());
        var path = item.field().orElse(FieldPath.ROOT);
        // Aliases and ancestor/child placements share one physical control, draft and history.
        if (selections.stream().anyMatch(before -> path.startsWith(before.path()))) continue;
        selections.removeIf(before -> before.path().startsWith(path));
        selections.add(new SettingsView.Selection(path, item.title(), item.help(), item.section()));
      }
      var taskDeclaration = declaredTask();
      if (taskDeclaration != null) selected.put(taskDeclaration.route(), List.of());
      if (inspection != null) selected.put(inspectionRoute, List.of());
      for (var route : selected.keySet()) ensureOwner(route);
      var returned = resetList ? navigation.position(selectedKey).orElse(null) : null;
      if (returned != null && selected.containsKey(returned.owner()))
        actionRoute = returned.owner();
      var actions = new ArrayList<net.minecraft.client.gui.components.AbstractWidget>();
      actions.add(button(token, text("done", "Done"), title, this::closeWorkflow));
      if (actionRoute == null || !selected.containsKey(actionRoute))
        actionRoute = selected.keySet().stream().findFirst().orElse(null);
      var actionEntry = actionRoute == null ? null : entry(actionRoute);
      // Current owner buttons are installed after the actual view has refreshed their state.
      int padding =
          Math.min(
              theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_SCREEN),
              Math.max(0, (Math.min(width, height) - 1) / 2));
      int available = Math.max(1, width - 2 * padding);
      var metrics =
          new FormGeometry.WorkspaceMetrics(
              Math.max(
                  theme()
                      .values()
                      .value(
                          design.inherent.reform.presentation.ThemeValues.Role
                              .MEASURE_NAVIGATION_MINIMUM),
                  font.width(text("search", "Search settings")) + 20),
              theme()
                  .values()
                  .value(
                      design.inherent.reform.presentation.ThemeValues.Role.MEASURE_EDITOR_MINIMUM),
              theme()
                  .values()
                  .value(
                      design.inherent.reform.presentation.ThemeValues.Role
                          .MEASURE_INFORMATION_MINIMUM),
              Math.max(112, Math.min(200, available / 4)),
              theme()
                  .values()
                  .value(
                      design.inherent.reform.presentation.ThemeValues.Role
                          .MEASURE_INFORMATION_PREFERRED),
              theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT));
      int headerHeight = header.measure(font, available);
      int bodyTop =
          padding
              + headerHeight
              + theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT);
      var preliminary =
          FormGeometry.workspace(
              available,
              bodyTop,
              Math.max(bodyTop + 1, height - padding),
              metrics,
              drawerOpen,
              information.open());
      net.minecraft.client.gui.components.Button navigationToggle = null;
      if (preliminary.compactNavigation()) {
        var toggle =
            button(
                token,
                text("categories", "Categories"),
                title,
                () -> {
                  drawerOpen = !drawerOpen;
                  rebuildWidgets();
                });
        toggle.setPosition(padding, padding);
        toggle.setWidth(Math.min(100, available));
        navigationToggle = toggle;
      }
      final var categoriesToggle = navigationToggle;
      int navigationHeight =
          preliminary.compactNavigation()
              ? theme()
                      .values()
                      .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT)
                  + theme()
                      .values()
                      .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT)
              : 0;
      // Measure once with the same labels before mounting, rather than reserving a fixed row count.
      var actionView =
          inspection != null
                  || actionEntry == null
                  || actionEntry.editor == null
                  || !actionEntry.editor.isOpen()
              ? null
              : actionEntry.editor.presentation();
      var actionSizes = new ArrayList<FormGeometry.Size>();
      actionSizes.add(
          new FormGeometry.Size(
              actions.getFirst().getWidth(),
              theme()
                  .values()
                  .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT)));
      if (actionView != null)
        for (var size : actionView.actionSizes(font, theme())) actionSizes.add(size);
      outerFrame =
          FormGeometry.screenFrame(
              width,
              height,
              Math.max(
                  theme()
                      .values()
                      .value(
                          design.inherent.reform.presentation.ThemeValues.Role
                              .MEASURE_TITLE_MINIMUM_HEIGHT),
                  headerHeight + navigationHeight),
              0,
              theme()
                  .values()
                  .value(
                      design.inherent.reform.presentation.ThemeValues.Role
                          .MEASURE_STATUS_MINIMUM_HEIGHT),
              actionSizes,
              theme().values());
      bodyTop = outerFrame.content().y();
      statusTop = outerFrame.status().map(FormGeometry.Placement::y).orElse(bodyTop);
      statusWidth = outerFrame.content().width();
      int editorBottom = bodyTop + outerFrame.content().height();
      if (navigationToggle != null && !outerFrame.headerInContent()) {
        navigationToggle.setPosition(
            padding,
            padding
                + headerHeight
                + theme()
                    .values()
                    .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_TEXT));
        addRenderableWidget(navigationToggle);
      }
      var headerBounds =
          outerFrame
              .title()
              .map(at -> new FormGeometry.Placement(at.x(), at.y(), at.width(), headerHeight))
              .orElse(new FormGeometry.Placement(padding, bodyTop, available, headerHeight));
      if (!outerFrame.headerInContent())
        for (var widget :
            header.mount(
                font, headerBounds, locationTrail(), () -> current(token), this::locationMenu))
          addRenderableWidget(widget);
      var geometry =
          isHome() && !drawerOpen
              ? new FormGeometry.Workspace(
                  Optional.empty(),
                  new FormGeometry.Placement(
                      0, bodyTop, available, Math.max(1, editorBottom - bodyTop)),
                  Optional.empty(),
                  preliminary.compactNavigation(),
                  false)
              : FormGeometry.workspace(
                  available, bodyTop, editorBottom, metrics, drawerOpen, information.open());
      var bounds = geometry.editor();
      if (isHome() && !drawerOpen)
        bounds =
            FormGeometry.centeredWidth(
                bounds,
                Math.addExact(
                    Math.multiplyExact(
                        font.width("M"),
                        theme()
                            .values()
                            .value(
                                design.inherent.reform.presentation.ThemeValues.Role
                                    .MEASURE_READING_COLUMNS)),
                    2
                        * theme()
                            .values()
                            .value(
                                design.inherent.reform.presentation.ThemeValues.Role
                                    .SPACE_SCREEN)));
      editingVisible = !geometry.navigationOverlay();
      final java.util.function.Consumer<FormPanel> beforeContent =
          target -> {
            if (outerFrame.headerInContent()) {
              header.appendTo(
                  target, font, locationTrail(), () -> current(token), this::locationMenu);
              if (categoriesToggle != null)
                target.actions("workspace:categories", categoriesToggle);
            }
            if (outerFrame.statusInContent()) target.row(Component.empty(), scopeStatus());
            if (notice != null) target.row(Component.empty(), notice);
            if (temporaryReveal != null && selectedFilter != Filter.ALL)
              target.row(
                  Component.empty(),
                  text(
                      "temporary_reveal",
                      "Temporarily showing the linked destination; your filter is unchanged."));
          };
      ReferenceTaskView<?, ?> taskBody = null;
      if (inspection == null && !geometry.navigationOverlay() && taskDeclaration != null) {
        var taskOwner = entry(taskDeclaration.route()).editor;
        if (taskOwner != null
            && taskOwner.isOpen()
            && taskOwner.view().availability() == EditorActor.Availability.READY)
          taskBody = taskView(new DestinationId(selectedKey.placement()));
      }
      if (activeTask != null && activeTask != taskBody) activeTask.visible(false);
      activeTask = taskBody;
      var customId = selectedKey == null ? null : new DestinationId(selectedKey.placement());
      var customBody =
          inspection == null
                  && !geometry.navigationOverlay()
                  && presentation.views().containsKey(customId)
              ? customView(customId)
              : null;
      for (var custom : customViews.values()) if (custom.view() != customBody) custom.view().hide();
      if (taskBody != null) {
        taskBody.visible(true);
        taskBody.compose(
            outerFrame.headerInContent()
                || outerFrame.statusInContent()
                || outerFrame.actionsInContent()
                || information.open(),
            beforeContent);
        mount(
            taskBody,
            new FormGeometry.Placement(
                padding + bounds.x(), bounds.y(), bounds.width(), bounds.height()));
        panel = taskBody.primary();
      } else {
        panel =
            new FormPanel(
                minecraft,
                padding + bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                false,
                theme());
        if (inspection == null) beforeContent.accept(panel);
      }
      if (!geometry.navigationOverlay()) {
        if (isHome()) appendHome(token);
        if (inspection != null) {
          inspection.compose(
              outerFrame.headerInContent()
                  || outerFrame.statusInContent()
                  || outerFrame.actionsInContent(),
              beforeContent,
              target -> {
                if (outerFrame.actionsInContent())
                  target.actions(
                      "workspace:actions",
                      actions.toArray(net.minecraft.client.gui.components.AbstractWidget[]::new));
              });
          mount(
              inspection,
              new FormGeometry.Placement(
                  padding + bounds.x(), bounds.y(), bounds.width(), bounds.height()));
          panel = inspection.primary();
        }
        if (customBody != null)
          customBody.append(
              panel, theme(), ClientEditingWorkflow.this.navigation, this::rebuildWidgets);
        if (information.open() && geometry.information().isEmpty()) information.append(panel);
        for (var item :
            inspection == null
                ? selected.entrySet()
                : java.util.Set.<Map.Entry<DocumentId, List<SettingsView.Selection>>>of()) {
          var bound = entry(item.getKey());
          if (bound.editor != null && bound.editor.isOpen()) {
            var view = bound.editor.presentation();
            var route = item.getKey();
            view.discardDocument(() -> confirmDiscard(route));
            view.compose(panel, item.getValue());
            mount(
                view,
                new FormGeometry.Placement(
                    padding + bounds.x(), bounds.y(), bounds.width(), bounds.height()));
          } else {
            var state = state(bound);
            var retry =
                button(
                    token,
                    text("retry", "Retry"),
                    state.title(),
                    () -> {
                      openingFailures.remove(item.getKey());
                      if (bound.form.canRetry()) bound.form.retry();
                      rebuildWidgets();
                    });
            retry.active = bound.opening == null;
            panel.row(
                state.title(),
                openingFailures.containsKey(item.getKey())
                    ? text("opening_failed", "The current owner could not open this editor.")
                    : state.summary(),
                retry);
          }
        }
        if (inspection == null && layout != null && !isHome()) {
          for (var group : selectedGroups())
            for (var action : group.actions()) operationRow(token, contributions.get(action.id()));
          for (var scope : reviews.values())
            if (scope.writable().stream().anyMatch(selected::containsKey)) {
              var review =
                  button(
                      token,
                      text("review", "Review changes"),
                      scope.title(),
                      () -> review(scope.id()));
              review.active = batch == null || !batch.hasActiveOperations();
              panel.row(scope.id().toString(), scope.title(), Component.empty(), review);
            }
        } else if (inspection == null && !isHome()) {
          var review =
              button(
                  token,
                  text("review_all", "Review all documents"),
                  title,
                  ClientEditingWorkflow.this::reviewAll);
          review.active =
              documents().stream().noneMatch(DocumentState::busy)
                  && (batch == null || !batch.hasActiveOperations());
          panel.row(
              Component.empty(),
              text(
                  "independent",
                  "Each document saves separately. Drafts and undo history remain when you browse."),
              review);
        }
        if (inspection == null && batchView != null)
          panel.row(
              Component.empty(),
              Component.empty(),
              button(
                  token,
                  text("batch_result", "Last batch review/result"),
                  title,
                  () -> navigate(batchView)));
        if (selected.isEmpty()
            && !isHome()
            && taskBody == null
            && customBody == null
            && inspection == null)
          panel.row(text("no_matches", "No matching settings or operations."), Component.empty());
        if (actionView != null) actions.addAll(actionView.ownerActions());
        if (inspection == null && outerFrame.actionsInContent())
          panel.actions(
              "workspace:actions",
              actions.toArray(net.minecraft.client.gui.components.AbstractWidget[]::new));
        if (inspection == null) addRenderableWidget(panel);
        if (taskBody != null) taskBody.finish();
        else if (inspection == null) {
          panel.finish(0);
          if (returned != null) panel.restore(returned.scroll());
          else if (!resetList && retainedScroll != null) panel.restore(retainedScroll);
        }
        String focus =
            inspection != null || taskBody != null
                ? null
                : returned != null ? returned.focus() : !resetList ? retainedFocus : null;
        if (focus != null) {
          setFocused(panel);
          panel.restoreFocus(focus);
        }
        if (geometry.information().isPresent()) {
          var at = geometry.information().orElseThrow();
          informationPanel =
              new FormPanel(
                  minecraft, padding + at.x(), at.y(), at.width(), at.height(), false, theme());
          addRenderableWidget(informationPanel);
          information.dock(informationPanel, () -> setFocused(informationPanel));
        }
        var informationTarget = informationPanel == null ? panel : informationPanel;
        information.focus(informationTarget, () -> setFocused(informationTarget));
      }
      resetList = false;
      var footerGeometry =
          FormGeometry.controls(
              actions.stream()
                  .map(widget -> new FormGeometry.Size(widget.getWidth(), widget.getHeight()))
                  .toList(),
              available,
              4);
      if (!outerFrame.actionsInContent() && outerFrame.actions().isPresent()) {
        var actionBounds = outerFrame.actions().orElseThrow();
        if (footerGeometry.height() > actionBounds.height())
          throw new IllegalStateException("Actual owner actions exceed their measured region");
        for (int i = 0; i < actions.size(); i++) {
          var position = footerGeometry.placements().get(i);
          var action = actions.get(i);
          action.setPosition(actionBounds.x() + position.x(), actionBounds.y() + position.y());
          action.setWidth(position.width());
          addRenderableWidget(action);
        }
        if (actionView != null && actions.size() > 1 && !geometry.navigationOverlay())
          actions
              .getLast()
              .setX(actionBounds.x() + actionBounds.width() - actions.getLast().getWidth());
      }
      navigationWidgets = null;
      if (geometry.navigation().isPresent()) {
        var at = geometry.navigation().orElseThrow();
        var nodes = navigationEntries();
        int maximum = ClientWorkflowLayout.MAXIMUM_NAVIGATION_DESTINATIONS;
        for (var placement : allPlacements()) {
          var bound = entry(placement.route());
          if (bound.editor != null && bound.editor.isOpen())
            maximum =
                (int)
                    Math.min(
                        Integer.MAX_VALUE,
                        (long) maximum
                            + bound.editor.presentation().navigationLimits().maximumEntries());
        }
        var limits = design.inherent.reform.presentation.FormCatalog.Limits.STANDARD;
        navigationWidgets =
            navigation.build(
                minecraft,
                new FormGeometry.Placement(
                    padding + at.x(), at.y() + 26, at.width(), Math.max(1, at.height() - 26)),
                nodes,
                selectedKey,
                new design.inherent.reform.presentation.FormCatalog.Limits(
                    maximum,
                    limits.maximumLabelCharacters(),
                    limits.maximumTotalLabelCharacters(),
                    limits.maximumQueryCharacters(),
                    limits.maximumHistoryEntries()),
                entries.values().stream()
                    .filter(bound -> bound.editor != null && bound.editor.isOpen())
                    .flatMap(
                        bound ->
                            bound.editor.presentation().searchProblem().stream()
                                .map(
                                    problem ->
                                        bound.form.title().copy().append(": ").append(problem)))
                    .map(component -> (Component) component)
                    .toList(),
                prefix -> {
                  if (geometry.navigationOverlay() && outerFrame.headerInContent()) {
                    header.appendTo(
                        prefix, font, locationTrail(), () -> current(token), this::locationMenu);
                    if (categoriesToggle != null)
                      prefix.actions("workspace:categories", categoriesToggle);
                  }
                },
                theme());
        addRenderableWidget(navigationWidgets.search());
        addRenderableWidget(navigationWidgets.panel());
        for (var control : navigationWidgets.history()) addRenderableWidget(control);
        if (navigation.restoreSearchFocus()) setFocused(navigationWidgets.search());
        else if (navigation.restoreTreeFocus()) {
          setFocused(navigationWidgets.panel());
          navigation.restoreTree();
        }
        viewFilter =
            NativeCycles.<Filter>builder(Overview::filterLabel, selectedFilter, Filter.values())
                .create(
                    padding + at.x(),
                    at.y(),
                    Math.min(at.width(), 140),
                    20,
                    text("view", "Show"),
                    (button, value) -> {
                      if (!current(token)) return;
                      selectedFilter = value;
                      clearTemporaryReveal();
                      resetList = true;
                      rebuildWidgets();
                    });
        addRenderableWidget(viewFilter);
      }
      displayed = documents();
      displayedOperations = contributionStates();
      displayedKey = selectedKey;
    }

    private void ensureOwner(DocumentId route) {
      var bound = entry(route);
      if (bound.editor != null || bound.opening != null || openingFailures.containsKey(route))
        return;
      if (!bound.form.availability().ready()) return;
      try {
        if (!bound.form.asynchronous()) editor(route);
        else
          openEditor(route)
              .whenComplete(
                  (editor, failed) ->
                      Minecraft.getInstance()
                          .execute(
                              () -> {
                                if (!isOpen()) return;
                                if (failed != null) openingFailures.put(route, failed);
                                if (NativeScreens.current(minecraft) == this) rebuildWidgets();
                              }));
      } catch (RuntimeException refused) {
        openingFailures.put(route, refused);
      }
    }

    private static Component filterLabel(Filter filter) {
      return switch (filter) {
        case ALL -> text("view_all", "All");
        case MODIFIED -> text("view_modified", "Modified");
        case INVALID -> text("view_invalid", "Invalid");
        case RESTART -> text("view_restart", "Restart");
      };
    }

    private WorkspaceNavigation.Key homeKey() {
      return new WorkspaceNavigation.Key(
          WorkspaceNavigation.Kind.HOME,
          presentation.home().orElseThrow().id().value(),
          Optional.empty());
    }

    private boolean isHome() {
      return selectedKey != null && selectedKey.kind() == WorkspaceNavigation.Kind.HOME;
    }

    private void selectHome() {
      if (presentation.home().isEmpty()) throw missingDestination();
      openDestination(new ClientDestination.Home());
    }

    private List<WorkspaceHeader.Crumb> locationTrail() {
      var declared = navigationEntries(true);
      var byKey = new LinkedHashMap<WorkspaceNavigation.Key, WorkspaceNavigation.Entry>();
      declared.forEach(node -> byKey.put(node.key(), node));
      var chain = new ArrayDeque<WorkspaceHeader.Crumb>();
      var seen = new java.util.HashSet<WorkspaceNavigation.Key>();
      var node = byKey.get(selectedKey);
      while (node != null && seen.add(node.key())) {
        var selected = node;
        chain.addFirst(
            new WorkspaceHeader.Crumb(node.title(), () -> revealNavigation(selected.key())));
        node = node.parent().map(byKey::get).orElse(null);
      }
      if (presentation.home().isPresent() && (chain.isEmpty() || !isHome()))
        chain.addFirst(
            new WorkspaceHeader.Crumb(presentation.home().orElseThrow().title(), this::selectHome));
      if (chain.isEmpty())
        chain.add(
            new WorkspaceHeader.Crumb(text("location_missing", "Unavailable location"), () -> {}));
      if (inspection != null)
        chain.addLast(new WorkspaceHeader.Crumb(inspection.caption(), () -> {}));
      return List.copyOf(chain);
    }

    private void locationMenu(List<WorkspaceHeader.Crumb> trail) {
      var choices = new ArrayList<ClientChoiceScreen.Choice>();
      for (int index = 0; index < trail.size(); index++) {
        var crumb = trail.get(index);
        choices.add(
            new ClientChoiceScreen.Choice(
                "ancestor/" + index,
                crumb.title(),
                Component.empty(),
                Optional::<Component>empty,
                () -> {
                  NativeScreens.open(minecraft, this);
                  crumb.selected().run();
                }));
      }
      navigate(
          new ClientChoiceScreen(
              this,
              text("location", "Location"),
              text("location_help", "Choose an ancestor or the current destination."),
              choices,
              text("back", "Back"),
              256));
    }

    private void appendHome(Object token) {
      var home = presentation.home().orElseThrow();
      panel.heading(home.title());
      panel.row(Component.empty(), home.introduction());
      var declared = navigationEntries(true);
      var featured = home.featured();
      var choices =
          declared.stream()
              .filter(
                  node ->
                      node.key().kind() != WorkspaceNavigation.Kind.HOME
                          && node.key().kind() != WorkspaceNavigation.Kind.FIELD
                          && node.key().kind() != WorkspaceNavigation.Kind.SECTION
                          && (featured.isEmpty()
                              ? node.parent().isEmpty()
                              : featured.contains(new DestinationId(node.key().placement()))))
              .toList();
      for (var node : choices) {
        var open =
            button(
                token,
                node.title(),
                presentation.identity().title(),
                () -> revealNavigation(node.key()));
        panel.row("home:" + node.key(), Component.empty(), node.help(), open);
      }
      var history = navigation.historyControls(font);
      panel.actions(
          "home:history",
          history.toArray(net.minecraft.client.gui.components.AbstractWidget[]::new));
      if (documents().stream().anyMatch(DocumentState::dirty)) {
        if (layout == null)
          panel.actions(
              "home:review",
              button(
                  token,
                  text("review_all", "Review all documents"),
                  presentation.identity().title(),
                  ClientEditingWorkflow.this::reviewAll));
        else
          for (var scope : reviews.values())
            if (scope.writable().stream().anyMatch(id -> state(entry(id)).dirty()))
              panel.actions(
                  "home:review:" + scope.id(),
                  button(
                      token,
                      scope.title().copy().append(": ").append(text("review", "Review changes")),
                      presentation.identity().title(),
                      () -> review(scope.id())));
      }
      for (var contribution : contributions.values())
        if (contribution.latest != null)
          panel.actions(
              "home:operation:" + contribution.action.id(),
              button(
                  token,
                  text("open_result", "Open result")
                      .copy()
                      .append(": ")
                      .append(contribution.action.title()),
                  presentation.identity().title(),
                  () -> openOperation(contribution, false)));
    }

    private WorkspaceNavigation.Key category(DocumentId id) {
      return new WorkspaceNavigation.Key(WorkspaceNavigation.Kind.CATEGORY, id, Optional.empty());
    }

    private WorkspaceNavigation.Key groupKey(int index) {
      return category(DocumentId.parse("reform:workflow_group/" + index));
    }

    private WorkspaceNavigation.Key destination(DocumentId id) {
      return new WorkspaceNavigation.Key(
          WorkspaceNavigation.Kind.DESTINATION, id, Optional.empty());
    }

    private List<ClientWorkflowLayout.Placement> allPlacements() {
      return layout == null
          ? entries.values().stream()
              .map(
                  bound ->
                      new ClientWorkflowLayout.Placement(
                          bound.form.route(),
                          bound.form.route(),
                          bound.form.title(),
                          Optional.empty()))
              .toList()
          : layout.groups().stream().flatMap(group -> group.placements().stream()).toList();
    }

    private List<ClientWorkflowLayout.Group> selectedGroups() {
      return layout.groups().stream()
          .filter(
              group ->
                  group.path().size() >= categoryPath.size()
                      && group.path().subList(0, categoryPath.size()).equals(categoryPath))
          .toList();
    }

    private ClientReferenceTask<?, ?> declaredTask() {
      return selectedKey != null && selectedKey.kind() == WorkspaceNavigation.Kind.CATEGORY
          ? presentation.referenceTasks().get(new DestinationId(selectedKey.placement()))
          : null;
    }

    private ReferenceTaskView<?, ?> activeTask;

    private List<ClientWorkflowLayout.Placement> pagePlacements() {
      if (inspection != null
          || isHome()
          || declaredTask() != null
          || selectedKey != null
              && presentation.views().containsKey(new DestinationId(selectedKey.placement())))
        return List.of();
      var states = new LinkedHashMap<DocumentId, DocumentState>();
      for (var state : documents()) states.put(state.document(), state);
      return (layout == null
              ? allPlacements().stream().filter(item -> item.route().equals(selectedRoute))
              : selectedGroups().stream().flatMap(group -> group.placements().stream()))
          .filter(
              item ->
                  accepts(item, states.get(item.route()))
                      || temporaryReveal != null && temporaryReveal.placement().equals(item.id()))
          .toList();
    }

    private List<WorkspaceNavigation.Entry> navigationEntries() {
      return navigationEntries(false);
    }

    private List<WorkspaceNavigation.Entry> navigationEntries(boolean includeFiltered) {
      var result = new LinkedHashMap<WorkspaceNavigation.Key, WorkspaceNavigation.Entry>();
      presentation
          .home()
          .ifPresent(
              home ->
                  result.put(
                      homeKey(),
                      new WorkspaceNavigation.Entry(
                          homeKey(),
                          Optional.empty(),
                          home.title(),
                          home.introduction(),
                          this::selectHome)));
      if (layout != null)
        for (int index = 0; index < layout.groups().size(); index++) {
          var group = layout.groups().get(index);
          if (group.path().isEmpty()) {
            var key = groupKey(index);
            result.putIfAbsent(
                key,
                new WorkspaceNavigation.Entry(
                    key,
                    Optional.empty(),
                    group.title(),
                    Component.empty(),
                    () -> selectPage(key)));
          } else
            for (int depth = 0; depth < group.path().size(); depth++) {
              var prefix = List.copyOf(group.path().subList(0, depth + 1));
              var item = prefix.getLast();
              var key = category(item.id());
              var parent =
                  depth == 0
                      ? Optional.<WorkspaceNavigation.Key>empty()
                      : Optional.of(category(prefix.get(depth - 1).id()));
              result.putIfAbsent(
                  key,
                  new WorkspaceNavigation.Entry(
                      key, parent, item.title(), item.help(), () -> selectPage(key)));
            }
        }
      var states = new LinkedHashMap<DocumentId, DocumentState>();
      for (var state : documents()) states.put(state.document(), state);
      for (var placement : allPlacements()) {
        if (!includeFiltered
            && !accepts(placement, states.get(placement.route()))
            && (temporaryReveal == null || !temporaryReveal.placement().equals(placement.id())))
          continue;
        var key = destination(placement.id());
        Optional<WorkspaceNavigation.Key> parent = Optional.empty();
        if (layout != null)
          for (int i = 0; i < layout.groups().size(); i++) {
            var group = layout.groups().get(i);
            if (group.placements().contains(placement)) {
              parent =
                  Optional.of(
                      group.path().isEmpty() ? groupKey(i) : category(group.path().getLast().id()));
              break;
            }
          }
        result.put(
            key,
            new WorkspaceNavigation.Entry(
                key,
                parent,
                placement.title(),
                placement.help(),
                () -> selectPlacement(placement),
                false,
                placement.title().getString()
                    + " "
                    + placement.help().getString()
                    + " "
                    + entry(placement.route()).form.title().getString()));
        var bound = entry(placement.route());
        if (bound.editor == null || !bound.editor.isOpen()) continue;
        var anchor = placement.field().orElse(FieldPath.ROOT);
        var targets = bound.editor.presentation().navigationTargets();
        var targetKeys = new LinkedHashMap<FormTarget, WorkspaceNavigation.Key>();
        for (var node : targets) {
          if (!node.scope().startsWith(anchor)
              || node.id() instanceof FormTarget.Field field && field.path().equals(anchor))
            continue;
          targetKeys.put(node.id(), WorkspaceNavigation.Key.form(placement.id(), node.id()));
        }
        var byTarget = new LinkedHashMap<FormTarget, FormNavigationIndex.Target>();
        targets.forEach(node -> byTarget.put(node.id(), node));
        for (var node : targets) {
          var targetKey = targetKeys.get(node.id());
          if (targetKey == null) continue;
          var parentTarget = node.parent();
          while (parentTarget.isPresent() && !targetKeys.containsKey(parentTarget.orElseThrow()))
            parentTarget =
                Optional.ofNullable(byTarget.get(parentTarget.orElseThrow()))
                    .flatMap(FormNavigationIndex.Target::parent);
          var owner = parentTarget.map(targetKeys::get).orElse(key);
          result.put(
              targetKey,
              new WorkspaceNavigation.Entry(
                  targetKey,
                  Optional.of(owner),
                  node.title(),
                  node.help(),
                  () -> revealNavigation(targetKey),
                  node.searchOnly(),
                  node.search()));
        }
      }
      return List.copyOf(result.values());
    }

    private void clearTemporaryReveal() {
      temporaryReveal = null;
      navigation.temporaryReveal(null);
      navigation.cancelRestoration();
      navigationRequest = new Object();
      supersedeNavigation();
    }

    private void selectPage(WorkspaceNavigation.Key key) {
      openDestination(new ClientDestination.Category(new DestinationId(key.placement())));
    }

    private void selectPlacement(ClientWorkflowLayout.Placement placement) {
      openDestination(new ClientDestination.Placement(new DestinationId(placement.id())));
    }

    private void revealNavigation(WorkspaceNavigation.Key key) {
      try {
        openDestination(destinationFor(key));
      } catch (PolicyRefusal missing) {
        notice =
            ClientStatusScreen.reason(
                missing.report(), text("unavailable", "That destination is unavailable."));
        rebuildWidgets();
      }
    }

    private boolean accepts(ClientWorkflowLayout.Placement placement, DocumentState state) {
      if (selectedFilter == Filter.ALL) return true;
      var editor = entry(placement.route()).editor;
      var view = editor == null ? null : editor.view();
      var field = placement.field();
      return switch (selectedFilter) {
        case ALL -> true;
        case MODIFIED ->
            state.dirty()
                && (field.isEmpty() || view != null && view.changedAt(field.orElseThrow()));
        case INVALID ->
            view != null
                && (hasError(view.validation(), field)
                    || hasError(view.observationReport(), field));
        case RESTART ->
            placement.timings().stream()
                    .anyMatch(
                        timing ->
                            switch (timing) {
                              case WORLD_RESTART, GAME_RESTART, NEW_WORLD -> true;
                              default -> false;
                            })
                || view != null
                    && view.lastReceipt().stream()
                        .flatMap(receipt -> receipt.requirements().stream())
                        .anyMatch(
                            required ->
                                relevant(field, required.path())
                                    && switch (required.kind()) {
                                      case WORLD_RESTART, GAME_RESTART, NEW_WORLD -> true;
                                      default -> false;
                                    });
      };
    }

    private static boolean hasError(
        design.inherent.reform.validation.ValidationReport report, Optional<FieldPath> field) {
      if (report.valid()) return false;
      return field.isEmpty()
          || report.issues().stream()
              .filter(
                  issue ->
                      issue.severity() == design.inherent.reform.validation.Issue.Severity.ERROR)
              .anyMatch(
                  issue ->
                      relevant(field, issue.path())
                          || issue.related().stream().anyMatch(path -> relevant(field, path)));
    }

    private static boolean relevant(Optional<FieldPath> field, FieldPath path) {
      return field.isEmpty()
          || field.stream().anyMatch(anchor -> path.startsWith(anchor) || anchor.startsWith(path));
    }

    private void operationRow(Object token, Contributed<?> entry) {
      var action = entry.action;
      var blocked = action.blocked().get();
      var run = button(token, action.title(), action.title(), () -> openOperation(entry, true));
      run.active = !entry.busy() && blocked.isEmpty();
      blocked.ifPresent(
          reason -> run.setTooltip(net.minecraft.client.gui.components.Tooltip.create(reason)));
      if (entry.latest != null) {
        var inspect =
            button(
                token,
                entry.latest.snapshot().result().isPresent()
                    ? text("inspect_result", "Inspect result")
                    : text("inspect_operation", "Inspect operation"),
                action.title(),
                () -> openOperation(entry, false));
        inspect.active = action.mayObserve().getAsBoolean();
        if (!inspect.active)
          inspect.setTooltip(
              net.minecraft.client.gui.components.Tooltip.create(
                  text(
                      "operation_inspection_unavailable",
                      "This context cannot inspect this operation.")));
        panel.anchorRow(
            action.id().toString(),
            Component.empty(),
            blocked.orElse(action.context()),
            run,
            inspect);
      } else
        panel.anchorRow(
            action.id().toString(), Component.empty(), blocked.orElse(action.context()), run);
    }

    private Button button(Object token, Component label, Component document, Runnable action) {
      return theme()
          .buttonBuilder(
              label,
              ignored -> {
                if (!current(token)) return;
                try {
                  action.run();
                } catch (PolicyRefusal refused) {
                  notice =
                      ClientStatusScreen.reason(
                          refused.report(), text("unavailable", "Operation unavailable"));
                  rebuildWidgets();
                }
              })
          .bounds(0, 0, Math.max(48, font.width(label) + 20), 20)
          .createNarration(original -> document.copy().append(": ").append(original.get()))
          .build();
    }

    @Override
    protected void beforeRebuild() {
      if (inspection != null) {
        inspection.preserve();
        navigation.preserve(getFocused());
        return;
      }
      if (activeTask != null) activeTask.preserve();
      navigation.preserve(getFocused());
      information.preserve(getFocused());
      if (panel != null) {
        retainedScroll = panel.bookmark();
        retainedFocus = getFocused() == panel ? panel.focusKey() : null;
      }
      var focused = focusedView();
      DocumentId rememberedOwner = actionRoute;
      if (focused instanceof SettingsView<?> settings)
        for (var bound : entries.values())
          if (bound.form.document().equals(settings.document())) {
            rememberedOwner = bound.form.route();
            break;
          }
      if (panel != null && editingVisible)
        navigation.remember(
            displayedKey,
            new WorkspaceNavigation.Position(
                panel.bookmark(), panel.rememberedFocusKey(), rememberedOwner));
      if (!resetList && focused instanceof SettingsView<?> settings)
        for (var bound : entries.values())
          if (bound.form.document().equals(settings.document())) {
            actionRoute = bound.form.route();
            break;
          }
    }

    @Override
    protected void beforeResize() {
      beforeRebuild();
      super.beforeResize();
    }

    private boolean current(Object token) {
      return isOpen() && generation == token && NativeScreens.current(minecraft) == this;
    }

    private void openDocument(DocumentId id, Optional<FieldPath> field) {
      openDocument(id, field, field.map(FormTarget.Field::new));
    }

    private void openDocument(
        DocumentId id, Optional<FieldPath> field, Optional<FormTarget> focus) {
      Optional<DestinationId> alias = Optional.empty();
      if (selectedKey != null
          && allPlacements().stream()
              .anyMatch(
                  item -> item.id().equals(selectedKey.placement()) && item.route().equals(id)))
        alias = Optional.of(new DestinationId(selectedKey.placement()));
      ClientDestination target =
          focus.orElse(null) instanceof FormTarget.Section section
              ? new ClientDestination.Section(id, section, alias)
              : focus.orElse(null) instanceof FormTarget.Field selected
                  ? new ClientDestination.Field(id, selected.path(), alias)
                  : alias.isPresent()
                      ? new ClientDestination.Placement(alias.orElseThrow())
                      : new ClientDestination.Document(id);
      openDestination(target);
    }

    private void mountDestination(NavigationSelection selected, ClientEditor<?> editor) {
      mountDestination(selected, editor, false);
    }

    private void restorePosition(NavigationBookmark bookmark) {
      if (panel == null) return;
      var captured = bookmark.navigation().map(NavigationBookmark.NavigationState::inspection);
      if (inspection != null && captured.isPresent()) {
        inspection.restore(captured.orElseThrow());
      } else {
        bookmark.viewport().ifPresent(panel::restore);
        bookmark.focus().ifPresent(panel::restoreFocus);
        if (panel.getFocused() != null) setFocused(panel);
      }
      if (bookmark.navigation().isPresent() && navigationWidgets != null) {
        if (bookmark.navigation().orElseThrow().searchFocused() && navigation.focusSearch())
          setFocused(navigationWidgets.search());
        else if (navigation.restoreTreeFocus()) {
          navigation.restoreTree();
          if (navigationWidgets.panel().getFocused() != null) setFocused(navigationWidgets.panel());
        }
      }
      if (inspection == null)
        navigation.remember(
            selectedKey,
            new WorkspaceNavigation.Position(
                panel.bookmark(), panel.rememberedFocusKey(), actionRoute));
    }

    private void mountDestination(
        NavigationSelection selected, ClientEditor<?> editor, boolean restoring) {
      mountDestination(selected, editor, restoring, null);
    }

    private void mountDestination(
        NavigationSelection selected,
        ClientEditor<?> editor,
        boolean restoring,
        NavigationBookmark.NavigationState navigationState) {
      mountDestination(selected, editor, restoring, navigationState, false);
    }

    private void mountDestination(
        NavigationSelection selected,
        ClientEditor<?> editor,
        boolean restoring,
        NavigationBookmark.NavigationState navigationState,
        boolean inspecting) {
      // Retain the old page before assigning new identity, including a focusless viewport.
      beforeRebuild();
      hideInspection();
      if (inspecting) navigation.task(inspectionKey(selected.route().orElseThrow()));
      var previousUi = navigation.uiState();
      var previousFilter = selectedFilter;
      if (navigationState != null) {
        navigation.restoreUi(navigationState.tree());
        selectedFilter = Filter.valueOf(navigationState.filter());
      }
      var previousKey = selectedKey;
      var previousCategory = categoryPath;
      var previousRoute = selectedRoute;
      var previousAction = actionRoute;
      var previousReveal = temporaryReveal;
      var previousNotice = notice;
      information.clear();
      selectedKey = selected.key();
      temporaryReveal =
          selected.route().isPresent() && (selectedFilter != Filter.ALL || navigation.filtered())
              ? selected.key()
              : null;
      navigation.temporaryReveal(temporaryReveal);
      categoryPath = selected.category();
      selected
          .route()
          .ifPresent(
              route -> {
                selectedRoute = route;
                actionRoute = route;
              });
      if (selected.route().isEmpty()) actionRoute = null;
      drawerOpen = false;
      resetList = true;
      notice = null;
      restoringDestination = restoring;
      navigation.beginMount();
      try {
        if (editor == null) rebuildWidgets();
        else showEditor(editor, selected.target());
        navigation.endMount();
        navigation.visit(
            selectedKey, design.inherent.reform.presentation.FormCatalog.Limits.STANDARD);
      } catch (RuntimeException | Error failed) {
        navigation.task(null);
        navigation.restoreUi(previousUi);
        selectedFilter = previousFilter;
        selectedKey = previousKey;
        categoryPath = previousCategory;
        selectedRoute = previousRoute;
        actionRoute = previousAction;
        temporaryReveal = previousReveal;
        navigation.temporaryReveal(previousReveal);
        notice = previousNotice;
        navigation.endMount();
        navigation.cancelRestoration();
        try {
          rebuildWidgets();
        } catch (RuntimeException | Error recovery) {
          if (recovery != failed) failed.addSuppressed(recovery);
        }
        throw failed;
      } finally {
        navigation.endMount();
        restoringDestination = false;
      }
    }

    private void revealOperation(Contributed<?> entry) {
      if (layout == null || entry == null) throw missingDestination();
      for (int index = 0; index < layout.groups().size(); index++) {
        var group = layout.groups().get(index);
        if (!group.actions().contains(entry.action)) continue;
        var key = group.path().isEmpty() ? groupKey(index) : category(group.path().getLast().id());
        mountDestination(
            new NavigationSelection(key, group.path(), Optional.empty(), Optional.empty()), null);
        if (panel == null || !panel.focusRow(entry.action.id().toString()))
          throw new PolicyRefusal(
              "navigation.operation_hidden",
              "This operation is hidden by the current view filter.");
        setFocused(panel);
        return;
      }
      throw missingDestination();
    }

    private void showEditor(ClientEditor<?> selected, Optional<FormTarget> target) {
      notice = null;
      boolean restoreFocus =
          (restoringDestination || navigation.restoring())
              && navigation.position(selectedKey).isPresent();
      rebuildWidgets();
      if (!restoreFocus && declaredTask() == null)
        selected.presentation().focusAfterLoad(target.orElse(new FormTarget.Field(FieldPath.ROOT)));
    }

    private void showOpeningFailure(Throwable failure) {
      while (failure instanceof CompletionException && failure.getCause() != null)
        failure = failure.getCause();
      notice =
          failure instanceof PolicyRefusal refused
              ? ClientStatusScreen.reason(
                  refused.report(), text("unavailable", "The policy is unavailable"))
              : text("opening_failed", "The current owner could not open this editor.");
      rebuildWidgets();
    }

    private void confirmDiscard(DocumentId id) {
      navigate(
          new UnsavedChangesScreen(
              text("discard_title", "Discard this document's draft?"),
              entry(id).form.title(),
              text(
                  "discard_help",
                  "Completed saves are retained. Discarding releases this editor; a later Open creates a new session."),
              text("review", "Review changes"),
              () ->
                  entry(id).editor != null && entry(id).editor.isOpen()
                      ? Optional.empty()
                      : Optional.of(text("context_ended", "This editing context ended.")),
              choice -> {
                NativeScreens.open(minecraft, this);
                try {
                  if (choice == UnsavedChangesScreen.Choice.DISCARD) {
                    discard(id);
                    rebuildWidgets();
                  } else if (choice == UnsavedChangesScreen.Choice.SAVE)
                    openDocument(id, Optional.empty());
                } catch (PolicyRefusal refused) {
                  notice =
                      ClientStatusScreen.reason(
                          refused.report(), text("unavailable", "Operation unavailable"));
                  rebuildWidgets();
                }
              },
              ClientEditingWorkflow.this::close));
    }

    @Override
    public void onClose() {
      if (inspection != null) {
        inspection.onClose();
        return;
      }
      closeWorkflow();
    }

    private void closeWorkflow() {
      if (!isOpen() || NativeScreens.current(minecraft) != this) return;
      var current = documents();
      if (current.stream().anyMatch(DocumentState::busy)
          || batch != null && batch.hasActiveOperations()
          || contributions.values().stream().anyMatch(Contributed::busy)) {
        notice =
            text(
                "wait",
                "Wait for pending operations and reconcile unknown results before closing.");
        rebuildWidgets();
        return;
      }
      if (current.stream()
          .anyMatch(state -> state.dirty() || state.opened() && !state.available())) {
        navigate(
            new UnsavedChangesScreen(
                text("unsaved", "Retained document drafts"),
                title,
                layout == null
                    ? text(
                        "exit_help",
                        "Review all documents before ordered saving, discard the remaining drafts, or keep editing. Previously completed saves are retained; documents do not form one atomic transaction.")
                    : text(
                        "exit_scoped_help",
                        "Return to the overview to review a scope or discard the remaining drafts."),
                text("review", "Review changes"),
                Optional::empty,
                choice -> {
                  if (choice == UnsavedChangesScreen.Choice.DISCARD) finish();
                  else {
                    NativeScreens.open(minecraft, this);
                    if (choice == UnsavedChangesScreen.Choice.SAVE && layout == null) {
                      try {
                        reviewAll();
                      } catch (PolicyRefusal refused) {
                        notice =
                            ClientStatusScreen.reason(
                                refused.report(), text("unavailable", "Review unavailable"));
                        rebuildWidgets();
                      }
                    }
                  }
                },
                ClientEditingWorkflow.this::close));
      } else finish();
    }

    private void finish() {
      // A domain/custom view may have started work after the decision was displayed.
      if (documents().stream().anyMatch(DocumentState::busy)
          || batch != null && batch.hasActiveOperations()
          || contributions.values().stream().anyMatch(Contributed::busy)) {
        if (NativeScreens.current(minecraft) != this) NativeScreens.open(minecraft, this);
        notice =
            text(
                "wait",
                "Wait for pending operations and reconcile unknown results before closing.");
        rebuildWidgets();
        return;
      }
      close();
      NativeScreens.open(minecraft, parent);
    }

    @Override
    protected boolean closeMountedView(FormView view) {
      onClose();
      return true;
    }

    @Override
    protected void showInformation(SettingsView<?> view, FieldPath path, Component label) {
      information.show(view, path, label);
    }

    private Optional<Runnable> informationDestination(FormInformation.Related link) {
      var bound = ownerEntries.get(link.document());
      if (bound == null) return Optional.empty();
      return Optional.of(
          () -> {
            try {
              openDocument(bound.form.route(), Optional.of(link.path()));
            } catch (PolicyRefusal refused) {
              showOpeningFailure(refused);
            }
          });
    }

    @Override
    public void tick() {
      if (!isOpen()) return;
      super.tick();
      if (!isOpen() || NativeScreens.current(minecraft) != this) return;
      if (information.tick()) rebuildWidgets();
      var focused = focusedView();
      DocumentId focusRoute = null;
      if (focused instanceof SettingsView<?> settings)
        for (var bound : entries.values())
          if (bound.form.document().equals(settings.document())) {
            focusRoute = bound.form.route();
            break;
          }
      if (language != Language.getInstance()
          || !displayed.equals(documents())
          || !displayedOperations.equals(contributionStates())
          || focusRoute != null && !focusRoute.equals(actionRoute)) rebuildWidgets();
    }

    private List<ContributionState> contributionStates() {
      return contributions.values().stream()
          .map(
              entry ->
                  new ContributionState(
                      entry.busy(),
                      entry.action.blocked().get().map(Component::copy),
                      entry.latest != null && entry.action.mayObserve().getAsBoolean()))
          .toList();
    }

    @Override
    public void removed() {
      if (!handingOff()) {
        information.clear();
        close();
      }
    }

    @Override
    protected void setInitialFocus() {
      if (hasCurrentFocus()) return;
      if (navigationWidgets != null && navigation.restoreSearchFocus())
        setFocused(navigationWidgets.search());
      else if (panel != null && !drawerOpen) {
        setFocused(panel);
        panel.focusVisibleControl();
      } else super.setInitialFocus();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
      if (key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
          && navigation.clearFocusedFilter()) return true;
      if (key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE && drawerOpen) {
        drawerOpen = false;
        rebuildWidgets();
        return true;
      }
      if (key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
          && information.open()
          && (getFocused() == informationPanel || informationPanel == null)) {
        information.close();
        return true;
      }
      if (key == com.mojang.blaze3d.platform.InputConstants.KEY_F
          && hasControlDown()
          && navigationWidgets != null
          && navigation.focusSearch()) {
        setFocused(navigationWidgets.search());
        return true;
      }
      return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public Component getNarrationMessage() {
      if (!isOpen()) return title;
      var message = title.copy().append(". ").append(scopeStatus());
      if (notice != null) message.append(". ").append(notice);
      if (inspection != null) message.append(". ").append(inspection.getNarrationMessage());
      if (actionRoute != null
          && entry(actionRoute).editor != null
          && entry(actionRoute).editor.isOpen())
        message.append(". ").append(entry(actionRoute).editor.presentation().contextMessage());
      return message;
    }

    private Component scopeStatus() {
      if (isHome())
        return Component.translatableWithFallback(
            "reform.workspace.home_status",
            "%s opened · %s with unsaved edits",
            documents().stream().filter(DocumentState::opened).count(),
            documents().stream().filter(DocumentState::dirty).count());
      if (!isOpen()) return text("context_ended", "This editing context ended.");
      if (actionRoute == null
          || entry(actionRoute).editor == null
          || !entry(actionRoute).editor.isOpen())
        return text("select_settings", "Choose settings to edit");
      return entry(actionRoute)
          .form
          .title()
          .copy()
          .append(": ")
          .append(entry(actionRoute).editor.presentation().statusMessage());
    }

    @Override
    public void render(FormCanvas graphics, int x, int y, float delta) {
      if (!isOpen()) return;
      if (information.guard()) rebuildWidgets();
      super.render(graphics, x, y, delta);
      if (outerFrame.title().isPresent()) header.render(graphics, font);
      var lines = font.split(scopeStatus(), Math.max(1, statusWidth));
      for (int i = 0; outerFrame.status().isPresent() && i < Math.min(2, lines.size()); i++)
        graphics.drawString(
            font,
            lines.get(i),
            outerFrame.status().orElseThrow().x(),
            statusTop + i * font.lineHeight,
            FormPanel.DESCRIPTION_TEXT);
    }
  }
}
