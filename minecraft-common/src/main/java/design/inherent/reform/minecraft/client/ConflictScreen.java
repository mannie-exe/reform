package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentConflicts;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.TaskPresentation;
import design.inherent.reform.presentation.ThemeValues;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

/** Native manual choices over the parent's actual editor. Resolution never performs its Save. */
public final class ConflictScreen<T> extends NativeFormScreen {
  private enum Choice {
    UNCHOSEN,
    BASELINE,
    OBSERVED,
    DRAFT;

    Optional<ConflictReview.Choice> value() {
      return this == UNCHOSEN
          ? Optional.empty()
          : Optional.of(ConflictReview.Choice.valueOf(name()));
    }
  }

  private final Component contextLabel;
  private final DocumentActions<T> actions;
  private final DocumentConflicts<T> conflicts;
  private final BooleanSupplier available;
  private final Consumer<Optional<DocumentConflicts.Resolution<T>>> returned;
  private final Runnable abandoned;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final Map<FieldPath, ConflictReview.Choice> choices = new LinkedHashMap<>();
  private final Map<FieldPath, CycleButton<Choice>> controls = new LinkedHashMap<>();
  private DocumentConflicts.Preview review;
  private OperationHandle<?> operation;
  private FormPanel panel, fields;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private Map<FieldPath, Integer> fieldOrder = Map.of();
  private design.inherent.reform.presentation.FormPresentation fieldPresentation =
      design.inherent.reform.presentation.FormPresentation.EMPTY;
  private FieldPath selected;
  private int fieldPage;
  private boolean activated, sourceDetails;
  private FormChrome chrome;
  private Object widgets;
  private Component notice;

  private boolean requested, refreshing, resolving, closed, returning, wasCurrent;

  public ConflictScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      BooleanSupplier available,
      Consumer<Optional<DocumentConflicts.Resolution<T>>> returned,
      Runnable abandoned) {
    super(title);
    this.contextLabel = Objects.requireNonNull(contextLabel);
    this.actions = Objects.requireNonNull(actions);
    conflicts = actions.conflicts();
    this.available = Objects.requireNonNull(available);
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
    review = conflicts.latest().orElse(null);
    if (review != null) {
      choices.putAll(conflicts.choices(review));
      requested = true;
    }
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.conflict." + key, fallback, arguments);
  }

  public Optional<DocumentConflicts.Preview> reviewedInput() {
    return closed ? Optional.empty() : Optional.ofNullable(review);
  }

  /** Current widget only, without source access, resolution or persistence authority. */
  public Optional<AbstractWidget> choiceControl(FieldPath path) {
    return Optional.ofNullable(controls.get(path));
  }

  /** Presentation order only, declared before mounting; resolution/capture order is unchanged. */
  public ConflictScreen<T> withFieldOrder(List<FieldPath> paths) {
    if (activated || closed)
      throw new IllegalStateException("Declare conflict field order before mounting");
    fieldOrder = FormFieldOrder.admit(actions, paths);
    return this;
  }

  private Component fieldLabel(FieldPath path) {
    return FieldLabels.input(
        actions.definition().id(),
        path,
        text("document", "Whole document"),
        fieldPresentation.catalog());
  }

  /** Retain the invoking form's captions/purpose; source and choice admission remain unchanged. */
  public ConflictScreen<T> withFieldPresentation(
      design.inherent.reform.presentation.FormPresentation presentation) {
    if (activated || closed)
      throw new IllegalStateException("Declare conflict field presentation before mounting");
    Objects.requireNonNull(presentation).requireCompatible(actions.definition().type());
    fieldPresentation = presentation;
    return this;
  }

  private boolean contextRecipe() {
    return actions.commitKind() == DocumentSession.CommitKind.CONTEXT;
  }

  private boolean ready() {
    return !closed
        && available.getAsBoolean()
        && NativeScreens.current(minecraft) == this
        && actions.view().availability() == EditorActor.Availability.READY;
  }

  private boolean current() {
    return ready() && review != null && conflicts.isCurrent(review);
  }

  private Component baseline() {
    return review != null && review.basis() == ConflictReview.Basis.SUBMITTED_DRAFT
        ? text("submitted", "Submitted draft")
        : contextRecipe()
            ? text("original_context", "Earlier observed context")
            : text("original_saved", "Earlier saved value");
  }

  private Component observed() {
    return contextRecipe()
        ? text("observed_context", "Observed context value")
        : text("observed_saved", "Observed saved value");
  }

  private Component label(Choice value) {
    return switch (value) {
      case UNCHOSEN -> text("unchosen", "Choose a value");
      case BASELINE -> baseline();
      case OBSERVED -> observed();
      case DRAFT -> text("draft", "Your draft");
    };
  }

  @Override
  protected void init() {
    activated = true;
    widgets = new Object();
    footer.clear();
    controls.clear();
    var resolve = button(text("resolve", "Resolve draft"), this::resolve);
    resolve.active = operation == null && current() && choices.size() == review.entries().size();
    var refresh =
        button(
            contextRecipe()
                ? text("read_context", "Read context values")
                : text("read_saved", "Read saved values"),
            this::refresh);
    refresh.active = operation == null && ready();
    var cancel = button(text("cancel", "Cancel operation"), this::cancel);
    cancel.active =
        operation != null && !resolving && !operation.snapshot().cancellationRequested();
    var back = button(text("back", "Back"), this::onClose);
    back.active = !resolving;
    int fieldWidth = 160;
    if (review != null)
      for (var entry : review.entries())
        fieldWidth = Math.max(fieldWidth, font.width(fieldLabel(entry.path())) + 24);
    int padding = 2 * theme().values().value(ThemeValues.Role.SPACE_CONTENT);
    int minimumHeight =
        3 * font.lineHeight + theme().values().value(ThemeValues.Role.CONTROL_HEIGHT) + padding;
    var policy =
        review == null || review.entries().isEmpty()
            ? taskPresentation()
                .withPreference(
                    TaskPresentation.Kind.INSPECTION, TaskPresentation.Preference.FOCUSED)
            : taskPresentation();
    var firstOffer =
        new TaskPresentation.Offer(new FormGeometry.Size(fieldWidth, minimumHeight), fieldWidth);
    var secondOffer =
        new TaskPresentation.Offer(new FormGeometry.Size(220 + padding, minimumHeight), 360);
    chrome =
        TaskPanels.readingChrome(
            minecraft,
            width,
            height,
            title,
            contextLabel,
            status(),
            footer,
            theme(),
            firstOffer,
            secondOffer);
    panels =
        new TaskPanels(
            chrome,
            policy,
            TaskPresentation.Kind.INSPECTION,
            firstOffer,
            secondOffer,
            theme(),
            retained);
    fields = panels.primary();
    panel = panels.detail();
    chrome.mountActions(this::addRenderableWidget, fields);
    fields.row(
        "primary:purpose",
        text("purpose", "Manual conflict review"),
        text(
            "help",
            "Choose every listed field. Resolving replaces this draft and resets its Undo history. Saving or applying remains separate."));
    if (review != null) {
      var entries =
          FormFieldOrder.sort(review.entries(), fieldOrder, DocumentConflicts.Entry::path);
      if (selected == null || entries.stream().noneMatch(entry -> entry.path().equals(selected)))
        selected = entries.isEmpty() ? null : entries.getFirst().path();
      appendIndex(entries);
      fields.row(
          "primary:source",
          text("source", "Observed source"),
          Component.literal(review.source().description()));
      fields.actions(
          "primary:source-disclosure",
          localAction(
              sourceDetails
                  ? text("hide_source_details", "Hide source details")
                  : text("source_details", "Source details"),
              () -> {
                sourceDetails = !sourceDetails;
                rebuildWidgets();
              }));
      if (sourceDetails) {
        fields.row(
            "primary:source-location",
            text("source_location", "Source location"),
            Component.literal(review.source().location().orElse(review.source().kind())));
        fields.row(
            "primary:baseline-revision", baseline(), Component.literal(review.baselineRevision()));
        fields.row(
            "primary:observed-revision", observed(), Component.literal(review.observedRevision()));
      }
      fields.row(
          "primary:raw-input",
          text("input", "Compared input"),
          text(
              "input_help",
              "Retained inactive values are included. Collections are chosen together; rows are not merged."));
      diagnostics("detail:source", observed(), review.observationReport());
      diagnostics(
          "detail:draft", text("draft_diagnostics", "Draft diagnostics"), review.draftValidation());
      var generation = widgets;
      for (int index = 0; index < entries.size(); index++) {
        var entry = entries.get(index);
        String key = "detail:entry:" + entry.path();
        var caption = fieldLabel(entry.path());
        panel.section(key, caption);
        FieldLabels.help(actions.definition().id(), entry.path(), fieldPresentation.catalog())
            .ifPresent(help -> panel.row(key + ":purpose", Component.empty(), help));
        panel.row(key + ":baseline", baseline(), Component.literal(entry.baseline()));
        panel.row(key + ":observed", observed(), Component.literal(entry.observed()));
        panel.row(key + ":draft", text("draft", "Your draft"), Component.literal(entry.draft()));
        var selected =
            choices.containsKey(entry.path())
                ? Choice.valueOf(choices.get(entry.path()).name())
                : Choice.UNCHOSEN;
        var control =
            NativeCycles.<Choice>builder(this::label, selected, Choice.values())
                .displayOnlyValue()
                .create(
                    0,
                    0,
                    180,
                    20,
                    text("use", "Use value"),
                    (widget, next) -> {
                      if (generation != widgets || !current() || operation != null) return;
                      try {
                        conflicts.choose(review, entry.path(), next.value());
                        if (next.value().isPresent())
                          choices.put(entry.path(), next.value().orElseThrow());
                        else choices.remove(entry.path());
                        notice = null;
                      } catch (RuntimeException refused) {
                        notice = failure(refused);
                      }
                      rebuildWidgets();
                    });
        control.active = operation == null && current();
        controls.put(entry.path(), control);
        panel.anchorRow(
            key + ":choice",
            caption,
            entry.wholeValue()
                ? text("whole", "Whole value, including retained input")
                : text("field", "Explicit field choice"),
            control);
      }
      if (!current())
        panel.row(
            "detail:stale",
            text("stale", "Review is no longer current"),
            text(
                "read_again",
                "Read the current values and choose again. Earlier draft work is retained."));
    }
    if (notice != null) fields.row("primary:notice", text("status", "Review status"), notice);
    addRenderableWidget(fields);
    if (panel != fields) addRenderableWidget(panel);
    panels.finish().ifPresent(this::setFocused);
  }

  private void appendIndex(List<DocumentConflicts.Entry> entries) {
    if (entries.isEmpty()) return;
    if (panels.arrangement() != TaskPresentation.Arrangement.ADJACENT) {
      var token = widgets;
      var control =
          NativeCycles.<FieldPath>builder(
                  this::fieldLabel,
                  selected,
                  entries.stream().map(DocumentConflicts.Entry::path).toList())
              .displayOnlyValue()
              .create(
                  0,
                  0,
                  180,
                  20,
                  text("field", "Field"),
                  (widget, path) -> {
                    if (!closed && widgets == token && NativeScreens.current(minecraft) == this)
                      reveal(path);
                  });
      control.active = entries.size() > 1;
      fields.row("primary:field", text("field", "Field"), Component.empty(), control);
      return;
    }
    int pages = Math.max(1, (entries.size() + 15) / 16);
    fieldPage = Math.clamp(fieldPage, 0, pages - 1);
    if (pages > 1) {
      var previous =
          localAction(
              text("previous_fields", "Previous fields"),
              () -> {
                fieldPage--;
                rebuildWidgets();
              });
      var next =
          localAction(
              text("next_fields", "Next fields"),
              () -> {
                fieldPage++;
                rebuildWidgets();
              });
      previous.active = fieldPage > 0;
      next.active = fieldPage + 1 < pages;
      fields.row(
          "primary:field-page",
          text("field_page", "Fields %s of %s", fieldPage + 1, pages),
          Component.empty(),
          previous,
          next);
    }
    for (int i = fieldPage * 16; i < Math.min(entries.size(), (fieldPage + 1) * 16); i++) {
      var path = entries.get(i).path();
      var choice =
          choices.containsKey(path) ? Choice.valueOf(choices.get(path).name()) : Choice.UNCHOSEN;
      fields.anchorRow(
          "primary:field:" + path,
          Component.empty(),
          label(choice),
          localAction(fieldLabel(path), () -> reveal(path)));
    }
  }

  private void reveal(FieldPath path) {
    selected = path;
    rebuildWidgets();
    setFocused(panel);
    panel.focusRow("detail:entry:" + path + ":choice");
  }

  private Button localAction(Component caption, Runnable action) {
    var token = widgets;
    return ClientControls.action(
            font,
            caption,
            () -> {
              if (!closed && widgets == token && NativeScreens.current(minecraft) == this)
                action.run();
            },
            theme())
        .build();
  }

  private void diagnostics(String key, Component title, ValidationReport report) {
    if (report.valid()) return;
    panel.section(key + ":diagnostics", title);
    if (!report.complete())
      panel.row(
          key + ":incomplete",
          text("incomplete", "Observation incomplete"),
          text("incomplete_help", "Read the current source before resolving."));
    int index = 0;
    for (var issue : report.issues())
      panel.row(
          key + ":issue:" + index++, fieldLabel(issue.path()), Component.literal(issue.fallback()));
  }

  private void prepare() {
    if (!ready() || operation != null) return;
    requested = true;
    refreshing = false;
    resolving = false;
    notice = null;
    try {
      operation = conflicts.preview(EntryPoint.GUI);
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  private void refresh() {
    if (!ready() || operation != null) return;
    refreshing = true;
    notice = null;
    try {
      operation = actions.load(EntryPoint.GUI);
    } catch (RuntimeException refused) {
      refreshing = false;
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  private void resolve() {
    if (operation != null || !current() || choices.size() != review.entries().size()) return;
    try {
      operation = conflicts.resolve(EntryPoint.GUI, review, Map.copyOf(choices));
      resolving = true;
      notice = null;
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  private void cancel() {
    if (operation == null || resolving) return;
    try {
      operation.requestCancellation();
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void tick() {
    if (closed || NativeScreens.current(minecraft) != this) return;
    if (!requested) {
      prepare();
      return;
    }
    if (operation != null && operation.snapshot().terminal()) {
      var result = operation.snapshot();
      operation = null;
      if (resolving) {
        resolving = false;
        if (result.result().orElse(null) instanceof DocumentConflicts.Resolution<?> resolved) {
          finish(Optional.of((DocumentConflicts.Resolution<T>) resolved));
          return;
        }
      } else if (refreshing) {
        refreshing = false;
        if (result.result().orElse(null) instanceof EditorActor.View<?> view
            && view.conflictObservationAvailable()) {
          prepare();
          return;
        }
      } else if (result.result().orElse(null) instanceof DocumentConflicts.Preview prepared) {
        review = prepared;
        choices.clear();
        choices.putAll(conflicts.choices(prepared));
      }
      notice =
          result.result().isEmpty()
              ? result.report().issues().isEmpty()
                  ? text("ended", "Review operation ended; earlier work is retained.")
                  : Component.literal(result.report().issues().getFirst().fallback())
              : null;
      if (result.result().orElse(null) instanceof EditorActor.View<?> view
          && !view.conflictObservationAvailable())
        notice =
            view.observationReport().valid()
                ? text("no_conflict", "No current conflict is available. Return to the editor.")
                : first(view.observationReport());
      wasCurrent = current();
      rebuildWidgets();
      if (review != null && current()) {
        if (selected != null) reveal(selected);
      }
    }
    boolean now = current();
    if (now != wasCurrent) {
      wasCurrent = now;
      rebuildWidgets();
    }
  }

  private Button button(Component label, Runnable callback) {
    var generation = widgets;
    var result =
        theme()
            .buttonBuilder(
                label,
                ignored -> {
                  if (!closed && widgets == generation && NativeScreens.current(minecraft) == this)
                    callback.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    footer.add(result);
    return result;
  }

  @Override
  protected void beforeResize() {
    if (panels != null) retained = panels.remember(getFocused());
  }

  @Override
  protected void rebuildWidgets() {
    beforeResize();
    super.rebuildWidgets();
  }

  @Override
  protected void setInitialFocus() {
    setFocused(panel);
    panel.focusVisibleControl();
  }

  private Component status() {
    if (operation != null) {
      if (operation.snapshot().cancellationRequested())
        return text("draining", "Cancellation requested; waiting for current work to stop.");
      if (resolving) return text("resolving", "Resolving choices; awaiting owner validation.");
      return refreshing
          ? text("reading", "Reading current values…")
          : text("preparing", "Preparing the complete comparison…");
    }
    if (notice != null) return notice;
    if (review == null) return text("waiting", "Read current values to prepare a review.");
    return text(
        "selection",
        "%s of %s choices selected. Saving or applying remains separate.",
        choices.size(),
        review.entries().size());
  }

  @Override
  public Component getNarrationMessage() {
    return title.copy().append(". ").append(contextLabel).append(". ").append(status());
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(graphics, status());
  }

  private void finish(Optional<DocumentConflicts.Resolution<T>> result) {
    returning = true;
    release();
    returned.accept(result);
  }

  @Override
  public void onClose() {
    if (!closed && !resolving && NativeScreens.current(minecraft) == this) finish(Optional.empty());
  }

  @Override
  public void removed() {
    if (!returning) {
      release();
      abandoned.run();
    }
  }

  private void release() {
    closed = true;
    widgets = new Object();
    if (operation != null && !resolving && !operation.snapshot().terminal()) {
      try {
        operation.requestCancellation();
      } catch (RuntimeException ignored) {
        /* Actual scope retains its result. */
      }
    }
  }

  private static Component first(ValidationReport report) {
    return report.issues().isEmpty()
        ? text("unavailable", "Required observation is unavailable")
        : Component.literal(report.issues().getFirst().fallback());
  }

  private static Component failure(Throwable failed) {
    while (failed instanceof java.util.concurrent.CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return failed instanceof PolicyRefusal refused
        ? first(refused.report())
        : text("failed", "Conflict review is unavailable. Earlier work is retained.");
  }
}
