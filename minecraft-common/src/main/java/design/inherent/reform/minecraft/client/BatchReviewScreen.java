package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Complete batch review over borrowed sessions; each write retains its actual owner receipt. */
public final class BatchReviewScreen extends NativeFormScreen {
  private final DocumentBatch batch;
  private final BooleanSupplier available;
  private final Function<DocumentId, Component> label;
  private final BiConsumer<DocumentBatch.Entry, Screen> inspect;
  private final Runnable returned, abandoned;
  private DocumentBatch.Review review;
  private OperationHandle<?> pending;
  private OperationHandle<DocumentBatch.Result> submission;
  private ValidationReport readiness = ValidationReport.VALID;
  private FormPanel panel;
  private Language language;
  private Component notice;
  private Object generation;
  private boolean requested, readable, handoff, closed;

  public BatchReviewScreen(
      Component title,
      DocumentBatch batch,
      BooleanSupplier available,
      Function<DocumentId, Component> label,
      BiConsumer<DocumentBatch.Entry, Screen> inspect,
      Runnable returned,
      Runnable abandoned) {
    super(Objects.requireNonNull(title).copy());
    this.batch = Objects.requireNonNull(batch);
    this.available = Objects.requireNonNull(available);
    this.label = Objects.requireNonNull(label);
    this.inspect = Objects.requireNonNull(inspect);
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
  }

  public Optional<DocumentBatch.Review> reviewedInput() {
    ClientSettingsRuntime.requireClientThread();
    return mayRead() ? Optional.ofNullable(review) : Optional.empty();
  }

  public Optional<OperationHandle<DocumentBatch.Result>> submission() {
    ClientSettingsRuntime.requireClientThread();
    return mayRead() ? Optional.ofNullable(submission) : Optional.empty();
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.batch." + key, fallback, arguments);
  }

  private boolean mayRead() {
    try {
      if (closed || !available.getAsBoolean()) return false;
      batch.requireRead();
      return true;
    } catch (PolicyRefusal unavailable) {
      return false;
    }
  }

  private ValidationReport readiness() {
    if (!readable || review == null) return ValidationReport.VALID;
    return batch.submissionReadiness(review);
  }

  @Override
  protected void init() {
    generation = new Object();
    language = Language.getInstance();
    readable = mayRead();
    readiness = readiness();
    var controls = new ArrayList<Button>();
    var refresh = button(text("refresh", "Refresh complete review"), this::refresh);
    refresh.active = !closed && available.getAsBoolean() && pending == null;
    controls.add(refresh);
    var save = button(text("save", "Save reviewed documents"), this::save);
    boolean changed =
        review != null
            && review.entries().stream()
                .anyMatch(entry -> entry.intent() == DocumentBatch.Intent.COMMIT);
    save.active = readable && changed && readiness.valid() && pending == null;
    if (!changed)
      save.setTooltip(
          Tooltip.create(text("no_changes", "There are no changed documents to save.")));
    if (!readiness.valid())
      save.setTooltip(Tooltip.create(reason(readiness, text("unavailable", "Review unavailable"))));
    controls.add(save);
    if (submission != null) {
      var result = button(text("result", "Submission result"), this::showResult);
      result.active = readable;
      controls.add(result);
    }
    if (pending != null) {
      var cancel =
          button(text("cancel", "Request cancellation"), () -> pending.requestCancellation());
      cancel.active = !pending.snapshot().cancellationRequested();
      controls.add(cancel);
    }
    controls.add(button(text("back", "Back to documents"), this::onClose));
    var geometry =
        FormGeometry.controls(
            controls.stream().map(value -> new FormGeometry.Size(value.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int footer = height - 12 - geometry.height();
    for (int i = 0; i < controls.size(); i++) {
      var at = geometry.placements().get(i);
      var control = controls.get(i);
      control.setPosition(12 + at.x(), footer + at.y());
      control.setWidth(at.width());
      addRenderableWidget(control);
    }
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    panel =
        new FormPanel(minecraft, 12, 36, Math.max(1, width - 24), Math.max(1, footer - 44), false);
    panel.row(
        Component.empty(),
        text(
            "scope",
            "Documents save in the displayed order. A later refusal keeps earlier completed saves. Clean documents are checked without writing. Inspect an owner for its fields, effective values and application requirements."));
    if (notice != null) panel.row(text("status", "Review status"), notice);
    if (!readable) {
      panel.row(
          Component.empty(),
          text(
              "unavailable",
              "Review material is unavailable until every document is readable and ready."));
    } else if (review != null) {
      panel.row(text("identity", "Review"), Component.literal(review.id().toString()));
      panel.row(
          text("validation", "Complete preflight"),
          review.valid()
              ? text(
                  "valid",
                  "All document and cross-document checks passed. Sources and authority are rechecked on submission.")
              : text(
                  "invalid",
                  "Preflight did not pass. No batch write is authorized; inspect the document diagnostics."));
      if (!readiness.valid())
        panel.row(
            text("readiness", "Submission availability"),
            reason(readiness, text("unavailable", "Review unavailable")));
      for (var entry : review.entries()) {
        var details =
            Component.literal(entry.intent() == DocumentBatch.Intent.COMMIT ? "Save" : "Check only")
                .append(". ")
                .append(entry.review().source().description())
                .append(". ")
                .append(
                    text(
                        "changes",
                        "%s semantic changes; authored changes: %s",
                        entry.review().comparison().changes().size(),
                        entry.review().comparison().authoredChanges()));
        if (!entry.preflight().valid())
          details
              .append(". ")
              .append(
                  reason(
                      entry.preflight(),
                      text("invalid_document", "Document preflight incomplete")));
        var cross = review.check().documents().get(entry.document());
        if (cross != null && !cross.valid())
          details
              .append(". ")
              .append(reason(cross, text("invalid_cross", "Cross-document check incomplete")));
        if (entry.review().applicationEffects().rules().isEmpty())
          details
              .append(". ")
              .append(text("unknown_effect", "Application requirements undeclared"));
        else
          details
              .append(". ")
              .append(
                  text(
                      "effects",
                      "%s declared application scopes; inspect before saving",
                      entry.review().applicationEffects().rules().size()));
        var inspectButton =
            button(
                text("inspect", "Inspect fields"),
                label.apply(entry.document()),
                () -> {
                  if (mayRead()) navigate(() -> inspect.accept(entry, this));
                });
        inspectButton.setTooltip(Tooltip.create(label.apply(entry.document())));
        panel.row(
            entry.document().toString(), label.apply(entry.document()), details, inspectButton);
      }
    } else
      panel.row(
          Component.empty(), text("pending", "Preparing complete candidate and source checks…"));
    panel.finish(scroll);
    addRenderableWidget(panel);
    if (focus != null) {
      setFocused(panel);
      panel.restoreFocus(focus);
    }
  }

  private Button button(Component caption, Runnable action) {
    return button(caption, Component.empty(), action);
  }

  private Button button(Component caption, Component context, Runnable action) {
    var token = generation;
    return Button.builder(
            caption,
            ignored -> {
              if (closed || token != generation || NativeScreens.current(minecraft) != this) return;
              try {
                action.run();
              } catch (PolicyRefusal refused) {
                notice = reason(refused.report(), text("unavailable", "Operation unavailable"));
                rebuildWidgets();
              }
            })
        .bounds(0, 0, Math.max(60, font.width(caption) + 20), 20)
        .createNarration(
            original ->
                context.getString().isEmpty()
                    ? original.get()
                    : context.copy().append(": ").append(original.get()))
        .build();
  }

  private static Component reason(ValidationReport report, Component fallback) {
    return ClientStatusScreen.reason(report, fallback);
  }

  private void refresh() {
    if (pending != null || closed || !available.getAsBoolean()) return;
    requested = true;
    review = null;
    notice = null;
    pending = batch.review(EntryPoint.GUI);
    rebuildWidgets();
  }

  private void save() {
    if (pending != null
        || review == null
        || !mayRead()
        || !batch.submissionReadiness(review).valid()) return;
    submission = batch.commit(EntryPoint.GUI, review);
    pending = submission;
    showResult();
  }

  private void showResult() {
    if (submission == null || !mayRead()) return;
    var selected = submission;
    var result =
        new OperationScreen<>(
            text("result", "Submission result"),
            title,
            selected,
            this::mayRead,
            this::outcome,
            List.of(),
            () -> NativeScreens.open(minecraft, this),
            abandoned);
    navigate(() -> NativeScreens.open(minecraft, result));
  }

  private OperationScreen.Outcome outcome(DocumentBatch.Result result) {
    var rows = new ArrayList<OperationScreen.Detail>();
    rows.add(
        new OperationScreen.Detail(
            text("identity", "Review"), Component.literal(result.review().toString())));
    rows.add(
        new OperationScreen.Detail(
            text("scope_label", "Effect scope"),
            text(
                "result_scope",
                "These are separate owner receipts. Completed saves remain saved. Return to an owner to reconcile unknown outcomes or apply saved values; do not repeat an uncertain write.")));
    for (var actual : result.outcomes()) {
      var summary =
          actual.intent() == DocumentBatch.Intent.CHECK
              ? reason(
                  actual.report(), text("checked", "Read-only document check; no write requested"))
              : actual
                  .operation()
                  .flatMap(OperationSnapshot::result)
                  .filter(value -> value instanceof EditorActor.Commit<?>)
                  .map(value -> (EditorActor.Commit<?>) value)
                  .flatMap(EditorActor.Commit::receipt)
                  .map(CommitFeedback::summary)
                  .orElseGet(
                      () ->
                          reason(
                              actual.report(),
                              text("not_attempted", "No owner receipt was produced")));
      rows.add(new OperationScreen.Detail(label.apply(actual.document()), summary));
      actual
          .operation()
          .ifPresent(
              value ->
                  rows.add(
                      new OperationScreen.Detail(
                          label.apply(actual.document()),
                          Component.literal(value.id() + " · " + value.state()))));
    }
    rows.add(
        new OperationScreen.Detail(
            text("diagnostics", "Submission diagnostics"),
            reason(
                result.report(),
                text(
                    "complete",
                    "Requested owner operations completed; application requirements remain owner-specific"))));
    return new OperationScreen.Outcome(
        result.completed()
            ? text(
                "complete",
                "Requested owner operations completed; application requirements remain owner-specific")
            : text("stopped", "Submission stopped; inspect the separate owner results"),
        rows);
  }

  private void navigate(Runnable action) {
    handoff = true;
    try {
      action.run();
    } finally {
      handoff = false;
    }
  }

  @Override
  public void tick() {
    if (!requested && available.getAsBoolean()) refresh();
    if (pending != null && pending.snapshot().terminal()) {
      var observed = pending.snapshot();
      pending = null;
      readable = mayRead();
      if (readable && observed.result().orElse(null) instanceof DocumentBatch.Review captured)
        review = captured;
      else if (!observed.report().valid())
        notice = reason(observed.report(), text("unavailable", "Operation unavailable"));
      rebuildWidgets();
    }
    boolean nextRead = mayRead();
    if (readable != nextRead
        || language != Language.getInstance()
        || !readiness.equals(readiness())) rebuildWidgets();
  }

  @Override
  public void onClose() {
    if (!closed && NativeScreens.current(minecraft) == this) navigate(returned);
  }

  @Override
  public void removed() {
    if (!handoff && !closed) {
      closed = true;
      abandoned.run();
    }
  }

  @Override
  protected void setInitialFocus() {
    if (getFocused() == null && panel != null) setFocused(panel);
  }

  @Override
  public Component getNarrationMessage() {
    return title
        .copy()
        .append(". ")
        .append(
            text(
                "scope",
                "Documents save separately in the displayed order; inspect each owner's requirements."));
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
  }
}
