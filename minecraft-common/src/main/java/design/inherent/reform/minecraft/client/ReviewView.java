package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.client.ReviewScreen.Action;
import design.inherent.reform.minecraft.client.ReviewScreen.Submission;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentReview;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.TaskPresentation;
import design.inherent.reform.presentation.ThemeValues;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Identified source/value inspection, with an optional explicit conditional-submit handoff. */
final class ReviewView<T> extends FormView implements ClientInspection {
  private boolean composed, unified, visible;

  private boolean currentHost() {
    return visible && isCurrent();
  }

  private Consumer<FormPanel> prefix = ignored -> {}, suffix = ignored -> {};

  void compose(boolean unified, Consumer<FormPanel> prefix, Consumer<FormPanel> suffix) {
    composed = true;
    this.unified = unified;
    this.prefix = Objects.requireNonNull(prefix);
    this.suffix = Objects.requireNonNull(suffix);
  }

  private enum Value {
    BASELINE,
    DRAFT,
    EFFECTIVE
  }

  private static final int SUMMARY_CHARACTERS = 512, ISSUE_PAGE = 16;
  private ClientTheme scopedTheme;
  private TaskPresentation scopedTasks;

  void presentation(ClientTheme theme, TaskPresentation tasks) {
    scopedTheme = Objects.requireNonNull(theme);
    scopedTasks = Objects.requireNonNull(tasks);
  }

  @Override
  protected ClientTheme theme() {
    return scopedTheme == null ? super.theme() : scopedTheme;
  }

  private TaskPresentation taskPresentation() {
    return scopedTasks == null ? TaskPresentation.standard() : scopedTasks;
  }

  Component caption() {
    return title.copy();
  }

  design.inherent.reform.definition.DocumentId document() {
    return actions.definition().id();
  }

  boolean borrows(DocumentActions<?> actual) {
    return actions == actual;
  }

  void preserve() {
    rememberPresentation();
  }

  void retireForNavigation() {
    release();
  }

  void hide() {
    visible = false;
    rememberPresentation();
    if (operation != null && !operation.snapshot().terminal()) {
      try {
        cancel();
      } catch (RuntimeException refused) {
        notice = failure(refused);
      }
    }
  }

  FormPanel primary() {
    return fieldPanel;
  }

  @Override
  boolean ownsFocus(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    return focused == fieldPanel || focused == panel || footer.contains(focused);
  }

  private final Component contextLabel;
  private final DocumentActions<T> actions;
  private final BooleanSupplier available;
  private final Consumer<Optional<FieldPath>> returned;
  private final Runnable abandoned;
  private final Optional<Submission> submission;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private List<Action> reviewActions = List.of();
  private List<Optional<Component>> actionReasons = List.of();
  private final Map<FieldPath, String> searchIndex = new LinkedHashMap<>();
  private Map<FieldPath, Integer> fieldOrder = Map.of();
  private design.inherent.reform.presentation.FormPresentation fieldPresentation =
      design.inherent.reform.presentation.FormPresentation.EMPTY;
  private final EnumSet<Value> expanded = EnumSet.noneOf(Value.class);
  private DocumentReview review;
  private EditorActor.Validation validated;
  private OperationHandle<?> operation;
  private FormPanel panel, fieldPanel;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private int retainedFooter = -1;
  private FormChrome chrome;
  private FormEditBox filter;
  private FieldPath selected;
  private ValidationReport diagnostics = ValidationReport.VALID;
  private Component notice, narration = Component.empty();
  private Optional<Component> submissionReason = Optional.empty();
  private Language language;
  private Object widgets;
  private int issuePage, changePage, fieldPage;
  private boolean sourceDetails,
      activated,
      requested,
      validating,
      closed,
      returning,
      showingOperation,
      wasCurrent,
      wasAvailable;

  public ReviewView(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      Optional<FieldPath> selected,
      BooleanSupplier available,
      Consumer<Optional<FieldPath>> returned,
      Runnable abandoned) {
    this(title, contextLabel, actions, selected, available, returned, abandoned, Optional.empty());
  }

  /** Borrow an exact batch capture; this inspection performs no individual submission. */
  ReviewView(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      BooleanSupplier available,
      Consumer<Optional<FieldPath>> returned,
      Runnable abandoned,
      DocumentReview captured,
      ValidationReport preflight) {
    this(title, contextLabel, actions, Optional.empty(), available, returned, abandoned);
    if (!captured.target().document().equals(actions.definition().id()))
      throw new IllegalArgumentException("Review capture belongs to another document");
    review = captured;
    requested = true;
    selected = captured.entries().isEmpty() ? null : captured.entries().getFirst().path();
    var target = captured.target();
    validated =
        new EditorActor.Validation(
            target.context(),
            target.session(),
            target.generation(),
            target.version(),
            target.sourceRevision(),
            preflight);
    updateDiagnostics();
    index();
  }

  public ReviewView(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      Optional<FieldPath> selected,
      BooleanSupplier available,
      Consumer<Optional<FieldPath>> returned,
      Runnable abandoned,
      Optional<Submission> submission) {
    super(title);
    this.contextLabel = Objects.requireNonNull(contextLabel).copy();
    this.actions = Objects.requireNonNull(actions);
    this.selected = Objects.requireNonNull(selected).orElse(FieldPath.ROOT);
    this.available = Objects.requireNonNull(available);
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
    this.submission = Objects.requireNonNull(submission);
  }

  void withActions(List<Action> entries) {
    if (activated || closed)
      throw new IllegalStateException("Declare review actions before opening the screen");
    reviewActions = List.copyOf(entries);
  }

  /**
   * Declare the invoking form's visual field order before mounting. Unlisted captured fields keep
   * their existing catalogue order. This changes neither the actual capture nor the data codec.
   */
  public ReviewView<T> withFieldOrder(List<FieldPath> paths) {
    if (activated || closed)
      throw new IllegalStateException("Declare review field order before mounting");
    fieldOrder = FormFieldOrder.admit(actions, paths);
    return this;
  }

  private List<DocumentReview.Entry> orderedEntries() {
    return FormFieldOrder.sort(review.entries(), fieldOrder, DocumentReview.Entry::path);
  }

  /** Retain the invoking form's declared captions and purpose, independently of serialized IDs. */
  public ReviewView<T> withFieldPresentation(
      design.inherent.reform.presentation.FormPresentation presentation) {
    if (activated || closed)
      throw new IllegalStateException("Declare review field presentation before mounting");
    Objects.requireNonNull(presentation).requireCompatible(actions.definition().type());
    fieldPresentation = presentation;
    index();
    return this;
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.review." + key, fallback, arguments);
  }

  public Optional<DocumentReview> reviewedInput() {
    return closed ? Optional.empty() : Optional.ofNullable(review);
  }

  public Optional<FieldPath> selectedField() {
    return review == null || closed ? Optional.empty() : Optional.ofNullable(selected);
  }

  private boolean ready() {
    if (closed || minecraft == null || !currentHost()) return false;
    try {
      return available.getAsBoolean()
          && actions.view().availability() == EditorActor.Availability.READY;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  private boolean current() {
    return ready()
        && review != null
        && actions
            .view()
            .snapshot()
            .filter(value -> review.target().equals(DraftRevision.of(value)))
            .isPresent();
  }

  private boolean validationMatches() {
    if (review == null || validated == null) return false;
    var target = review.target();
    return target.context().equals(validated.context())
        && target.session().equals(validated.session())
        && target.generation() == validated.generation()
        && target.version() == validated.version()
        && target.sourceRevision().equals(validated.sourceRevision());
  }

  @Override
  protected void init() {
    activated = true;
    visible = true;
    widgets = new Object();
    language = Language.getInstance();
    wasCurrent = current();
    wasAvailable = ready();
    submissionReason = submission.isPresent() ? submitBlocked() : Optional.empty();
    footer.clear();
    var refresh = button(text("refresh", "Refresh view"), () -> request(false));
    refresh.active = ready() && operation == null;
    var validate = button(text("validate", "Validate"), () -> request(true));
    validate.active = ready() && operation == null;
    var export = button(text("export", "Export value…"), this::exportValue);
    export.active = canExport();
    actionReasons = reviewActions.stream().map(this::actionBlocked).toList();
    for (int i = 0; i < reviewActions.size(); i++) {
      var action = reviewActions.get(i);
      var blocked = actionReasons.get(i);
      var run = button(action.label(), () -> openAction(action));
      run.active = blocked.isEmpty();
      blocked.ifPresent(reason -> run.setTooltip(Tooltip.create(reason)));
    }
    submission.ifPresent(
        action -> {
          var commit = button(action.label(), this::submit);
          var blocked = submitBlocked();
          commit.active = blocked.isEmpty();
          blocked.ifPresent(reason -> commit.setTooltip(Tooltip.create(reason)));
        });
    if (operation != null) {
      var cancel = button(text("cancel", "Cancel"), this::cancel);
      cancel.active = !operation.snapshot().cancellationRequested();
    }
    var edit =
        button(
            text("edit", "Edit field"),
            () -> {
              if (current() && operation == null && selected != null) finish(Optional.of(selected));
            });
    edit.active = current() && selected != null && !review.entries().isEmpty() && operation == null;
    button(text("back", "Back"), () -> finish(Optional.empty()));
    int fieldWidth = theme().values().value(ThemeValues.Role.MEASURE_INFORMATION_MINIMUM);
    int comparisonWidth = theme().values().value(ThemeValues.Role.MEASURE_EDITOR_MINIMUM);
    int minimumHeight =
        3 * font.lineHeight + theme().values().value(ThemeValues.Role.CONTROL_HEIGHT);
    var firstOffer =
        new TaskPresentation.Offer(new FormGeometry.Size(fieldWidth, minimumHeight), fieldWidth);
    var secondOffer =
        new TaskPresentation.Offer(
            new FormGeometry.Size(comparisonWidth, minimumHeight), comparisonWidth);
    chrome =
        TaskPanels.readingChrome(
            minecraft,
            new FormGeometry.Placement(left, top, width, height),
            composed ? Component.empty() : title,
            composed ? Component.empty() : contextLabel,
            status(),
            footer,
            theme(),
            firstOffer,
            secondOffer);
    panels =
        new TaskPanels(
            chrome,
            !unified && review != null && !review.entries().isEmpty()
                ? taskPresentation()
                : taskPresentation()
                    .withPreference(
                        TaskPresentation.Kind.INSPECTION, TaskPresentation.Preference.FOCUSED),
            TaskPresentation.Kind.INSPECTION,
            firstOffer,
            secondOffer,
            theme(),
            retained);
    fieldPanel = panels.primary();
    panel = panels.detail();
    if (composed) {
      prefix.accept(fieldPanel);
      fieldPanel.heading(title);
      if (!contextLabel.getString().isEmpty()) fieldPanel.row(Component.empty(), contextLabel);
    }
    chrome.mountActions(this::addRenderableWidget, fieldPanel);
    narration = title.copy().append(". ").append(contextLabel);
    if (review != null) content();
    else panel.row(text("source", "Source and values"), status());
    if (notice != null) panel.row("detail:notice", text("notice", "View status"), notice);
    if (composed) suffix.accept(panel);
    var restored = panels.finish();
    addRenderableWidget(fieldPanel);
    if (fieldPanel != panel) addRenderableWidget(panel);
    if (retainedFooter >= 0
        && retainedFooter < footer.size()
        && footer.get(retainedFooter).active
        && footer.get(retainedFooter).visible) setFocused(footer.get(retainedFooter));
    else restored.ifPresent(this::setFocused);
    wasCurrent = current();
    wasAvailable = ready();
  }

  private void content() {
    var entries = orderedEntries();
    if (entries.isEmpty()) {
      selected = null;
      panel.row(text("no_fields", "No declared fields"), Component.empty());
      sourceFacts();
      diagnosis();
      return;
    }
    if (filter == null) {
      filter = new FormEditBox(font, 0, 0, 160, 20, text("search", "Find a field"));
      filter.setMaxLength(256);
      filter.setHint(text("search", "Find a field"));
      filter.setResponder(
          ignored -> {
            fieldPage = 0;
            if (!closed && currentHost()) rebuildWidgets();
          });
    }
    var query = filter.getValue().strip().toLowerCase(Locale.ROOT);
    var matches =
        entries.stream()
            .filter(value -> searchIndex.getOrDefault(value.path(), "").contains(query))
            .toList();
    fieldPanel.row(
        "primary:search",
        text("search", "Find a field"),
        text("matches", "%s of %s fields match", matches.size(), entries.size()),
        filter);
    var entry =
        entries.stream()
            .filter(value -> value.path().equals(selected))
            .findFirst()
            .orElseGet(() -> entries.getFirst());
    selected = entry.path();
    var paths = new ArrayList<>(matches.stream().map(DocumentReview.Entry::path).toList());
    if (!paths.contains(selected)) paths.addFirst(selected);
    if (panels.arrangement() == TaskPresentation.Arrangement.ADJACENT) {
      fieldIndex(paths);
      if (entry.wholeValue())
        fieldPanel.row(
            "primary:whole-value",
            Component.empty(),
            text("whole", "Whole value; collection rows are not aligned by position."));
    } else {
      var token = widgets;
      var choice =
          NativeCycles.<FieldPath>builder(this::label, selected, paths)
              .displayOnlyValue()
              .create(
                  0,
                  0,
                  180,
                  20,
                  text("field", "Field"),
                  (button, path) -> {
                    if (closed || widgets != token || !currentHost()) return;
                    selected = path;
                    expanded.clear();
                    rebuildWidgets();
                  });
      choice.active = paths.size() > 1;
      fieldPanel.row(
          "primary:field",
          text("field", "Field"),
          entry.wholeValue()
              ? text("whole", "Whole value; collection rows are not aligned by position.")
              : Component.empty(),
          choice);
    }
    if (matches.isEmpty())
      fieldPanel.row(
          "primary:no_matches",
          text("no_matches", "No matching fields"),
          text("selection_retained", "The selected capture remains visible."));
    if (submission.isPresent()) changes();
    if (!current())
      fieldPanel.row(
          "primary:stale",
          text("stale", "Draft changed after this capture"),
          text("stale_help", "These details retain their original version. Refresh the view."));
    panel.row("detail:selected", text("selected_field", "Selected field"), label(selected));
    FieldLabels.help(actions.definition().id(), selected, fieldPresentation.catalog())
        .ifPresent(help -> panel.row("detail:purpose", Component.empty(), help));
    actions
        .fields()
        .field(entry.path())
        .flatMap(
            field ->
                design.inherent.reform.minecraft.NumericComponents.bounds(field.type().shape()))
        .ifPresent(
            bounds -> {
              var title = text("numeric_bounds", "Numeric bounds");
              panel.row("numeric_bounds", title, bounds);
              narration = narration.copy().append(". ").append(title).append(": ").append(bounds);
            });
    value(
        Value.BASELINE,
        text("baseline", "Observed baseline"),
        Optional.of(entry.baseline()),
        entry.numericValues().baseline());
    value(
        Value.DRAFT,
        text("draft", "Draft at capture"),
        Optional.of(entry.draft()),
        entry.numericValues().draft());
    value(
        Value.EFFECTIVE,
        text("effective", "Observed effective value"),
        entry.effective(),
        entry.numericValues().effective());
    panel.row(
        "detail:application",
        text("application", "Application timing"),
        entry
            .application()
            .<Component>map(this::application)
            .orElseGet(
                () ->
                    text(
                        "application_unknown",
                        "No application boundary is declared for this field. Saving does not certify live effects.")));
    narration = narration.copy().append(". ").append(label(selected));
    sourceFacts();
    diagnosis();
  }

  private void sourceFacts() {
    var source = Component.literal(review.source().description());
    panel.row("detail:source", text("source", "Observed source"), source);
    panel.row(
        "detail:capture",
        text("basis", "Capture"),
        text("capture_help", "Refresh this view to capture the draft again."));
    narration = narration.copy().append(". ").append(source);
    var toggle =
        indexButton(
            sourceDetails
                ? text("hide_source_details", "Hide source details")
                : text("source_details", "Source details"),
            widgets,
            () -> {
              sourceDetails = !sourceDetails;
              rebuildWidgets();
            });
    panel.actions("detail:source-toggle", toggle);
    if (!sourceDetails) return;
    panel.row(
        "detail:document-identity",
        text("document_identity", "Settings ID"),
        Component.literal(review.target().document().toString()));
    panel.row(
        "detail:comparison-accounting",
        text("comparison_details", "Comparison details"),
        text(
            "summary_detail",
            "%s semantic changes. Authored input changed: %s.",
            review.comparison().changes().size(),
            review.comparison().authoredChanges()));
    if (submission.isPresent())
      panel.row(
          "detail:submission-boundary",
          Component.empty(),
          text(
              "submit_boundary",
              "Submission rechecks the exact captured draft, complete validation, current authority and source. These declarations do not certify live world changes."));
    if (review.effective().isEmpty())
      panel.row(
          "detail:effective_state",
          text("effective_state", "Effective observation"),
          text("not_observed", "This owner has not supplied a qualified effective observation."));
    else {
      var effective = review.effective().orElseThrow();
      panel.row(
          "detail:effective_state",
          text("effective_state", "Effective observation"),
          text(
              "effective_detail",
              "%s at revision %s; value details captured: %s. This is an observed snapshot.",
              effective.availability().name(),
              effective.revision(),
              effective.valuesCaptured()));
    }

    panel.row(
        "detail:source-kind",
        text("source_kind", "Source kind"),
        Component.literal(review.source().kind()));
    review
        .source()
        .location()
        .ifPresent(
            location ->
                panel.row(
                    "detail:source-location",
                    text("source_location", "Source location"),
                    Component.literal(location)));
    panel.row(
        "detail:capture-revision",
        text("basis", "Capture"),
        text(
            "basis_detail",
            "Draft version %s. Source revision %s. This view does not reread the source.",
            review.target().version(),
            review.target().sourceRevision()));
    if (selected != null)
      panel.row(
          "detail:field-identity",
          text("field_identity", "Field identity"),
          Component.literal(selected.toString()));
    review
        .draftBasis()
        .ifPresent(
            basis -> {
              var value =
                  Component.literal(basis.source().description())
                      .append("\n")
                      .append(basis.revision());
              basis.source().location().ifPresent(location -> value.append("\n").append(location));
              var caption = text("recipe_basis", "Draft recipe basis");
              panel.row("detail:recipe-basis", caption, value);
              narration = narration.copy().append(". ").append(caption).append(". ").append(value);
            });
  }

  private void fieldIndex(List<FieldPath> paths) {
    int pages = Math.max(1, (paths.size() + ISSUE_PAGE - 1) / ISSUE_PAGE);
    fieldPage = Math.clamp(fieldPage, 0, pages - 1);
    var token = widgets;
    if (pages > 1) {
      var previous =
          indexButton(
              text("previous_fields", "Previous fields"),
              token,
              () -> {
                fieldPage--;
                rebuildWidgets();
              });
      var next =
          indexButton(
              text("next_fields", "Next fields"),
              token,
              () -> {
                fieldPage++;
                rebuildWidgets();
              });
      previous.active = fieldPage > 0;
      next.active = fieldPage + 1 < pages;
      fieldPanel.row(
          "primary:field-page",
          text("field_page", "Fields %s of %s", fieldPage + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int at = fieldPage * ISSUE_PAGE;
        at < Math.min(paths.size(), (fieldPage + 1) * ISSUE_PAGE);
        at++) {
      var path = paths.get(at);
      var choose =
          indexButton(
              label(path),
              token,
              () -> {
                selected = path;
                expanded.clear();
                rebuildWidgets();
                setFocused(fieldPanel);
                fieldPanel.focusRow("primary:field:" + path);
              });
      choose.setTooltip(Tooltip.create(label(path)));
      fieldPanel.anchorRow(
          "primary:field:" + path,
          Component.empty(),
          path.equals(selected) ? text("selected_field", "Selected field") : Component.empty(),
          choose);
    }
  }

  private Button indexButton(Component label, Object token, Runnable selected) {
    return ClientControls.action(
            font,
            label,
            () -> {
              if (!closed && widgets == token && currentHost() && ready()) selected.run();
            },
            theme())
        .build();
  }

  private void value(
      Value kind,
      Component title,
      Optional<String> value,
      Optional<design.inherent.reform.presentation.NumericDisplay.Caption> caption) {
    String raw = value.orElseGet(() -> text("no_value", "No qualified value details").getString());
    boolean limited = raw.length() > SUMMARY_CHARACTERS;
    Component summary =
        design.inherent.reform.minecraft.NumericComponents.value(
            caption,
            Component.literal(
                limited && !expanded.contains(kind)
                    ? raw.substring(0, SUMMARY_CHARACTERS) + "…"
                    : raw));
    narration =
        narration
            .copy()
            .append(". ")
            .append(title)
            .append(": ")
            .append(
                design.inherent.reform.minecraft.NumericComponents.value(
                    caption,
                    Component.literal(limited ? raw.substring(0, SUMMARY_CHARACTERS) + "…" : raw)));
    if (limited) {
      var token = widgets;
      var expand =
          theme()
              .buttonBuilder(
                  expanded.contains(kind) ? text("collapse", "Collapse") : text("expand", "Expand"),
                  ignored -> {
                    if (closed || widgets != token || !currentHost()) return;
                    if (!expanded.remove(kind)) expanded.add(kind);
                    rebuildWidgets();
                  })
              .bounds(0, 0, 80, 20)
              .build();
      panel.row("detail:value:" + kind, title, summary, expand);
      panel.row(
          Component.empty(),
          text("characters", "%s characters in the complete captured value", raw.length()));
    } else panel.row("detail:value:" + kind, title, summary);
  }

  private Optional<Component> submitBlocked() {
    if (!current())
      return Optional.of(
          text("submit_stale", "Refresh this capture before submitting its changes."));
    if (operation != null)
      return Optional.of(text("submit_wait", "Wait for the current review operation to finish."));
    var view = actions.view();
    if (view.pending().isPresent())
      return Optional.of(
          text("submit_pending", "Reconcile the previous outcome before submitting again."));
    if (view.review().isPresent())
      return Optional.of(
          text("submit_conflict", "Return to the editor and resolve the source conflict."));
    if (!view.validation().valid() || !review.comparison().report().valid())
      return Optional.of(text("submit_invalid", "Repair the invalid draft before submitting."));
    return Optional.empty();
  }

  private void submit() {
    if (submission.isEmpty() || submitBlocked().isPresent()) return;
    var target = review.target();
    var callback = submission.orElseThrow().submit();
    returning = true;
    release();
    callback.accept(target);
  }

  private void changes() {
    var comparison = review.comparison();
    panel.heading(text("changes", "Changes in this capture"));
    panel.row(
        text("scope_summary", "Scope"),
        text("whole_document", "Whole document, including settings outside the current section."));
    int count = comparison.changes().size();
    Component summary =
        !comparison.report().valid()
            ? text(
                "comparison_needs_attention",
                "Some values could not be compared. See the diagnostics below.")
            : count == 0
                ? comparison.authoredChanges()
                    ? text("input_changes_only", "No value changes; entered input differs.")
                    : text("no_value_changes", "No value changes.")
                : count == 1
                    ? text("one_value_change", "1 value changed.")
                    : text("value_change_count", "%s values changed.", count);
    panel.row(text("summary", "Compared with the observed baseline"), summary);
    submitBlocked()
        .ifPresent(reason -> panel.row(text("before_submit", "Before submitting"), reason));
    var changes = comparison.changes();
    int pages = Math.max(1, (changes.size() + ISSUE_PAGE - 1) / ISSUE_PAGE);
    changePage = Math.clamp(changePage, 0, pages - 1);
    if (pages > 1) {
      var token = widgets;
      var previous =
          theme()
              .buttonBuilder(
                  text("previous_changes", "Previous changes"),
                  ignored -> {
                    if (!closed && widgets == token && currentHost() && changePage > 0) {
                      changePage--;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 125, 20)
              .build();
      var next =
          theme()
              .buttonBuilder(
                  text("next_changes", "Next changes"),
                  ignored -> {
                    if (!closed && widgets == token && currentHost() && changePage + 1 < pages) {
                      changePage++;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 110, 20)
              .build();
      previous.active = changePage > 0;
      next.active = changePage + 1 < pages;
      panel.row(
          text("change_page", "Page %s of %s · %s changes", changePage + 1, pages, changes.size()),
          Component.empty(),
          previous,
          next);
    }
    for (int index = changePage * ISSUE_PAGE;
        index < Math.min(changes.size(), (changePage + 1) * ISSUE_PAGE);
        index++) {
      var change = changes.get(index);
      var application = review.applicationEffects().forField(change.path());
      var detail =
          Component.literal(preview(change.beforeJson()) + " → " + preview(change.afterJson()));
      detail
          .append("\n")
          .append(
              application
                  .<Component>map(this::application)
                  .orElseGet(
                      () ->
                          text(
                              "application_unknown",
                              "No application boundary is declared for this field. Saving does not certify live effects.")));
      panel.row("detail:change:" + index, label(change.path()), detail);
    }
  }

  private static String preview(String value) {
    return value.length() > SUMMARY_CHARACTERS
        ? value.substring(0, SUMMARY_CHARACTERS) + "…"
        : value;
  }

  private Component application(ApplicationEffects.Rule rule) {
    return Component.literal(
            rule.timings().stream()
                .sorted()
                .map(value -> timing(value).getString())
                .collect(java.util.stream.Collectors.joining(", ")))
        .append("\n")
        .append(rule.explanation());
  }

  private void diagnosis() {
    var issues = diagnostics.issues();
    if (!diagnostics.complete())
      panel.row(text("incomplete", "Some observations are incomplete"), Component.empty());
    if (issues.isEmpty()) return;
    panel.heading(text("diagnostics", "Diagnostics"));
    int pages = (issues.size() - 1) / ISSUE_PAGE + 1;
    issuePage = Math.clamp(issuePage, 0, pages - 1);
    var token = widgets;
    var previous =
        theme()
            .buttonBuilder(
                text("previous", "Previous"),
                ignored -> {
                  if (widgets == token && !closed && currentHost() && issuePage > 0) {
                    issuePage--;
                    rebuildWidgets();
                  }
                })
            .bounds(0, 0, 80, 20)
            .build();
    previous.active = issuePage > 0;
    var next =
        theme()
            .buttonBuilder(
                text("next", "Next"),
                ignored -> {
                  if (widgets == token && !closed && currentHost() && issuePage + 1 < pages) {
                    issuePage++;
                    rebuildWidgets();
                  }
                })
            .bounds(0, 0, 64, 20)
            .build();
    next.active = issuePage + 1 < pages;
    panel.row(
        "diagnostics:page",
        text("page", "Page %s of %s · %s diagnostics", issuePage + 1, pages, issues.size()),
        Component.empty(),
        previous,
        next);
    for (int i = issuePage * ISSUE_PAGE;
        i < Math.min(issues.size(), (issuePage + 1) * ISSUE_PAGE);
        i++) {
      var issue = issues.get(i);
      var links = new ArrayList<AbstractWidget>();
      if (issue.related().isEmpty()) {
        links.add(destination(issue.path(), text("go_to", "Go to field"), token));
      } else {
        var uniqueLocations = new java.util.LinkedHashSet<FieldPath>();
        uniqueLocations.add(issue.path());
        uniqueLocations.addAll(issue.related());
        var locations = List.copyOf(uniqueLocations);
        var location =
            NativeCycles.<FieldPath>builder(this::label, issue.path(), locations)
                .displayOnlyValue()
                .create(
                    0,
                    0,
                    160,
                    20,
                    text("location", "Diagnostic location"),
                    (control, path) ->
                        control.setTooltip(Tooltip.create(Component.literal(path.toString()))));
        location.setTooltip(Tooltip.create(Component.literal(issue.path().toString())));
        links.add(location);
        var go =
            theme()
                .buttonBuilder(
                    text("go_to", "Go to field"),
                    ignored -> {
                      if (!closed && widgets == token && current() && currentHost())
                        finish(Optional.of(location.getValue()));
                    })
                .bounds(0, 0, 80, 20)
                .build();
        go.active = current() && operation == null;
        links.add(go);
      }
      panel.row(
          "issue:" + i,
          label(issue.path()),
          Component.literal(issue.fallback()),
          links.toArray(AbstractWidget[]::new));
    }
  }

  private Button destination(FieldPath path, Component caption, Object token) {
    var result =
        theme()
            .buttonBuilder(
                caption,
                ignored -> {
                  if (widgets == token && current() && currentHost()) finish(Optional.of(path));
                })
            .bounds(0, 0, Math.max(64, Math.min(180, font.width(caption) + 20)), 20)
            .build();
    result.active = current() && operation == null;
    return result;
  }

  private Component label(FieldPath path) {
    return FieldLabels.input(
        actions.definition().id(), path, text("document", "Document"), fieldPresentation.catalog());
  }

  private Component timing(ApplicationEffects.Timing value) {
    String fallback =
        switch (value) {
          case OWNER_PUBLICATION -> "Owner value publication";
          case EXPLICIT_RELOAD -> "Explicit reload";
          case REOPEN_VIEW -> "Reopen the view";
          case NEXT_OPERATION -> "Next operation";
          case NEXT_JOIN -> "Next join";
          case WORLD_RESTART -> "World or server restart";
          case GAME_RESTART -> "Game restart";
          case NEW_WORLD -> "New world only";
          case NEW_CHUNKS -> "Newly generated chunks";
          case INCOMPATIBLE -> "Incompatible with this context";
          case OWNER_MANAGED -> "Application managed by the owner";
        };
    return text("timing." + value.name().toLowerCase(Locale.ROOT), fallback);
  }

  private void index() {
    searchIndex.clear();
    if (review == null) return;
    for (var entry : review.entries())
      searchIndex.put(
          entry.path(),
          (entry.selector() + " " + label(entry.path()).getString()).toLowerCase(Locale.ROOT));
  }

  private void updateDiagnostics() {
    diagnostics = review == null ? ValidationReport.VALID : review.report();
    if (validationMatches()) diagnostics = diagnostics.and(validated.report());
  }

  private void request(boolean validate) {
    if (!ready() || operation != null) return;
    requested = true;
    validating = validate;
    notice = null;
    try {
      operation = validate ? actions.validate(EntryPoint.GUI) : actions.review(EntryPoint.GUI);
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  private void cancel() {
    if (operation == null) return;
    var selected = operation;
    selected
        .requestCancellation()
        .whenComplete(
            (ignored, failed) ->
                minecraft.execute(
                    () -> {
                      if (!closed && currentHost() && operation == selected) {
                        if (failed != null) notice = failure(failed);
                        rebuildWidgets();
                      }
                    }));
  }

  private boolean canExport() {
    return current() && operation == null && actions.view().validation().valid();
  }

  private Optional<Component> actionBlocked(Action action) {
    if (!canExport())
      return Optional.of(
          text("action_review", "Review the current valid draft before continuing."));
    return action.blocked(review.target());
  }

  private void openAction(Action action) {
    if (actionBlocked(action).isPresent()) return;
    try {
      showOperation(action.open(review.target(), () -> openScreen(ownerScreen()), this::abandon));
    } catch (PolicyRefusal refused) {
      notice = failure(refused);
      rebuildWidgets();
    }
  }

  private void showOperation(Screen output) {
    showingOperation = true;
    try {
      openScreen(output);
    } finally {
      showingOperation = false;
    }
  }

  private void abandon() {
    release();
    abandoned.run();
  }

  private void exportValue() {
    if (!canExport()) return;
    try {
      var target = review.target();
      var handle = actions.export(EntryPoint.GUI, target);
      var id = handle.snapshot().id();
      var output =
          new OperationScreen<>(
              text("export_title", "Export value"),
              text(
                  "export_context",
                  "Document %s · draft %s · in-memory JSON",
                  target.document().toString(),
                  target.version()),
              handle,
              () ->
                  !closed
                      && available.getAsBoolean()
                      && actions.view().availability() == EditorActor.Availability.READY
                      && actions.operation(id).filter(retained -> retained == handle).isPresent(),
              artifact ->
                  new OperationScreen.Outcome(
                      text(
                          "export_result",
                          "%s bytes ready to copy. The saved file and effective values are unchanged.",
                          artifact.size()),
                      List.of(
                          new OperationScreen.Detail(
                              text("export_schema", "Schema version"),
                              Component.literal(Integer.toString(artifact.schemaVersion()))),
                          new OperationScreen.Detail(
                              text("export_source", "Source revision"),
                              Component.literal(artifact.draft().sourceRevision())),
                          new OperationScreen.Detail(
                              text("export_checksum", "SHA-256"),
                              Component.literal(artifact.sha256())))),
              List.of(
                  new OperationScreen.Action(
                      "copy_json",
                      text("copy_json", "Copy JSON"),
                      () ->
                          handle.snapshot().result().isPresent()
                              ? Optional.empty()
                              : Optional.of(
                                  text("no_export", "Wait for an admitted export result.")),
                      () -> {
                        try {
                          minecraft.keyboardHandler.setClipboard(
                              handle.snapshot().result().orElseThrow().text());
                          notice = text("copied_json", "Copied the exported JSON.");
                        } catch (RuntimeException failed) {
                          notice = failure(failed);
                        } finally {
                          openScreen(ownerScreen());
                        }
                      })),
              () -> openScreen(ownerScreen()),
              this::abandon);
      showOperation(output);
    } catch (PolicyRefusal refused) {
      notice = failure(refused);
      rebuildWidgets();
    }
  }

  @Override
  public void tick() {
    if (!requested && ready()) request(false);
    if (operation != null && operation.snapshot().terminal()) {
      var result = operation.snapshot();
      operation = null;
      if (ready() && result.result().orElse(null) instanceof DocumentReview captured) {
        review = captured;
        var requestedField = selected == null ? FieldPath.ROOT : selected;
        selected =
            review.entries().stream()
                .map(DocumentReview.Entry::path)
                .filter(requestedField::startsWith)
                .max(java.util.Comparator.comparingInt(path -> path.segments().size()))
                .orElseGet(
                    () -> review.entries().isEmpty() ? null : review.entries().getFirst().path());
        expanded.clear();
        issuePage = 0;
        changePage = 0;
        index();
        notice = null;
      } else if (ready()
          && result.result().orElse(null) instanceof EditorActor.Validation checked) {
        validated = checked;
        notice =
            validationMatches()
                ? checked.report().valid()
                    ? text("valid", "The actual owner accepted validation.")
                    : text("invalid", "The actual owner reported validation issues.")
                : text(
                    "validation_other",
                    "Validation belongs to another draft version; refresh the view.");
      } else notice = first(result.report());
      updateDiagnostics();
      rebuildWidgets();
    }
    if (language != Language.getInstance()) {
      index();
      rebuildWidgets();
    }
    if (wasAvailable != ready()) {
      if (!ready()) {
        review = null;
        searchIndex.clear();
        updateDiagnostics();
      }
      rebuildWidgets();
    } else if (wasCurrent != current()
        || submission.isPresent() && !submissionReason.equals(submitBlocked())
        || !reviewActions.isEmpty()
            && !actionReasons.equals(reviewActions.stream().map(this::actionBlocked).toList()))
      rebuildWidgets();
  }

  private Button button(Component caption, Runnable callback) {
    var token = widgets;
    var control =
        theme()
            .buttonBuilder(
                caption,
                ignored -> {
                  if (!closed && widgets == token && currentHost()) callback.run();
                })
            .bounds(0, 0, Math.max(56, font.width(caption) + 20), 20)
            .build();
    footer.add(control);
    return control;
  }

  private void rememberPresentation() {
    if (!activated) return;
    if (panels != null) retained = panels.remember(getFocused());
    retainedFooter = footer.indexOf(getFocused());
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
    setFocused(fieldPanel);
    fieldPanel.focusVisibleControl();
  }

  private Component status() {
    if (operation != null)
      return operation.snapshot().cancellationRequested()
          ? text("draining", "Cancellation requested; waiting for current work to finish.")
          : validating
              ? text("validating", "Validating with the actual owner…")
              : text("preparing", "Preparing the identified view…");
    if (notice != null) return notice;
    if (!wasAvailable) return text("unavailable", "This editing owner is unavailable.");
    return review == null
        ? text("waiting", "Refresh the view to inspect observed values.")
        : wasCurrent
            ? text("captured_values", "Captured values")
            : text("stale_status", "Earlier capture; refresh to inspect the new draft.");
  }

  @Override
  public Component getNarrationMessage() {
    return narration.copy().append(". ").append(status());
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(graphics, status());
  }

  private void finish(Optional<FieldPath> path) {
    returning = true;
    release();
    returned.accept(path);
  }

  @Override
  public void onClose() {
    if (!closed && currentHost()) finish(Optional.empty());
  }

  @Override
  public void removed() {
    if (!returning && !showingOperation && !closed) abandon();
  }

  private void release() {
    closed = true;
    widgets = new Object();
    if (operation != null && !operation.snapshot().terminal()) {
      try {
        operation.requestCancellation();
      } catch (RuntimeException ignored) {
        /* Actual scope retains and drains its work. */
      }
    }
    review = null;
    validated = null;
    narration = Component.empty();
    searchIndex.clear();
  }

  private static Component first(ValidationReport report) {
    return report.issues().isEmpty()
        ? text("ended", "No qualified result is available.")
        : Component.literal(report.issues().getFirst().fallback());
  }

  private static Component failure(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return failed instanceof PolicyRefusal refused
        ? first(refused.report())
        : text("failed", "The view could not be prepared. Earlier draft work is retained.");
  }
}
