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
public final class ImportScreen<T> extends NativeFormScreen {
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
  private FormPanel panel;
  private FormChrome chrome;
  private Component sourceLabel, notice;
  private Object widgetGeneration, selectionEpoch;
  private FileDialogRequest dialogRequest;
  private boolean picker, staging, closed, returning, wasCurrent = true;

  public ImportScreen(
      Component title,
      Component contextLabel,
      DocumentActions<T> actions,
      List<? extends ImportFormat<T>> formats,
      BooleanSupplier available,
      Runnable returned,
      Runnable abandoned) {
    this(title, contextLabel, actions, formats, available, returned, abandoned, Optional.empty());
  }

  public ImportScreen(
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

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.import." + key, fallback, arguments);
  }

  /** Cached result currently shown by the native view; no parsing, validation or owner effect. */
  public Optional<DocumentImports.Preview> reviewedInput() {
    return closed ? Optional.empty() : Optional.ofNullable(review);
  }

  private boolean ready() {
    var view = actions.view();
    return !closed
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
    widgetGeneration = new Object();
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    footer.clear();
    var preview = button(text("preview", "Preview import"), this::preview);
    preview.active = ready() && imports != null && operation == null && !picker;
    var stage = button(text("stage", "Stage draft"), this::stage);
    stage.active = operation == null && !picker && currentReview() && review.stageable();
    var cancel = button(text("cancel", "Cancel operation"), this::cancel);
    cancel.active = operation != null && !staging && !operation.snapshot().cancellationRequested();
    var back = button(text("back", "Back"), this::onClose);
    back.active = !staging;
    chrome =
        new FormChrome(
            minecraft,
            width,
            height,
            title,
            Component.empty(),
            operation == null
                ? notice == null
                    ? text("footer", "Stage changes the draft only; Back retains earlier work.")
                    : notice
                : progress(operation.snapshot()),
            footer);
    panel = chrome.panel();
    chrome.mountActions(this::addRenderableWidget, panel);
    panel.row(
        "purpose",
        text("draft_only", "Import into this draft"),
        text(
            "help",
            "Review before replacement. One Undo restores earlier work. This does not save, publish resources or apply a world recipe."));
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
                            if (NativeScreens.current(minecraft) == this
                                && ready()
                                && operation == null
                                && !picker)
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
                      && NativeScreens.current(minecraft) == this
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
    addRenderableWidget(panel);
    panel.finish(scroll);
    panel.restoreFocus(focus);
    if (focus != null) setFocused(panel);
  }

  private void showReview() {
    panel.section("reviewed", text("review", "Reviewed input"));
    panel.row(
        "identity",
        Component.literal(review.source().label()),
        text(
            "identity",
            "%s bytes · SHA-256 %s",
            review.source().bytes(),
            review.source().sha256()));
    panel.row(
        "version",
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
      panel.row(
          "unavailable_compare",
          text("compare_unavailable", "Typed comparison unavailable"),
          text(
              "compare_help",
              "Invalid data is retained with diagnostics. It is not an empty successful comparison."));
    else if (review.changes().isEmpty())
      panel.row(
          "no_changes",
          text("equivalent", "No typed value changes"),
          text(
              review.inputChanges() ? "input_changed" : "unchanged",
              review.inputChanges()
                  ? "Authored or inactive editor input will still be replaced."
                  : "The imported input matches this draft."));
    else {
      panel.heading(text("changes", "Typed changes (%s)", review.changes().size()));
      int index = 0;
      for (var change : review.changes())
        panel.row(
            "change:" + index++,
            FieldLabels.input(
                actions.definition().id(), change.path(), text("document", "Document value")),
            text("before_after", "Before: %s\nAfter: %s", change.beforeJson(), change.afterJson()));
    }
    if (!review.importedValidation().valid() && review.stageable())
      panel.row(
          "repairable",
          text("repairable", "Repairable draft"),
          text(
              "repairable_help",
              "This format permits retaining invalid fields. Stage opens them in the editor for repair; Save still requires complete validation."));
    if (!currentReview())
      panel.row(
          "stale",
          text("stale", "The target draft changed"),
          text(
              "preview_again",
              "Preview again before replacing it. Earlier work remains in the editor."));
  }

  private void diagnostics(String key, Component label, ValidationReport report) {
    if (report.valid()) return;
    panel.section(key + "-diagnostics", label);
    if (!report.complete())
      panel.row(
          key + ":incomplete",
          text("incomplete", "Validation incomplete"),
          text("not_stageable", "A complete review is required before staging."));
    int index = 0;
    for (var issue : report.issues())
      panel.row(
          key + ":issue:" + index++,
          FieldLabels.input(
              actions.definition().id(), issue.path(), text("document", "Document value")),
          Component.literal(issue.fallback()));
  }

  /** A native file drop explicitly admits one local path; no server path is resolved. */
  @Override
  public void onFilesDrop(List<Path> paths) {
    if (!ready() || operation != null || picker || NativeScreens.current(minecraft) != this) return;
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
    if (!ready() || operation != null || picker || NativeScreens.current(minecraft) != this) return;
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
    String dialogTitle = text("choose_title", "Import profile").getString();
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
                      if (closed
                          || epoch != selectionEpoch
                          || NativeScreens.current(minecraft) != this) return;
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
    } catch (RuntimeException refused) {
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
    if (closed || NativeScreens.current(minecraft) != this) return;
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
        rebuildWidgets();
        if (review != null) focusReview();
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
    return Button.builder(
            label,
            ignored -> {
              if (!closed
                  && widgetGeneration == generation
                  && NativeScreens.current(minecraft) == this) callback.run();
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
    if (review != null) focusReview();
    else {
      setFocused(panel);
      panel.focusVisibleControl();
    }
  }

  private void focusReview() {
    panel.setFocused((net.minecraft.client.gui.components.events.GuiEventListener) null);
    panel.jump(review.importedValidation().valid() ? "reviewed" : "imported-diagnostics");
    setFocused(panel);
  }

  @Override
  protected void rebuildWidgets() {
    var bookmark = panel == null ? null : panel.bookmark();
    String focus = panel == null ? null : panel.focusKey();
    super.rebuildWidgets();
    if (bookmark != null) panel.restore(bookmark);
    panel.restoreFocus(focus);
    if (focus != null) setFocused(panel);
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
    if (closed || staging || NativeScreens.current(minecraft) != this) return;
    returning = true;
    release();
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
