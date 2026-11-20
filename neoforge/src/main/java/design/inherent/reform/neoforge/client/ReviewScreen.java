package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentReview;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Identified source/value inspection, with an optional explicit conditional-submit handoff. */
public final class ReviewScreen<T> extends FormScreen {
  /** A borrowed frontend action; its recipient must submit the supplied exact revision. */
  public record Submission(Component label, Consumer<DraftRevision> submit) {
    public Submission {
      label = Objects.requireNonNull(label).copy();
      Objects.requireNonNull(submit);
    }

    @Override
    public Component label() {
      return label.copy();
    }
  }

  private enum Value {
    BASELINE,
    DRAFT,
    EFFECTIVE
  }

  private static final int SUMMARY_CHARACTERS = 512, ISSUE_PAGE = 16;
  private final Component contextLabel;
  private final DocumentActions<T> actions;
  private final BooleanSupplier available;
  private final Consumer<Optional<FieldPath>> returned;
  private final Runnable abandoned;
  private final Optional<Submission> submission;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final Map<FieldPath, String> searchIndex = new LinkedHashMap<>();
  private final EnumSet<Value> expanded = EnumSet.noneOf(Value.class);
  private DocumentReview review;
  private EditorActor.Validation validated;
  private OperationHandle<?> operation;
  private FormPanel panel;
  private FormEditBox filter;
  private FieldPath selected;
  private ValidationReport diagnostics = ValidationReport.VALID;
  private Component notice, narration = Component.empty();
  private Optional<Component> submissionReason = Optional.empty();
  private Language language;
  private Object widgets;
  private int statusTop, issuePage, changePage;
  private boolean requested, validating, closed, returning, showingExport, wasCurrent, wasAvailable;

  public ReviewScreen(
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
  ReviewScreen(
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

  public ReviewScreen(
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
    if (closed || minecraft == null || minecraft.screen != this) return false;
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
    var geometry =
        FormGeometry.controls(
            footer.stream().map(value -> new FormGeometry.Size(value.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int top = height - 12 - geometry.height();
    statusTop = top - 24;
    for (int i = 0; i < footer.size(); i++) {
      var at = geometry.placements().get(i);
      var control = footer.get(i);
      control.setPosition(12 + at.x(), top + at.y());
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
            Math.max(1, statusTop - bodyTop - 8),
            false);
    narration = title.copy().append(". ").append(contextLabel);
    if (review != null) content();
    else panel.row(text("source", "Source and values"), status());
    if (notice != null) panel.row("notice", text("notice", "View status"), notice);
    addRenderableWidget(panel);
    panel.finish(0);
    wasCurrent = current();
    wasAvailable = ready();
  }

  private void content() {
    var source =
        Component.literal(review.source().description())
            .append(" (" + review.source().kind() + ")");
    review.source().location().ifPresent(value -> source.append("\n").append(value));
    panel.row("source", text("source", "Observed source"), source);
    panel.row(
        "basis",
        text("basis", "Capture"),
        text(
            "basis_detail",
            "Draft version %s. Source revision %s. This view does not reread the source.",
            review.target().version(),
            review.target().sourceRevision()));
    narration = narration.copy().append(". ").append(source);
    review
        .draftBasis()
        .ifPresent(
            basis -> {
              var selected =
                  Component.literal(basis.source().description())
                      .append("\n")
                      .append(basis.revision());
              basis
                  .source()
                  .location()
                  .ifPresent(location -> selected.append("\n").append(location));
              var label = text("recipe_basis", "Draft recipe basis");
              panel.row("recipe-basis", label, selected);
              narration = narration.copy().append(". ").append(label).append(". ").append(selected);
            });
    if (submission.isPresent()) changes();
    if (!current())
      panel.row(
          "stale",
          text("stale", "Draft changed after this capture"),
          text("stale_help", "These details retain their original version. Refresh the view."));
    if (review.entries().isEmpty()) {
      selected = null;
      panel.row(text("no_fields", "No declared fields"), Component.empty());
      diagnosis();
      return;
    }
    if (filter == null) {
      filter = new FormEditBox(font, 0, 0, 160, 20, text("search", "Find a field"));
      filter.setMaxLength(256);
      filter.setHint(text("search", "Find a field"));
      filter.setResponder(
          ignored -> {
            if (!closed && minecraft.screen == this) rebuildWidgets();
          });
    }
    var query = filter.getValue().strip().toLowerCase(Locale.ROOT);
    var matches =
        review.entries().stream()
            .filter(value -> searchIndex.getOrDefault(value.path(), "").contains(query))
            .toList();
    panel.row(
        "search",
        text("search", "Find a field"),
        text("matches", "%s of %s fields match", matches.size(), review.entries().size()),
        filter);
    var entry =
        review.entries().stream()
            .filter(value -> value.path().equals(selected))
            .findFirst()
            .orElseGet(() -> review.entries().getFirst());
    selected = entry.path();
    var paths = new ArrayList<>(matches.stream().map(DocumentReview.Entry::path).toList());
    if (!paths.contains(selected)) paths.addFirst(selected);
    var token = widgets;
    var choice =
        CycleButton.<FieldPath>builder(this::label)
            .withValues(paths)
            .withInitialValue(selected)
            .displayOnlyValue()
            .create(
                0,
                0,
                180,
                20,
                text("field", "Field"),
                (button, path) -> {
                  if (closed || widgets != token || minecraft.screen != this) return;
                  selected = path;
                  expanded.clear();
                  rebuildWidgets();
                });
    choice.active = paths.size() > 1;
    panel.row(
        "field",
        text("field", "Field"),
        entry.wholeValue()
            ? text("whole", "Whole value; collection rows are not aligned by position.")
            : Component.empty(),
        choice);
    if (matches.isEmpty())
      panel.row(
          "no_matches",
          text("no_matches", "No matching fields"),
          text("selection_retained", "The selected capture remains visible."));
    value(Value.BASELINE, text("baseline", "Observed baseline"), Optional.of(entry.baseline()));
    value(Value.DRAFT, text("draft", "Draft at capture"), Optional.of(entry.draft()));
    value(Value.EFFECTIVE, text("effective", "Observed effective value"), entry.effective());
    panel.row(
        "application",
        text("application", "Owner-declared application"),
        entry
            .application()
            .<Component>map(this::application)
            .orElseGet(
                () ->
                    text(
                        "application_unknown",
                        "No application boundary is declared for this field. Saving does not certify live effects.")));
    if (review.effective().isEmpty())
      panel.row(
          "effective_state",
          text("effective_state", "Effective observation"),
          text("not_observed", "This owner has not supplied a qualified effective observation."));
    else {
      var effective = review.effective().orElseThrow();
      panel.row(
          "effective_state",
          text("effective_state", "Effective observation"),
          text(
              "effective_detail",
              "%s at revision %s; value details captured: %s. This is an observed snapshot.",
              effective.availability().name(),
              effective.revision(),
              effective.valuesCaptured()));
    }
    narration = narration.copy().append(". ").append(label(selected));
    diagnosis();
  }

  private void value(Value kind, Component title, Optional<String> value) {
    String raw = value.orElseGet(() -> text("no_value", "No qualified value details").getString());
    boolean limited = raw.length() > SUMMARY_CHARACTERS;
    Component summary =
        Component.literal(
            limited && !expanded.contains(kind) ? raw.substring(0, SUMMARY_CHARACTERS) + "…" : raw);
    narration =
        narration
            .copy()
            .append(". ")
            .append(title)
            .append(": ")
            .append(limited ? raw.substring(0, SUMMARY_CHARACTERS) + "…" : raw);
    if (limited) {
      var token = widgets;
      var expand =
          Button.builder(
                  expanded.contains(kind) ? text("collapse", "Collapse") : text("expand", "Expand"),
                  ignored -> {
                    if (closed || widgets != token || minecraft.screen != this) return;
                    if (!expanded.remove(kind)) expanded.add(kind);
                    rebuildWidgets();
                  })
              .bounds(0, 0, 80, 20)
              .build();
      panel.row("value:" + kind, title, summary, expand);
      panel.row(
          Component.empty(),
          text("characters", "%s characters in the complete captured value", raw.length()));
    } else panel.row("value:" + kind, title, summary);
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
        text("scope", "Affected owner"), Component.literal(review.target().document().toString()));
    panel.row(
        text("summary", "Compared with the observed baseline"),
        text(
            "summary_detail",
            "%s semantic changes. Authored input changed: %s.",
            comparison.changes().size(),
            comparison.authoredChanges()));
    panel.row(
        Component.empty(),
        text(
            "submit_boundary",
            "Submission rechecks the exact captured draft, complete validation, current authority and source. These declarations do not certify live world changes."));
    submitBlocked()
        .ifPresent(reason -> panel.row(text("before_submit", "Before submitting"), reason));
    var changes = comparison.changes();
    int pages = Math.max(1, (changes.size() + ISSUE_PAGE - 1) / ISSUE_PAGE);
    changePage = Math.clamp(changePage, 0, pages - 1);
    if (pages > 1) {
      var token = widgets;
      var previous =
          Button.builder(
                  text("previous_changes", "Previous changes"),
                  ignored -> {
                    if (!closed && widgets == token && minecraft.screen == this && changePage > 0) {
                      changePage--;
                      rebuildWidgets();
                    }
                  })
              .bounds(0, 0, 125, 20)
              .build();
      var next =
          Button.builder(
                  text("next_changes", "Next changes"),
                  ignored -> {
                    if (!closed
                        && widgets == token
                        && minecraft.screen == this
                        && changePage + 1 < pages) {
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
      panel.row("change:" + index, label(change.path()), detail);
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
        Button.builder(
                text("previous", "Previous"),
                ignored -> {
                  if (widgets == token && !closed && minecraft.screen == this && issuePage > 0) {
                    issuePage--;
                    rebuildWidgets();
                  }
                })
            .bounds(0, 0, 80, 20)
            .build();
    previous.active = issuePage > 0;
    var next =
        Button.builder(
                text("next", "Next"),
                ignored -> {
                  if (widgets == token
                      && !closed
                      && minecraft.screen == this
                      && issuePage + 1 < pages) {
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
            CycleButton.<FieldPath>builder(this::label)
                .withValues(locations)
                .withInitialValue(issue.path())
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
            Button.builder(
                    text("go_to", "Go to field"),
                    ignored -> {
                      if (!closed && widgets == token && current() && minecraft.screen == this)
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
        Button.builder(
                caption,
                ignored -> {
                  if (widgets == token && current() && minecraft.screen == this)
                    finish(Optional.of(path));
                })
            .bounds(0, 0, Math.max(64, Math.min(180, font.width(caption) + 20)), 20)
            .build();
    result.active = current() && operation == null;
    return result;
  }

  private Component label(FieldPath path) {
    return FieldLabels.input(actions.definition().id(), path, text("document", "Document"));
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
                      if (!closed && minecraft.screen == this && operation == selected) {
                        if (failed != null) notice = failure(failed);
                        rebuildWidgets();
                      }
                    }));
  }

  private boolean canExport() {
    return current() && operation == null && actions.view().validation().valid();
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
                          minecraft.setScreen(this);
                        }
                      })),
              () -> minecraft.setScreen(this),
              () -> {
                release();
                abandoned.run();
              });
      showingExport = true;
      try {
        minecraft.setScreen(output);
      } finally {
        showingExport = false;
      }
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
        || submission.isPresent() && !submissionReason.equals(submitBlocked())) rebuildWidgets();
  }

  private Button button(Component caption, Runnable callback) {
    var token = widgets;
    var control =
        Button.builder(
                caption,
                ignored -> {
                  if (!closed && widgets == token && minecraft.screen == this) callback.run();
                })
            .bounds(0, 0, Math.max(56, font.width(caption) + 20), 20)
            .build();
    footer.add(control);
    return control;
  }

  @Override
  protected void rebuildWidgets() {
    var bookmark = panel == null ? null : panel.bookmark();
    var focus = panel == null ? null : panel.focusKey();
    int footerFocus = footer.indexOf(getFocused());
    super.rebuildWidgets();
    if (bookmark != null) panel.restore(bookmark);
    if (focus != null) {
      setFocused(panel);
      panel.restoreFocus(focus);
    } else if (footerFocus >= 0
        && footerFocus < footer.size()
        && footer.get(footerFocus).active
        && footer.get(footerFocus).visible) setFocused(footer.get(footerFocus));
  }

  @Override
  protected void setInitialFocus() {
    setFocused(panel);
    panel.focusVisibleControl();
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
            ? text("ready", "Captured details; editing and source effects remain separate.")
            : text("stale_status", "Earlier capture; refresh to inspect the new draft.");
  }

  @Override
  public Component getNarrationMessage() {
    return narration.copy().append(". ").append(status());
  }

  @Override
  public void render(GuiGraphics graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    graphics.drawWordWrap(
        font, contextLabel, 12, 30, Math.max(1, width - 24), FormPanel.DESCRIPTION_TEXT);
    var lines = font.split(status(), Math.max(1, width - 24));
    for (int i = 0; i < Math.min(2, lines.size()); i++)
      graphics.drawString(
          font, lines.get(i), 12, statusTop + i * font.lineHeight, FormPanel.DESCRIPTION_TEXT);
  }

  private void finish(Optional<FieldPath> path) {
    returning = true;
    release();
    returned.accept(path);
  }

  @Override
  public void onClose() {
    if (!closed && minecraft.screen == this) finish(Optional.empty());
  }

  @Override
  public void removed() {
    if (!returning && !showingExport && !closed) {
      release();
      abandoned.run();
    }
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
