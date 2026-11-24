package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.OperationScreen.Action;
import design.inherent.reform.minecraft.client.OperationScreen.Outcome;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.TaskPresentation;
import design.inherent.reform.presentation.ThemeValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Retained identified feedback over the original operation handle. */
final class OperationView<O> extends MetadataView implements ClientOperationInspection {
  record Bookmark(
      TaskPanels.State panels, String footer, boolean technical, int page, int diagnosticPage) {}

  private boolean restoringPresentation;

  Bookmark bookmark() {
    rememberPresentation();
    return new Bookmark(retained, retainedFooter, technical, page, diagnosticPage);
  }

  void restore(Bookmark state) {
    restoringPresentation = true;
    try {
      retained = state.panels();
      retainedFooter = state.footer();
      technical = state.technical();
      page = state.page();
      diagnosticPage = state.diagnosticPage();
      super.rebuildWidgets();
    } finally {
      restoringPresentation = false;
    }
  }

  OperationHandle<O> handle() {
    return operation;
  }

  Component context() {
    return contextLabel.copy();
  }

  boolean retained() {
    return !closed;
  }

  boolean available() {
    return readable();
  }

  @Override
  FormPanel primary() {
    return panel;
  }

  @Override
  void preserve() {
    rememberPresentation();
  }

  @Override
  void hide() {
    rememberPresentation();
    visible = false;
    generation = new Object();
  }

  @Override
  void retireForNavigation() {
    release();
  }

  @Override
  boolean ownsFocus(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    return focused == panel || focused == detail || controls.containsValue(focused);
  }

  private static final int PAGE_SIZE = 16;
  private final Component contextLabel;
  private final OperationHandle<O> operation;
  private final BooleanSupplier mayObserve;
  private final Function<O, Outcome> describe;
  private final List<Action> actions;
  private final Runnable returned, abandoned;
  private final Map<String, Button> controls = new LinkedHashMap<>();
  private Map<String, Optional<Component>> blocked = Map.of();
  private OperationSnapshot<O> observed;
  private Outcome outcome;
  private Component presentationFailure, notice;
  private FormPanel panel, detail;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private String retainedFooter;
  private boolean technical;
  private FormChrome chrome;
  private Language language;
  private Object generation;
  private int page, diagnosticPage;
  private boolean closed, returning;

  /**
   * mayObserve runs on the native lifecycle executor, never while drawing. It must check the actual
   * current scope/recipient and READ authority, not just compare an ID or version. describe
   * projects an admitted result once per observed revision and must perform no IO or domain
   * effects.
   */
  OperationView(
      Component title,
      Component contextLabel,
      OperationHandle<O> operation,
      BooleanSupplier mayObserve,
      Function<O, Outcome> describe,
      List<Action> actions,
      Runnable returned,
      Runnable abandoned) {
    super(Objects.requireNonNull(title).copy());
    this.contextLabel = Objects.requireNonNull(contextLabel).copy();
    this.operation = Objects.requireNonNull(operation);
    this.mayObserve = Objects.requireNonNull(mayObserve);
    this.describe = Objects.requireNonNull(describe);
    this.actions = List.copyOf(actions);
    if (actions.size() > 8 || actions.stream().map(Action::id).distinct().count() != actions.size())
      throw new IllegalArgumentException("Operation actions need distinct bounded identities");
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
  }

  private static Component text(String key, String fallback, Object... args) {
    return Component.translatableWithFallback("reform.operation." + key, fallback, args);
  }

  /** Current cached disclosure only. This neither looks up another job nor grants authority. */
  public Optional<OperationSnapshot<O>> observedOperation() {
    return closed ? Optional.empty() : Optional.ofNullable(observed);
  }

  private boolean readable() {
    try {
      return !closed && mayObserve.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private void observe() {
    var next = readable() ? operation.snapshot() : null;
    if (next == null || observed == null || next.revision() != observed.revision()) {
      observed = next;
      outcome = null;
      presentationFailure = null;
      if (next != null && next.result().isPresent()) {
        try {
          outcome = Objects.requireNonNull(describe.apply(next.result().orElseThrow()));
        } catch (RuntimeException failed) {
          presentationFailure =
              text(
                  "details_unavailable",
                  "The complete result details could not be presented. The actual operation result is retained.");
        }
      }
    }
    var ready = new LinkedHashMap<String, Optional<Component>>();
    for (var action : actions) {
      Optional<Component> reason;
      try {
        reason =
            observed == null
                ? Optional.of(
                    text("unavailable", "This operation is unavailable to the current context."))
                : Objects.requireNonNull(action.blocked().get()).map(Component::copy);
      } catch (RuntimeException unavailable) {
        reason = Optional.of(text("action_unavailable", "This action is currently unavailable."));
      }
      ready.put(action.id(), reason);
    }
    blocked = Map.copyOf(ready);
  }

  @Override
  protected void init() {
    visible = true;
    if (composed) returning = false;
    observe();
    generation = new Object();
    language = Language.getInstance();
    controls.clear();
    var footer = new ArrayList<Button>();
    var cancel = button("cancel", text("cancel", "Request cancellation"), this::cancel);
    cancel.active = observed != null && !observed.terminal() && !observed.cancellationRequested();
    footer.add(cancel);
    for (var action : actions) {
      var control = button(action.id(), action.label(), () -> invoke(action));
      control.active = blocked.get(action.id()).isEmpty();
      blocked.get(action.id()).ifPresent(reason -> control.setTooltip(Tooltip.create(reason)));
      footer.add(control);
    }
    footer.add(button("back", backCaption(text("back", "Back")), this::finish));
    boolean hasDetails =
        observed != null && technical
            || outcome != null
                && outcome.details().stream().anyMatch(item -> technical || !item.technical())
            || observed != null && !observed.report().valid()
            || presentationFailure != null
            || notice != null
            || blocked.values().stream().anyMatch(Optional::isPresent);
    var policy =
        hasDetails
            ? taskPresentation()
            : taskPresentation()
                .withPreference(
                    TaskPresentation.Kind.OPERATIONS, TaskPresentation.Preference.FOCUSED);
    int primaryWidth = theme().values().value(ThemeValues.Role.MEASURE_EDITOR_MINIMUM);
    int detailWidth = theme().values().value(ThemeValues.Role.MEASURE_INFORMATION_MINIMUM);
    int minimumHeight =
        3 * font.lineHeight + theme().values().value(ThemeValues.Role.CONTROL_HEIGHT);
    var firstOffer =
        new TaskPresentation.Offer(
            new FormGeometry.Size(primaryWidth, minimumHeight), primaryWidth);
    var secondOffer =
        new TaskPresentation.Offer(new FormGeometry.Size(detailWidth, minimumHeight), detailWidth);
    chrome =
        TaskPanels.readingChrome(
            minecraft,
            new FormGeometry.Placement(left, top, width, height),
            composed ? Component.empty() : title,
            composed ? Component.empty() : contextLabel,
            Component.empty(),
            footer,
            theme(),
            firstOffer,
            secondOffer);
    panels =
        new TaskPanels(
            chrome,
            unified
                ? policy.withPreference(
                    TaskPresentation.Kind.OPERATIONS, TaskPresentation.Preference.FOCUSED)
                : policy,
            TaskPresentation.Kind.OPERATIONS,
            firstOffer,
            secondOffer,
            theme(),
            retained);
    panel = panels.primary();
    detail = panels.detail();
    if (composed) {
      prefix.accept(panel);
      panel.heading(title);
      if (!contextLabel.getString().isEmpty()) panel.row(Component.empty(), contextLabel);
    }
    chrome.mountActions(this::addRenderableWidget, panel);
    if (observed == null) {
      panel.row(
          "primary:unavailable",
          text("unavailable", "This operation is unavailable to the current context."),
          Component.empty());
    } else {
      var summary = outcome == null ? Component.empty() : outcome.summary();
      boolean hasSummary = !summary.getString().isEmpty();
      if (hasSummary) panel.row("primary:result", text("result", "Result"), summary);
      panel.row("primary:state", text("state", "Actual state"), stateLabel(observed.state()));
      var progress = observed.progress();
      if (!observed.terminal()
          || progress.completed() > 0
          || progress.total().isPresent()
          || !progress.unit().isEmpty())
        panel.row(
            "primary:progress",
            text("progress", "Reported progress"),
            text(
                "progress_detail",
                "%s: %s %s · total %s",
                progress.phase(),
                progress.completed(),
                progress.unit(),
                progress.total().isPresent()
                    ? Long.toString(progress.total().getAsLong())
                    : text("unknown_total", "unknown").getString()));
      if (!hasSummary) {
        panel.row(
            "primary:effects",
            text("effects", "Effects"),
            observed.cancellationRequested() && !observed.terminal()
                ? text(
                    "draining",
                    "Cancellation requested; waiting for actual work to stop or drain. It is not rollback.")
                : observed.terminal()
                    ? text(
                        "terminal",
                        "The operation ended. Its state alone does not certify successful persistence or application.")
                    : text(
                        "running",
                        "Work is pending. Returning to the editor leaves it running; no completion percentage is inferred."));
      }
      panel.actions(
          "primary:technical",
          button(
              "technical",
              technical
                  ? text("hide_technical", "Hide technical details")
                  : text("show_technical", "Show technical details"),
              () -> {
                technical = !technical;
                rebuildWidgets();
              }));
      report("operation", text("diagnostics", "Operation diagnostics"), observed.report());
      if (outcome != null) details();
      if (presentationFailure != null)
        detail.row("detail:presentation", Component.empty(), presentationFailure);
      if (technical) {
        detail.row(
            "detail:identity",
            text("identity", "Operation"),
            Component.literal(observed.operation() + "\n" + observed.id()));
        detail.row(
            "detail:revision",
            text("revision", "Result revision"),
            Component.literal(Long.toString(observed.revision())));
      }
    }
    for (var action : actions)
      blocked
          .get(action.id())
          .ifPresent(reason -> detail.row("detail:blocked:" + action.id(), action.label(), reason));
    if (notice != null) detail.row("detail:notice", text("notice", "Request status"), notice);
    if (composed) suffix.accept(detail);
    var focus = panels.finish();
    addRenderableWidget(panel);
    if (detail != panel) addRenderableWidget(detail);
    if (retainedFooter != null
        && controls.containsKey(retainedFooter)
        && controls.get(retainedFooter).active) setFocused(controls.get(retainedFooter));
    else focus.ifPresent(this::setFocused);
  }

  private void details() {
    var details = outcome.details();
    var visible =
        java.util.stream.IntStream.range(0, details.size())
            .filter(i -> technical || !details.get(i).technical())
            .toArray();
    int pages = Math.max(1, (visible.length + PAGE_SIZE - 1) / PAGE_SIZE);
    page = Math.clamp(page, 0, pages - 1);
    if (pages > 1) {
      var token = generation;
      var previous =
          theme()
              .buttonBuilder(
                  text("previous", "Previous details"),
                  ignored -> {
                    if (live(token) && page > 0) {
                      page--;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 125, 20)
              .build();
      var next =
          theme()
              .buttonBuilder(
                  text("next", "Next details"),
                  ignored -> {
                    if (live(token) && page + 1 < pages) {
                      page++;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 110, 20)
              .build();
      previous.active = page > 0;
      next.active = page + 1 < pages;
      detail.row(
          "detail:page",
          text("page", "Result details %s of %s", page + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int i = page * PAGE_SIZE; i < Math.min(visible.length, (page + 1) * PAGE_SIZE); i++) {
      var item = details.get(visible[i]);
      detail.row("detail:value:" + visible[i], item.label(), item.value());
    }
  }

  private void report(
      String key, Component title, design.inherent.reform.validation.ValidationReport report) {
    if (!report.complete())
      detail.row(
          "detail:incomplete:" + key,
          title,
          text("incomplete", "The reported diagnostics are incomplete."));
    int pages = Math.max(1, (report.issues().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    diagnosticPage = Math.clamp(diagnosticPage, 0, pages - 1);
    if (pages > 1) {
      var token = generation;
      var previous =
          theme()
              .buttonBuilder(
                  text("previous_diagnostics", "Previous diagnostics"),
                  ignored -> {
                    if (live(token) && diagnosticPage > 0) {
                      diagnosticPage--;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 150, 20)
              .build();
      var next =
          theme()
              .buttonBuilder(
                  text("next_diagnostics", "Next diagnostics"),
                  ignored -> {
                    if (live(token) && diagnosticPage + 1 < pages) {
                      diagnosticPage++;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 135, 20)
              .build();
      previous.active = diagnosticPage > 0;
      next.active = diagnosticPage + 1 < pages;
      detail.row(
          "detail:diagnostic-page",
          text("diagnostic_page", "Diagnostics %s of %s", diagnosticPage + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int i = diagnosticPage * PAGE_SIZE;
        i < Math.min(report.issues().size(), (diagnosticPage + 1) * PAGE_SIZE);
        i++) {
      var issue = report.issues().get(i);
      detail.row(
          "detail:report:" + key + ":" + i,
          title.copy().append(" · ").append(issue.path().toString()),
          Component.literal(issue.fallback()));
    }
  }

  private boolean live(Object token) {
    return !closed && generation == token && minecraft != null && currentHost();
  }

  private Button button(String id, Component label, Runnable callback) {
    var token = generation;
    var control =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (live(token)) callback.run();
                })
            .bounds(0, 0, Math.max(40, font.width(label) + 20), 20)
            .build();
    controls.put(id, control);
    return control;
  }

  private void invoke(Action action) {
    if (!readable()) {
      rebuildWidgets();
      return;
    }
    observe();
    if (blocked.get(action.id()).isPresent()) {
      rebuildWidgets();
      return;
    }
    returning = true;
    release();
    action.invoke().run();
  }

  private void cancel() {
    if (!readable() || operation.snapshot().terminal()) return;
    try {
      operation
          .requestCancellation()
          .whenComplete(
              (ignored, failed) ->
                  minecraft.execute(
                      () -> {
                        if (closed || !currentHost()) return;
                        if (failed != null) {
                          var cause = failed;
                          while (cause instanceof CompletionException && cause.getCause() != null)
                            cause = cause.getCause();
                          notice =
                              text(
                                  "cancel_refused",
                                  "Cancellation was not admitted; the actual operation remains authoritative.");
                        }
                        rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      notice =
          text(
              "cancel_refused",
              "Cancellation was not admitted; the actual operation remains authoritative.");
      rebuildWidgets();
    }
  }

  @Override
  public void tick() {
    var before = observed;
    var reasons = blocked;
    observe();
    if ((before == null) != (observed == null)
        || before != null && observed != null && before.revision() != observed.revision()
        || !reasons.equals(blocked)
        || language != Language.getInstance()) rebuildWidgets();
  }

  private void rememberPresentation() {
    if (minecraft == null || restoringPresentation) return;
    if (panels != null) retained = panels.remember(getFocused());
    retainedFooter =
        controls.entrySet().stream()
            .filter(entry -> entry.getValue() == getFocused())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
  }

  @Override
  protected void beforeResize() {
    rememberPresentation();
  }

  @Override
  protected void rebuildWidgets() {
    rememberPresentation();
    super.rebuildWidgets();
  }

  @Override
  protected void setInitialFocus() {
    if (panels != null && (getFocused() == panels.primary() || getFocused() == panels.detail()))
      return;
    setFocused(controls.get("back"));
  }

  private static Component stateLabel(OperationSnapshot.State state) {
    return switch (state) {
      case QUEUED -> text("state.queued", "Queued");
      case RUNNING -> text("state.running", "Running");
      case CANCELLATION_REQUESTED -> text("state.cancellation_requested", "Cancellation requested");
      case COMPLETED -> text("state.completed", "Result available");
      case REFUSED -> text("state.refused", "Request refused");
      case FAILED -> text("state.failed", "Operation failed");
      case CANCELLED -> text("state.cancelled", "Cancelled");
    };
  }

  @Override
  public Component getNarrationMessage() {
    var message =
        title
            .copy()
            .append(". ")
            .append(contextLabel)
            .append(". ")
            .append(
                observed == null
                    ? text("unavailable", "This operation is unavailable to the current context.")
                    : text(
                        "narration_summary",
                        "State %s. %s: %s %s. Cancellation requested: %s.",
                        stateLabel(observed.state()),
                        observed.progress().phase(),
                        observed.progress().completed(),
                        observed.progress().unit(),
                        observed.cancellationRequested()));
    if (outcome != null) message.append(". ").append(outcome.summary());
    if (technical && observed != null)
      message.append(". ").append(observed.operation() + " " + observed.id());
    return message;
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(graphics);
  }

  private void finish() {
    if (closed) return;
    returning = true;
    if (composed) hide();
    else release();
    returned.run();
  }

  @Override
  public void onClose() {
    if (!closed && currentHost()) finish();
  }

  @Override
  public void removed() {
    if (!closed && !returning) {
      release();
      abandoned.run();
    }
  }

  private void release() {
    closed = true;
    generation = new Object();
    observed = null;
    outcome = null;
    blocked = Map.of();
  }
}
