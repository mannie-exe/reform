package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Complete batch review over borrowed sessions; each write retains its actual owner receipt. */
final class BatchReviewView extends MetadataView implements ClientBatchInspection {
  private final DocumentBatch batch;
  private final BooleanSupplier available;
  private final Function<DocumentId, Component> label;
  private final java.util.function.Consumer<DocumentBatch.Entry> inspect;
  private final Runnable returned, abandoned;
  private DocumentBatch.Review review;
  private OperationHandle<?> pending;
  private OperationHandle<DocumentBatch.Result> submission;
  private ValidationReport readiness = ValidationReport.VALID;
  private FormPanel panel, detail;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private final List<Button> footer = new ArrayList<>();
  private java.util.Map<DocumentId, BatchReviewScreen.Fields> fieldPresentations =
      java.util.Map.of();
  private DocumentId selected;
  private int changePage, retainedFooter = -1;
  private boolean focusDetailOnMount, restoringPresentation, technical;
  private java.util.function.Supplier<ValidationReport> inputGuard = () -> ValidationReport.VALID;
  private static final int CHANGE_PAGE = 16;

  record Bookmark(
      TaskPanels.State panels, DocumentId selected, int page, int footer, boolean technical) {}

  Bookmark bookmark() {
    preserve();
    return new Bookmark(retained, selected, changePage, retainedFooter, technical);
  }

  void restore(Bookmark state) {
    restoringPresentation = true;
    try {
      retained = state.panels();
      selected = state.selected();
      changePage = state.page();
      retainedFooter = state.footer();
      technical = state.technical();
      super.rebuildWidgets();
    } finally {
      restoringPresentation = false;
    }
  }

  void withFieldPresentations(java.util.Map<DocumentId, BatchReviewScreen.Fields> fields) {
    if (minecraft != null) throw new IllegalStateException("Declare batch fields before mounting");
    fields = java.util.Map.copyOf(fields);
    if (!batch.documents().containsAll(fields.keySet()))
      throw new IllegalArgumentException("Batch presentation needs actual member identities");
    fieldPresentations = fields;
  }

  void withInputGuard(java.util.function.Supplier<ValidationReport> guard) {
    if (minecraft != null)
      throw new IllegalStateException("Declare the batch guard before mounting");
    inputGuard = Objects.requireNonNull(guard);
  }

  private FormChrome chrome;
  private Language language;
  private Component notice;
  private Object generation;
  private boolean requested, readable, handoff, closed;

  BatchReviewView(
      Component title,
      DocumentBatch batch,
      BooleanSupplier available,
      Function<DocumentId, Component> label,
      java.util.function.Consumer<DocumentBatch.Entry> inspect,
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

  boolean borrows(DocumentBatch actual) {
    return batch == actual;
  }

  boolean retained() {
    return !closed;
  }

  @Override
  FormPanel primary() {
    return panel;
  }

  @Override
  void preserve() {
    if (panels == null || !visible || restoringPresentation) return;
    retained = panels.remember(getFocused());
    retainedFooter = footer.indexOf(getFocused());
  }

  @Override
  void hide() {
    preserve();
    visible = false;
    focusDetailOnMount = false;
    generation = new Object();
  }

  @Override
  void retireForNavigation() {
    hide();
    closed = true;
  }

  @Override
  boolean ownsFocus(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    return focused == panel || focused == detail || footer.contains(focused);
  }

  @Override
  public Optional<DocumentId> selectedDocument() {
    ClientSettingsRuntime.requireClientThread();
    return mayRead() ? chosen().map(DocumentBatch.Entry::document) : Optional.empty();
  }

  private Optional<DocumentBatch.Entry> chosen() {
    return review == null
        ? Optional.empty()
        : review.entries().stream().filter(entry -> entry.document().equals(selected)).findFirst();
  }

  private void select(DocumentId document) {
    if (!mayRead()
        || review == null
        || review.entries().stream().noneMatch(entry -> entry.document().equals(document))) return;
    selected = document;
    changePage = 0;
    focusDetailOnMount = true;
    rebuildWidgets();
  }

  private Component fieldLabel(
      DocumentId document, design.inherent.reform.definition.FieldPath path) {
    var fields = fieldPresentations.get(document);
    return FieldLabels.input(
        document,
        path,
        text("document", "Document"),
        fields == null
            ? design.inherent.reform.presentation.FormPresentation.EMPTY.catalog()
            : fields.presentation().catalog());
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
    return batch.submissionReadiness(review).and(Objects.requireNonNull(inputGuard.get()));
  }

  @Override
  protected void init() {
    visible = true;
    generation = new Object();
    language = Language.getInstance();
    readable = mayRead();
    readiness = readiness();
    footer.clear();
    var refresh = button(text("refresh", "Refresh complete review"), this::refresh);
    refresh.active = !closed && available.getAsBoolean() && pending == null;
    footer.add(refresh);
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
    footer.add(save);
    if (submission != null) {
      var result = button(text("result", "Submission result"), this::showResult);
      result.active = readable;
      footer.add(result);
    }
    if (pending != null) {
      var cancel =
          button(text("cancel", "Request cancellation"), () -> pending.requestCancellation());
      cancel.active = !pending.snapshot().cancellationRequested();
      footer.add(cancel);
    }
    footer.add(button(backCaption(text("back", "Back to documents")), this::onClose));
    if (review != null && chosen().isEmpty())
      selected = review.entries().isEmpty() ? null : review.entries().getFirst().document();
    int control =
        theme().values().value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT);
    int indexWidth =
        theme()
            .values()
            .value(design.inherent.reform.presentation.ThemeValues.Role.MEASURE_NAVIGATION_MINIMUM);
    int detailsWidth =
        theme()
            .values()
            .value(
                design.inherent.reform.presentation.ThemeValues.Role.MEASURE_INFORMATION_MINIMUM);
    var first =
        new design.inherent.reform.presentation.TaskPresentation.Offer(
            new design.inherent.reform.presentation.FormGeometry.Size(
                indexWidth, control + 3 * font.lineHeight),
            indexWidth);
    var second =
        new design.inherent.reform.presentation.TaskPresentation.Offer(
            new design.inherent.reform.presentation.FormGeometry.Size(
                detailsWidth, control + 3 * font.lineHeight),
            detailsWidth);
    var policy =
        unreadableOrPending() || unified
            ? taskPresentation()
                .withPreference(
                    design.inherent.reform.presentation.TaskPresentation.Kind.INSPECTION,
                    design.inherent.reform.presentation.TaskPresentation.Preference.FOCUSED)
            : taskPresentation();
    chrome =
        TaskPanels.readingChrome(
            minecraft,
            new design.inherent.reform.presentation.FormGeometry.Placement(
                left, top, width, height),
            composed ? Component.empty() : title,
            Component.empty(),
            pending == null
                ? Component.empty()
                : text("working", "Checking the reviewed documents…"),
            footer,
            theme(),
            first,
            second);
    panels =
        new TaskPanels(
            chrome,
            policy,
            design.inherent.reform.presentation.TaskPresentation.Kind.INSPECTION,
            first,
            second,
            theme(),
            retained);
    panel = panels.primary();
    detail = panels.detail();
    if (composed) {
      prefix.accept(panel);
      panel.heading(title);
    }
    panel.row(
        "scope",
        Component.empty(),
        text(
            "scope",
            "Documents save in the displayed order. Earlier completed saves remain saved if a later document fails. Clean documents are checked without writing."));
    if (notice != null) panel.row("notice", text("status", "Review status"), notice);
    if (!readable)
      panel.row(
          "unavailable",
          Component.empty(),
          text(
              "unavailable",
              "Review material is unavailable until every document is readable and ready."));
    else if (review == null)
      panel.row(
          "pending",
          Component.empty(),
          text("pending", "Preparing complete candidate and source checks…"));
    else {
      panel.row(
          "validation",
          text("validation", "Review checks"),
          review.valid()
              ? text(
                  "valid",
                  "All document and cross-document checks passed. Sources and authority are rechecked on submission.")
              : text(
                  "invalid",
                  "Review checks did not pass. Inspect the affected document before saving."));
      if (!readiness.valid())
        panel.row(
            "readiness",
            text("readiness", "Save availability"),
            reason(readiness, text("unavailable", "Review unavailable")));
      for (var entry : review.entries()) {
        var title = label.apply(entry.document());
        var token = generation;
        var choose =
            FormPanel.navigationButton(
                title,
                panel.fieldWidth(),
                false,
                entry.document().equals(selected),
                () -> {
                  if (!closed && token == generation && currentHost()) select(entry.document());
                });
        choose.setTooltip(Tooltip.create(title));
        panel.row(
            "owner:" + entry.document(),
            Component.empty(),
            entry.intent() == DocumentBatch.Intent.COMMIT
                ? text("will_save", "Save this document")
                : text("check_only", "Check only; no write"),
            choose);
      }
      chosen().ifPresent(this::details);
    }
    if (composed) suffix.accept(detail);
    chrome.mountActions(this::addRenderableWidget, panel);
    var focused = panels.finish();
    addRenderableWidget(panel);
    if (detail != panel) addRenderableWidget(detail);
    if (focusDetailOnMount && readable && chosen().isPresent()) {
      focusDetailOnMount = false;
      detail.jump("detail:owner");
      detail.focusVisibleControl();
      setFocused(detail);
    } else if (retainedFooter >= 0
        && retainedFooter < footer.size()
        && footer.get(retainedFooter).active) setFocused(footer.get(retainedFooter));
    else focused.ifPresent(this::setFocused);
  }

  private boolean unreadableOrPending() {
    return !readable || review == null;
  }

  private void details(DocumentBatch.Entry entry) {
    detail.section("detail:owner", label.apply(entry.document()));
    detail.row(
        "detail:intent",
        text("intent", "Reviewed action"),
        entry.intent() == DocumentBatch.Intent.COMMIT
            ? text("will_save", "Save this document")
            : text("check_only", "Check only; no write"));
    detail.row(
        "detail:source",
        text("source", "Source"),
        Component.literal(entry.review().source().description()));
    var inspectButton =
        button(
            text("inspect", "Inspect fields"),
            label.apply(entry.document()),
            () -> {
              if (mayRead()) navigate(() -> inspect.accept(entry));
            });
    detail.actions("detail:inspect", inspectButton);
    diagnostics("detail:document", entry.preflight());
    var cross = review.check().documents().get(entry.document());
    if (cross != null) diagnostics("detail:cross", cross);
    var fields = fieldPresentations.get(entry.document());
    var order = new java.util.LinkedHashMap<design.inherent.reform.definition.FieldPath, Integer>();
    if (fields != null) for (var path : fields.order()) order.put(path, order.size());
    var changed =
        entry.review().comparison().changes().stream()
            .map(change -> change.path())
            .collect(java.util.stream.Collectors.toSet());
    var changes =
        FormFieldOrder.sort(
            entry.review().entries().stream()
                .filter(field -> changed.contains(field.path()))
                .toList(),
            order,
            design.inherent.reform.operation.DocumentReview.Entry::path);
    detail.section("detail:changes", text("change_preview", "Captured value changes"));
    boolean compared = entry.review().comparison().report().valid();
    detail.row(
        "detail:change-count",
        compared
            ? text(
                "value_changes", "%s value changes", entry.review().comparison().changes().size())
            : text(
                "comparison_unavailable",
                "Some values could not be compared. Inspect fields for the full diagnostics."),
        entry.review().comparison().authoredChanges()
            ? text(
                "authored",
                "Saved choices also changed. Inspect fields for their complete source details.")
            : Component.empty());
    int pages = Math.max(1, (changes.size() + CHANGE_PAGE - 1) / CHANGE_PAGE);
    changePage = Math.clamp(changePage, 0, pages - 1);
    if (pages > 1) {
      var previous =
          button(
              text("previous", "Previous fields"),
              () -> {
                if (changePage > 0) {
                  changePage--;
                  rebuildWidgets();
                }
              });
      var next =
          button(
              text("next", "Next fields"),
              () -> {
                if (changePage + 1 < pages) {
                  changePage++;
                  rebuildWidgets();
                }
              });
      previous.active = changePage > 0;
      next.active = changePage + 1 < pages;
      detail.row(
          "detail:page",
          text("page", "Page %s of %s", changePage + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int index = changePage * CHANGE_PAGE;
        index < Math.min(changes.size(), (changePage + 1) * CHANGE_PAGE);
        index++) {
      var field = changes.get(index);
      detail.row(
          "detail:change:" + index,
          fieldLabel(entry.document(), field.path()),
          text(
              "before_after",
              "Before: %s\nDraft: %s",
              design.inherent.reform.minecraft.NumericComponents.value(
                  field.numericValues().baseline(),
                  Component.literal(ReviewText.preview(field.baseline()))),
              design.inherent.reform.minecraft.NumericComponents.value(
                  field.numericValues().draft(),
                  Component.literal(ReviewText.preview(field.draft())))));
    }
    if (compared && changes.isEmpty())
      detail.row(
          "detail:no-values",
          Component.empty(),
          text(
              "no_value_changes",
              "No captured value changes. Authored choices and owner checks still determine the reviewed action."));
    detail.section("detail:effects", text("effects_title", "After saving"));
    if (entry.review().applicationEffects().rules().isEmpty())
      detail.row(
          "detail:effects-unknown",
          Component.empty(),
          text(
              "unknown_effect",
              "When these settings take effect has not been declared. Inspect the owner before saving."));
    else
      for (var rule : entry.review().applicationEffects().rules())
        detail.row(fieldLabel(entry.document(), rule.path()), ReviewText.application(rule));
    var more =
        button(
            technical
                ? text("hide_details", "Hide source details")
                : text("show_details", "Show source details"),
            () -> {
              technical = !technical;
              rebuildWidgets();
            });
    detail.actions("detail:technical", more);
    if (technical) {
      detail.row(
          "detail:identity", text("identity", "Review"), Component.literal(review.id().toString()));
      detail.row(
          "detail:document-id",
          text("document_id", "Document ID"),
          Component.literal(entry.document().toString()));
      detail.row(
          "detail:revision",
          text("source_revision", "Source revision"),
          Component.literal(entry.review().target().sourceRevision().toString()));
    }
  }

  private void diagnostics(String prefix, ValidationReport report) {
    if (report.valid()) return;
    if (report.issues().isEmpty())
      detail.row(
          prefix, Component.empty(), text("incomplete", "The required checks are incomplete."));
    for (int i = 0; i < report.issues().size(); i++)
      detail.row(
          prefix + ":" + i,
          text("problem", "Review problem"),
          Component.literal(report.issues().get(i).fallback()));
  }

  @Override
  protected void rebuildWidgets() {
    preserve();
    super.rebuildWidgets();
  }

  private Button button(Component caption, Runnable action) {
    return button(caption, Component.empty(), action);
  }

  private Button button(Component caption, Component context, Runnable action) {
    var token = generation;
    return theme()
        .buttonBuilder(
            caption,
            ignored -> {
              if (closed || token != generation || !currentHost()) return;
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
    var input = Objects.requireNonNull(inputGuard.get());
    if (!input.valid()) {
      notice =
          reason(
              input,
              text(
                  "input_unaccepted",
                  "Repair or revert unaccepted document input before reviewing."));
      rebuildWidgets();
      return;
    }
    review = null;
    notice = null;
    pending = batch.review(EntryPoint.GUI);
    rebuildWidgets();
  }

  private void save() {
    if (pending != null || review == null || !mayRead() || !readiness().valid()) return;
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
            () -> openScreen(ownerScreen()),
            abandoned);
    openScreen(result);
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
    if (!currentHost()) return;
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
    if (!closed && currentHost()) navigate(returned);
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
    if (getFocused() != null || panels == null) return;
    var region = retained.focused() == null ? retained.viewport() : retained.focused();
    var target = region == TaskPanels.Region.DETAIL ? detail : panel;
    setFocused(target);
    target.focusVisibleControl();
  }

  @Override
  public Component getNarrationMessage() {
    var description =
        text(
            "scope",
            "Documents save separately in the displayed order; inspect each owner's requirements.");
    return composed ? description : title.copy().append("\n").append(description);
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(graphics);
  }
}
