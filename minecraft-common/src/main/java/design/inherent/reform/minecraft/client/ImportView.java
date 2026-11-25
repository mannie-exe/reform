package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.importing.FileImportSource;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Explicit local import review. Stage changes the existing draft; Save remains with its owner. */
final class ImportView<T> extends MetadataView implements ClientImportInspection {
  private static final AtomicBoolean FILE_DIALOG = new AtomicBoolean();
  private final DocumentActions<T> actions;
  private final Component contextLabel;
  private final List<ImportFormat<T>> formats;
  private final BooleanSupplier available;
  private final Runnable returned, abandoned;
  private final Optional<ImportCatalog<T>> catalog;
  private final List<AbstractWidget> footer = new ArrayList<>();
  private ImportFormat<T> format;
  private ImportSource source;
  private DocumentImports<T> imports;
  private DocumentImports.Preview review;
  private OperationHandle<?> operation;
  private FormPanel panel, detail;
  private TaskPanels panels;
  private TaskPanels.State retained = TaskPanels.State.empty();
  private FormChrome chrome;
  private Component sourceLabel, notice;
  private Object widgetGeneration, selectionEpoch;
  private FileDialogRequest dialogRequest;
  private boolean picker, staging, closed, returning, activated, readable, wasCurrent = true;
  private java.util.Map<design.inherent.reform.definition.FieldPath, Integer> fieldOrder =
      java.util.Map.of();
  private design.inherent.reform.presentation.FormPresentation fieldPresentation =
      design.inherent.reform.presentation.FormPresentation.EMPTY;

  ImportView(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      List<? extends ImportFormat<T>> formats,
      BooleanSupplier available,
      Runnable returned,
      Runnable abandoned) {
    this(title, contextLabel, actions, formats, available, returned, abandoned, Optional.empty());
  }

  ImportView(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      List<? extends ImportFormat<T>> formats,
      BooleanSupplier available,
      Runnable returned,
      Runnable abandoned,
      Optional<ImportCatalog<T>> catalog) {
    super(title);
    this.contextLabel = Objects.requireNonNull(contextLabel);
    this.actions = Objects.requireNonNull(actions);
    this.formats = List.copyOf(formats);
    if (this.formats.isEmpty()
        || this.formats.size() > DocumentImports.Limits.STANDARD.maximumFormats()
        || this.formats.stream().anyMatch(item -> item.definition() != actions.definition()))
      throw new IllegalArgumentException("Import needs admitted formats for its actual document");
    this.format = this.formats.getFirst();
    this.available = Objects.requireNonNull(available);
    this.returned = Objects.requireNonNull(returned);
    this.abandoned = Objects.requireNonNull(abandoned);
    this.catalog = Objects.requireNonNull(catalog);
    if (catalog
        .filter(
            value ->
                value.definition() != actions.definition() || !value.parsers().equals(this.formats))
        .isPresent())
      throw new IllegalArgumentException(
          "Import choices require their exact definition and parsers");
  }

  record Bookmark(TaskPanels.State panels, int footer) {}

  private int retainedFooter = -1;
  private boolean focusReviewOnMount, previewWantsFocus, restoringPresentation;

  Bookmark bookmark() {
    preserve();
    return new Bookmark(retained, retainedFooter);
  }

  void restore(Bookmark state) {
    restoringPresentation = true;
    try {
      retained = state.panels();
      retainedFooter = state.footer();
      super.rebuildWidgets();
    } finally {
      restoringPresentation = false;
    }
  }

  void withFieldOrder(java.util.List<design.inherent.reform.definition.FieldPath> paths) {
    if (activated || closed)
      throw new IllegalStateException("Declare import field order before mounting");
    fieldOrder = FormFieldOrder.admit(actions, paths);
  }

  void withFieldPresentation(design.inherent.reform.presentation.FormPresentation presentation) {
    if (activated || closed)
      throw new IllegalStateException("Declare import field presentation before mounting");
    Objects.requireNonNull(presentation).requireCompatible(actions.definition().type());
    fieldPresentation = presentation;
  }

  private Component fieldLabel(design.inherent.reform.definition.FieldPath path) {
    return FieldLabels.input(
        actions.definition().id(),
        path,
        text("document", "Document value"),
        fieldPresentation.catalog());
  }

  private boolean mayRead() {
    try {
      if (closed || !available.getAsBoolean()) return false;
      actions.requireInspection();
      return true;
    } catch (PolicyRefusal unavailable) {
      return false;
    }
  }

  design.inherent.reform.definition.DocumentId document() {
    return actions.definition().id();
  }

  boolean borrows(DocumentActions<?> actual) {
    return actions == actual;
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
    if (panels != null && visible && !restoringPresentation) {
      retained = panels.remember(getFocused());
      if (footer.contains(getFocused())) retainedFooter = footer.indexOf(getFocused());
      else if (getFocused() == panel || getFocused() == detail) retainedFooter = -1;
    }
  }

  @Override
  void hide() {
    preserve();
    visible = false;
    focusReviewOnMount = false;
    previewWantsFocus = false;
    widgetGeneration = new Object();
    selectionEpoch = new Object();
    if (dialogRequest != null) dialogRequest.abandonQueued();
    picker = false;
    if (operation != null && !staging && !operation.snapshot().terminal())
      operation.requestCancellation();
  }

  @Override
  void retireForNavigation() {
    hide();
    release();
  }

  @Override
  boolean ownsFocus(net.minecraft.client.gui.components.events.GuiEventListener focused) {
    return focused == panel || focused == detail || footer.contains(focused);
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.import." + key, fallback, arguments);
  }

  /** Cached result currently shown by the native view; no parsing, validation or owner effect. */
  public Optional<DocumentImports.Preview> reviewedInput() {
    return mayRead() ? Optional.ofNullable(review) : Optional.empty();
  }

  private boolean ready() {
    var view = actions.view();
    return mayRead()
        && view.availability() == EditorActor.Availability.READY
        && view.snapshot().isPresent()
        && view.pending().isEmpty()
        && view.review().isEmpty()
        && available.getAsBoolean();
  }

  private boolean currentReview() {
    if (review == null || !ready()) return false;
    var snapshot = actions.view().snapshot().orElseThrow();
    var target = review.target();
    return target.context().equals(snapshot.context())
        && target.session().equals(snapshot.session())
        && target.generation() == snapshot.generation()
        && target.version() == snapshot.version()
        && target.sourceRevision().equals(snapshot.sourceRevision());
  }

  @Override
  protected void init() {
    activated = true;
    readable = mayRead();
    visible = true;
    if (composed) returning = false;
    widgetGeneration = new Object();
    footer.clear();
    var preview = button(text("preview", "Preview import"), this::preview);
    preview.active = ready() && imports != null && operation == null && !picker;
    var stage = button(text("stage", "Stage draft"), this::stage);
    stage.active = operation == null && !picker && currentReview() && review.stageable();
    var cancel = button(text("cancel", "Cancel operation"), this::cancel);
    cancel.active = operation != null && !staging && !operation.snapshot().cancellationRequested();
    var back = button(backCaption(text("back", "Back")), this::onClose);
    back.active = !staging;
    int sourceWidth =
        theme()
            .values()
            .value(design.inherent.reform.presentation.ThemeValues.Role.MEASURE_EDITOR_MINIMUM);
    int reviewWidth =
        theme()
            .values()
            .value(
                design.inherent.reform.presentation.ThemeValues.Role.MEASURE_INFORMATION_MINIMUM);
    int minimumHeight =
        3 * font.lineHeight
            + theme()
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.CONTROL_HEIGHT);
    var first =
        new design.inherent.reform.presentation.TaskPresentation.Offer(
            new design.inherent.reform.presentation.FormGeometry.Size(sourceWidth, minimumHeight),
            sourceWidth);
    var second =
        new design.inherent.reform.presentation.TaskPresentation.Offer(
            new design.inherent.reform.presentation.FormGeometry.Size(reviewWidth, minimumHeight),
            reviewWidth);
    var policy =
        review == null || !readable
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
            operation == null
                ? notice == null
                    ? text("footer", "Stage changes the draft only; Back retains earlier work.")
                    : notice
                : progress(operation.snapshot()),
            footer,
            theme(),
            first,
            second);
    panels =
        new TaskPanels(
            chrome,
            unified
                ? policy.withPreference(
                    design.inherent.reform.presentation.TaskPresentation.Kind.INSPECTION,
                    design.inherent.reform.presentation.TaskPresentation.Preference.FOCUSED)
                : policy,
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
    chrome.mountActions(this::addRenderableWidget, panel);
    if (!readable) {
      panel.row(
          "unavailable",
          text("unavailable", "Import is unavailable. Your draft is retained."),
          Component.empty());
      finishPanels();
      return;
    }
    panel.row(
        "purpose",
        text("draft_only", "Import into this draft"),
        text(
            "help",
            "Review the changes before replacing this draft. One Undo restores the previous draft. Nothing is saved or applied."));
    panel.row(
        "target",
        text("target", "Target document"),
        Component.literal(
            actions.definition().id() + " · " + actions.definition().schemaVersion()));
    panel.row("target_context", text("context", "Editing context"), contextLabel);
    catalog
        .filter(value -> !value.sources().isEmpty())
        .ifPresent(
            choices -> {
              var options = new ArrayList<Optional<ImportCatalog.Source>>();
              options.add(Optional.empty());
              choices.sources().forEach(choice -> options.add(Optional.of(choice)));
              var selected =
                  options.stream()
                      .filter(
                          choice ->
                              choice.isPresent()
                                  && choices
                                      .admittedSource(choice.orElseThrow().id())
                                      .filter(bound -> bound == source)
                                      .isPresent())
                      .findFirst()
                      .orElse(Optional.empty());
              var chooseSource =
                  NativeCycles.<Optional<ImportCatalog.Source>>builder(
                          choice ->
                              choice
                                  .<Component>map(value -> Component.literal(value.label()))
                                  .orElseGet(
                                      () -> text("choose_registered", "Choose an admitted source")),
                          selected,
                          options)
                      .displayOnlyValue()
                      .create(
                          0,
                          0,
                          180,
                          20,
                          text("registered", "Registered source"),
                          (button, choice) -> {
                            if (currentHost() && ready() && operation == null && !picker)
                              choice.ifPresent(
                                  value ->
                                      selectSource(
                                          choices.admittedSource(value.id()).orElseThrow()));
                          });
              chooseSource.active = ready() && operation == null && !picker;
              chooseSource.setTooltip(
                  Tooltip.create(
                      selected
                          .<Component>map(value -> Component.literal(value.label()))
                          .orElseGet(
                              () -> text("choose_registered", "Choose an admitted source"))));
              panel.row(
                  "registered_source",
                  text("registered", "Registered source"),
                  text(
                      "registered_help",
                      "These sources are explicitly admitted by this owner. Selection alone reads no file."),
                  chooseSource);
            });
    var choose = action(text("choose", "Choose file…"), this::chooseFile);
    choose.active = ready() && operation == null && !picker;
    panel.row(
        "source",
        text("source", "Local source"),
        sourceLabel == null ? text("drop", "Choose or drop one local file here.") : sourceLabel,
        choose);
    var generation = widgetGeneration;
    var mode =
        NativeCycles.<ImportFormat<T>>builder(
                value -> Component.literal(value.label()), format, formats)
            .displayOnlyValue()
            .create(
                0,
                0,
                180,
                20,
                text("format", "Source format"),
                (widget, selected) -> {
                  if (!closed
                      && widgetGeneration == generation
                      && currentHost()
                      && operation == null
                      && !picker) {
                    format = selected;
                    review = null;
                    notice = null;
                    if (source != null) selectSource(source);
                    else rebuildWidgets();
                  }
                });
    mode.active = operation == null && !picker && ready();
    panel.row(
        "format",
        text("format", "Source format"),
        text("format_help", "Select the declared format. No filename or content-based guessing."),
        mode);
    if (review != null) showReview();
    if (notice != null) panel.row("notice", text("notice", "Import status"), notice);
    finishPanels();
  }

  private void finishPanels() {
    if (composed) suffix.accept(detail);
    var focus = panels.finish();
    addRenderableWidget(panel);
    if (detail != panel) addRenderableWidget(detail);
    if (focusReviewOnMount && review != null && readable) {
      focusReviewOnMount = false;
      focusReview();
    } else if (retainedFooter >= 0
        && retainedFooter < footer.size()
        && footer.get(retainedFooter).active) setFocused(footer.get(retainedFooter));
    else focus.ifPresent(this::setFocused);
  }

  private void showReview() {
    detail.section("detail:reviewed", text("review", "Reviewed input"));
    detail.row(
        "detail:identity",
        Component.literal(review.source().label()),
        text(
            "identity",
            "%s bytes · SHA-256 %s",
            review.source().bytes(),
            review.source().sha256()));
    detail.row(
        "detail:version",
        text("version", "Declared source schema"),
        review.sourceSchemaVersion().isPresent()
            ? Component.literal(Integer.toString(review.sourceSchemaVersion().getAsInt()))
            : text("unversioned", "Unversioned document value; target version is not inferred."));
    diagnostics(
        "current",
        text("current_diagnostics", "Current draft diagnostics"),
        review.currentValidation());
    diagnostics(
        "imported",
        text("imported_diagnostics", "Imported draft diagnostics"),
        review.importedValidation());
    if (!review.comparisonAvailable())
      detail.row(
          "detail:unavailable_compare",
          text("compare_unavailable", "Comparison unavailable"),
          text(
              "compare_help",
              "Invalid data is retained with diagnostics. It is not an empty successful comparison."));
    else if (review.changes().isEmpty())
      detail.row(
          "detail:no_changes",
          text("equivalent", "No typed value changes"),
          text(
              review.inputChanges() ? "input_changed" : "unchanged",
              review.inputChanges()
                  ? "Authored or inactive editor input will still be replaced."
                  : "The imported input matches this draft."));
    else {
      detail.heading(text("changes", "Typed changes (%s)", review.changes().size()));
      int index = 0;
      for (var change :
          FormFieldOrder.sort(review.changes(), fieldOrder, DocumentActions.Change::path))
        detail.row(
            "detail:change:" + index++,
            fieldLabel(change.path()),
            text(
                "before_after",
                "Before: %s\nAfter: %s",
                design.inherent.reform.minecraft.ChoiceComponents.value(
                    change.beforeChoiceCaption(), Component.literal(change.beforeJson())),
                design.inherent.reform.minecraft.ChoiceComponents.value(
                    change.afterChoiceCaption(), Component.literal(change.afterJson()))));
    }
    if (!review.importedValidation().valid() && review.stageable())
      detail.row(
          "detail:repairable",
          text("repairable", "Repairable draft"),
          text(
              "repairable_help",
              "This format permits retaining invalid fields. Stage opens them in the editor for repair; Save still requires complete validation."));
    if (!currentReview())
      detail.row(
          "detail:stale",
          text("stale", "The target draft changed"),
          text(
              "preview_again",
              "Preview again before replacing it. Earlier work remains in the editor."));
  }

  private void diagnostics(String key, Component label, ValidationReport report) {
    if (report.valid()) return;
    detail.section("detail:" + key + "-diagnostics", label);
    if (!report.complete())
      detail.row(
          "detail:" + key + ":incomplete",
          text("incomplete", "Validation incomplete"),
          text("not_stageable", "A complete review is required before staging."));
    int index = 0;
    for (var issue : report.issues())
      detail.row(
          "detail:" + key + ":issue:" + index++,
          fieldLabel(issue.path()),
          Component.literal(issue.fallback()));
  }

  /** A native file drop explicitly admits one local path; no server path is resolved. */
  @Override
  public void onFilesDrop(List<Path> paths) {
    if (!ready() || operation != null || picker || !currentHost()) return;
    if (paths.size() != 1) {
      notice = text("one_file", "Choose or drop exactly one file.");
      rebuildWidgets();
      return;
    }
    try {
      int limit = formats.stream().mapToInt(ImportFormat::maximumBytes).max().orElseThrow();
      selectSource(
          new FileImportSource(
              DocumentId.parse("reform:selected_local_file"), paths.getFirst(), limit));
    } catch (RuntimeException refused) {
      discardReview();
      source = null;
      sourceLabel = null;
      notice = failure(refused);
      rebuildWidgets();
    }
  }

  /** Registered sources use the same workflow without an arbitrary server filesystem command. */
  public void selectSource(ImportSource selected) {
    if (!ready() || operation != null || picker || !currentHost()) return;
    var epoch = new Object();
    selectionEpoch = epoch;
    discardReview();
    source = null;
    sourceLabel = null;
    DocumentImports<T> requested = null;
    boolean adopted = false;
    try {
      requested =
          actions.imports(
              selected,
              formats,
              catalog.map(ImportCatalog::limits).orElse(DocumentImports.Limits.STANDARD));
      var label = Component.literal(selected.label());
      if (closed || epoch != selectionEpoch) {
        requested.close();
        return;
      }
      imports = requested;
      adopted = true;
      source = selected;
      sourceLabel = label;
      notice = null;
    } catch (RuntimeException refused) {
      if (closed || epoch != selectionEpoch) return;
      notice = failure(refused);
    } finally {
      if (!adopted && requested != null) requested.close();
    }
    wasCurrent = true;
    rebuildWidgets();
  }

  private void chooseFile() {
    if (picker || operation != null || !ready()) return;
    if (!FILE_DIALOG.compareAndSet(false, true)) {
      notice = text("dialog_busy", "A re:form file dialog is already open.");
      rebuildWidgets();
      return;
    }
    picker = true;
    var epoch = new Object();
    selectionEpoch = epoch;
    var request = new FileDialogRequest();
    dialogRequest = request;
    String dialogTitle = text("choose_title", "Import settings").getString();
    notice =
        text(
            "dialog_open",
            "Choose a file in the native dialog. Cancel leaves this review unchanged.");
    rebuildWidgets();
    try {
      NativeFileDialogs.select(dialogTitle, request::enter)
          .whenComplete(
              (chosen, failed) -> {
                FILE_DIALOG.set(false);
                minecraft.execute(
                    () -> {
                      if (closed || epoch != selectionEpoch || !currentHost()) return;
                      picker = false;
                      if (failed != null) {
                        notice = failure(failed);
                        rebuildWidgets();
                      } else if (chosen != null) {
                        try {
                          onFilesDrop(List.of(Path.of(chosen)));
                        } catch (RuntimeException invalid) {
                          notice = failure(invalid);
                          rebuildWidgets();
                        }
                      } else {
                        notice = null;
                        rebuildWidgets();
                      }
                    });
              });
    } catch (RuntimeException rejected) {
      FILE_DIALOG.set(false);
      request.abandonQueued();
      picker = false;
      notice = failure(rejected);
      rebuildWidgets();
    }
  }

  private void preview() {
    if (!ready() || imports == null || operation != null || picker) return;
    review = null;
    notice = null;
    staging = false;
    try {
      operation = imports.preview(EntryPoint.GUI, format.id());
      previewWantsFocus = true;
    } catch (RuntimeException refused) {
      previewWantsFocus = false;
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  private void stage() {
    if (operation != null || picker || !currentReview() || !review.stageable()) return;
    try {
      operation = imports.stage(EntryPoint.GUI, review);
      staging = true;
      notice = null;
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  private void cancel() {
    if (operation == null || staging) return;
    try {
      operation.requestCancellation();
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    rebuildWidgets();
  }

  @Override
  public void tick() {
    if (closed || !currentHost()) return;
    boolean nextReadable = mayRead();
    if (readable != nextReadable) {
      readable = nextReadable;
      rebuildWidgets();
    }
    if (operation != null) {
      var result = operation.snapshot();
      if (result.terminal()) {
        operation = null;
        if (staging) {
          staging = false;
          if (result.result().isPresent()) {
            onClose();
            return;
          }
          review = null;
        } else if (result.result().orElse(null) instanceof DocumentImports.Preview preview)
          review = preview;
        notice = result.result().isPresent() ? null : operationFailure(result);
        wasCurrent = currentReview();
        focusReviewOnMount = review != null && readable && previewWantsFocus;
        previewWantsFocus = false;
        rebuildWidgets();
      }
    }
    boolean current = review == null || currentReview();
    if (wasCurrent && !current) {
      notice = text("stale", "The target draft changed");
      rebuildWidgets();
    }
    wasCurrent = current;
  }

  private Button action(Component label, Runnable callback) {
    var generation = widgetGeneration;
    return theme()
        .buttonBuilder(
            label,
            ignored -> {
              if (!closed && widgetGeneration == generation && currentHost()) callback.run();
            })
        .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
        .build();
  }

  private Button button(Component label, Runnable callback) {
    var result = action(label, callback);
    footer.add(result);
    return result;
  }

  @Override
  protected void setInitialFocus() {
    var region = retained.focused() == null ? retained.viewport() : retained.focused();
    var target = region == TaskPanels.Region.DETAIL ? detail : panel;
    setFocused(target);
    target.focusVisibleControl();
  }

  private void focusReview() {
    detail.setFocused((net.minecraft.client.gui.components.events.GuiEventListener) null);
    detail.jump(
        review.importedValidation().valid() ? "detail:reviewed" : "detail:imported-diagnostics");
    setFocused(detail);
  }

  @Override
  protected void rebuildWidgets() {
    preserve();
    super.rebuildWidgets();
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(
        graphics,
        operation == null
            ? notice == null
                ? text("footer", "Stage changes the draft only; Back retains earlier work.")
                : notice
            : progress(operation.snapshot()));
  }

  private Component progress(OperationSnapshot<?> state) {
    if (state.cancellationRequested())
      return text("draining", "Cancellation requested; waiting for the current work to stop.");
    if (state.state() == OperationSnapshot.State.QUEUED) return text("queued", "Queued for review");
    if (staging) return text("staging", "Staging the reviewed draft");
    return switch (state.progress().phase()) {
      case "import.read" -> text("reading", "Reading the admitted source");
      case "import.compare" -> text("comparing", "Validating and comparing the draft");
      default -> text("preparing", "Preparing the import review");
    };
  }

  @Override
  public void onClose() {
    if (closed || staging || !currentHost()) return;
    returning = true;
    if (composed) hide();
    else release();
    returned.run();
  }

  @Override
  public void removed() {
    if (returning) return;
    release();
    abandoned.run();
  }

  private void discardReview() {
    if (imports != null) imports.close();
    imports = null;
    review = null;
  }

  private void release() {
    closed = true;
    selectionEpoch = new Object();
    if (dialogRequest != null) dialogRequest.abandonQueued();
    discardReview();
  }

  private static Component operationFailure(OperationSnapshot<?> result) {
    if (result.report().issues().isEmpty())
      return text("cancelled", "Import review ended without staging. Earlier work is retained.");
    return Component.literal(result.report().issues().getFirst().fallback());
  }

  private static Component failure(Throwable failed) {
    if (failed instanceof PolicyRefusal refusal && !refusal.report().issues().isEmpty())
      return Component.literal(refusal.report().issues().getFirst().fallback());
    return text("unavailable", "Import is unavailable. Your draft is retained.");
  }
}
