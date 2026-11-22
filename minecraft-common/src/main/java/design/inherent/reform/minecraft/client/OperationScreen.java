package design.inherent.reform.minecraft.client;

import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.presentation.FormGeometry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Borrowed, identified operation feedback. Back leaves work running; cancellation is explicit. */
public final class OperationScreen<O> extends NativeFormScreen {
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
  private FormPanel panel;
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
    footer.add(button("back", text("back", "Back"), this::finish));
    var geometry =
        FormGeometry.controls(
            footer.stream().map(value -> new FormGeometry.Size(value.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int footerTop = height - 12 - geometry.height();
    for (int i = 0; i < footer.size(); i++) {
      var at = geometry.placements().get(i);
      var control = footer.get(i);
      control.setPosition(12 + at.x(), footerTop + at.y());
      control.setWidth(at.width());
      addRenderableWidget(control);
    }
    int bodyTop = 40 + font.split(contextLabel, Math.max(1, width - 24)).size() * font.lineHeight;
    panel =
        new FormPanel(
            minecraft,
            12,
            bodyTop,
            Math.max(1, width - 24),
            Math.max(1, footerTop - bodyTop - 8),
            false);
    if (observed == null) {
      panel.row(
          text("unavailable", "This operation is unavailable to the current context."),
          Component.empty());
    } else {
      var summary = outcome == null ? Component.empty() : outcome.summary();
      boolean hasSummary = !summary.getString().isEmpty();
      if (hasSummary) panel.row(text("result", "Result"), summary);
      panel.row(
          text("state", "Actual state"),
          text(
              "state_detail",
              "%s · result revision %s",
              observed.state().name(),
              observed.revision()));
      var progress = observed.progress();
      if (!observed.terminal()
          || progress.completed() > 0
          || progress.total().isPresent()
          || !progress.unit().isEmpty())
        panel.row(
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
      report("operation", text("diagnostics", "Operation diagnostics"), observed.report());
      if (outcome != null) details();
      if (presentationFailure != null) panel.row(Component.empty(), presentationFailure);
      panel.row(
          text("identity", "Operation"),
          Component.literal(observed.operation() + "\n" + observed.id()));
    }
    for (var action : actions)
      blocked.get(action.id()).ifPresent(reason -> panel.row(action.label(), reason));
    if (notice != null) panel.row(text("notice", "Request status"), notice);
    panel.finish(0);
    addRenderableWidget(panel);
  }

  private void details() {
    var details = outcome.details();
    int pages = Math.max(1, (details.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    page = Math.clamp(page, 0, pages - 1);
    if (pages > 1) {
      var token = generation;
      var previous =
          Button.builder(
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
          Button.builder(
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
      panel.row(
          text("page", "Result details %s of %s", page + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int i = page * PAGE_SIZE; i < Math.min(details.size(), (page + 1) * PAGE_SIZE); i++) {
      var detail = details.get(i);
      panel.row("detail:" + i, detail.label(), detail.value());
    }
  }

  private void report(
      String key, Component title, design.inherent.reform.validation.ValidationReport report) {
    if (!report.complete())
      panel.row(title, text("incomplete", "The reported diagnostics are incomplete."));
    int pages = Math.max(1, (report.issues().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    diagnosticPage = Math.clamp(diagnosticPage, 0, pages - 1);
    if (pages > 1) {
      var token = generation;
      var previous =
          Button.builder(
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
          Button.builder(
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
      panel.row(
          text("diagnostic_page", "Diagnostics %s of %s", diagnosticPage + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int i = diagnosticPage * PAGE_SIZE;
        i < Math.min(report.issues().size(), (diagnosticPage + 1) * PAGE_SIZE);
        i++) {
      var issue = report.issues().get(i);
      panel.row(
          "report:" + key + ":" + i,
          title.copy().append(" · ").append(issue.path().toString()),
          Component.literal(issue.fallback()));
    }
  }

  private boolean live(Object token) {
    return !closed
        && generation == token
        && minecraft != null
        && NativeScreens.current(minecraft) == this;
  }

  private Button button(String id, Component label, Runnable callback) {
    var token = generation;
    var control =
        Button.builder(
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
                        if (closed || NativeScreens.current(minecraft) != this) return;
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

  @Override
  protected void rebuildWidgets() {
    var bookmark = panel == null ? null : panel.bookmark();
    var panelFocus = panel == null ? null : panel.focusKey();
    var focus =
        controls.entrySet().stream()
            .filter(entry -> entry.getValue() == getFocused())
            .map(Map.Entry::getKey)
            .findFirst();
    super.rebuildWidgets();
    if (bookmark != null) panel.restore(bookmark);
    if (panelFocus != null) {
      setFocused(panel);
      panel.restoreFocus(panelFocus);
    } else focus.map(controls::get).filter(control -> control.active).ifPresent(this::setFocused);
  }

  @Override
  protected void setInitialFocus() {
    setFocused(controls.get("back"));
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
                        "narration",
                        "Operation %s. State %s. %s: %s %s. Cancellation requested: %s.",
                        observed.id().toString(),
                        observed.state().name(),
                        observed.progress().phase(),
                        observed.progress().completed(),
                        observed.progress().unit(),
                        observed.cancellationRequested()));
    if (outcome != null) message.append(". ").append(outcome.summary());
    return message;
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    graphics.drawWordWrap(
        font, contextLabel, 12, 30, Math.max(1, width - 24), FormPanel.DESCRIPTION_TEXT);
  }

  private void finish() {
    if (closed) return;
    returning = true;
    release();
    returned.run();
  }

  @Override
  public void onClose() {
    if (!closed && NativeScreens.current(minecraft) == this) finish();
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
