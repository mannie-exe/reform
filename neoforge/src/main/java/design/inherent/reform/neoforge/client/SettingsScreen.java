package design.inherent.reform.neoforge.client;

import static design.inherent.reform.neoforge.client.FieldLabels.readable;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.JsonImportFormat;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormDependencies;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormModel;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.NumericInput;
import design.inherent.reform.reference.CollectionSelection;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceSelection;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

/** Generated recursive native form over the same admitted owner/operation services. */
public final class SettingsScreen<T> extends FormScreen {
  private record Control(
      AbstractWidget widget,
      Consumer<String> display,
      boolean editable,
      ValueShape shape,
      boolean cycle,
      DraftTarget target,
      Object token,
      Quantity quantity) {}

  private static final class Quantity {
    NumericInput input;
    CycleButton<String> units;
    Button less, more;
    QuantitySlider slider;
    boolean deferred;

    Quantity(NumericInput input) {
      this.input = input;
    }
  }

  private static final class QuantitySlider extends AbstractSliderButton {
    private final Component label;
    private final DoubleConsumer edits;

    QuantitySlider(Component label, DoubleConsumer edits) {
      super(0, 0, 160, 20, label, 0);
      this.label = label;
      this.edits = edits;
    }

    void display(NumericInput input, Component unit) {
      input.position().ifPresent(position -> value = position);
      setMessage(Component.literal(input.text()).append(" ").append(unit));
    }

    @Override
    protected void applyValue() {
      edits.accept(value);
    }

    @Override
    protected void updateMessage() {
      /* The mapped display supplies the selected value. */
    }

    @Override
    protected MutableComponent createNarrationMessage() {
      return Component.translatable(
          "gui.narrate.slider", label.copy().append(": ").append(getMessage()));
    }
  }

  private final Screen parent;
  private final Component contextLabel;
  private final DocumentDefinition<T> definition;
  private final DocumentActions<T> actions;
  private final Map<DocumentId, ReferenceActions<String, ?>> sources;
  private final FormPresentation presentation;
  private final FieldEditors<T> fieldEditors;
  private final CatalogPane catalog;

  private record InputPosition(int cursor, int selection, Optional<String> unit) {}

  private final Map<FieldPath, InputPosition> inputPositions = new LinkedHashMap<>();
  private final java.util.Set<FieldPath> expandedEditors = new java.util.HashSet<>();
  private final Map<FieldPath, Button> editorButtons = new LinkedHashMap<>();
  private final Map<FieldPath, Button> disclosureButtons = new LinkedHashMap<>();
  private final FormDependencies.Bound dependencies;
  private final Map<FieldPath, Control> controls = new LinkedHashMap<>();
  private final Map<FieldPath, String> local = new LinkedHashMap<>();
  private final Map<FieldPath, Long> versions = new LinkedHashMap<>();
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final List<FieldPath> visibleFields = new ArrayList<>();
  private final Map<AbstractWidget, Boolean> structureButtons = new LinkedHashMap<>();
  private final Map<AbstractWidget, FieldPath> structurePaths = new LinkedHashMap<>();
  private final Map<FieldPath, Button> availabilityButtons = new LinkedHashMap<>();
  private final Map<FieldPath, String> availabilityRows = new LinkedHashMap<>();
  private EditorActor.View<T> dependencyView;
  private long dependencyVersion = -1;
  private FormDependencies.Evaluation dependencyEvaluation;
  private List<FormModel.Layout> formLayout = List.of();
  private UUID formSession;
  private long formGeneration;
  private FormPanel panel;
  private EditorActor.View<T> displayed;
  private Button save, undo, redo, revert, defaults, reload, importData, conflict, done;
  private Object widgetGeneration;
  private int bodyTop, statusTop, pendingEdits;
  private long version;
  private boolean requested, updating, saving, loading, handoff, closed, formUnavailable;
  private Component notice;
  private DraftTree modelDraft;
  private FormModel cachedModel;
  private IllegalArgumentException modelFailure;
  private Language displayedLanguage;
  private boolean bodyVisible = true;

  /** No implicit initialization, actor lookup or settings ownership is inferred by the screen. */
  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions) {
    this(parent, title, contextLabel, definition, actions, Map.of());
  }

  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources) {
    this(parent, title, contextLabel, definition, actions, sources, FormPresentation.EMPTY);
  }

  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation) {
    this(
        parent,
        title,
        contextLabel,
        definition,
        actions,
        sources,
        presentation,
        FieldEditors.builder(definition).build());
  }

  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions,
      Map<DocumentId, ? extends ReferenceActions<String, ?>> sources,
      FormPresentation presentation,
      FieldEditors<T> fieldEditors) {
    super(title);
    this.parent = parent;
    this.contextLabel = Objects.requireNonNull(contextLabel);
    this.definition = Objects.requireNonNull(definition);
    this.actions = Objects.requireNonNull(actions);
    var admitted = new LinkedHashMap<DocumentId, ReferenceActions<String, ?>>();
    sources.forEach(
        (id, source) -> {
          if (!id.equals(source.source()))
            throw new IllegalArgumentException(
                "Reference binding must retain its actual source ID");
          admitted.put(id, source);
        });
    this.sources = Map.copyOf(admitted);
    this.presentation = Objects.requireNonNull(presentation);
    this.fieldEditors = Objects.requireNonNull(fieldEditors);
    fieldEditors.requireCompatible(definition);
    dependencies = presentation.bind(definition.type());
    if (definition != actions.definition())
      throw new IllegalArgumentException("A form requires its actual owner definition");
    catalog =
        presentation.catalog().enabled()
            ? new CatalogPane(
                presentation.catalog(),
                definition.id(),
                new CatalogPane.Host() {
                  public boolean active() {
                    return !closed && minecraft != null && minecraft.screen == SettingsScreen.this;
                  }

                  public boolean change(Runnable navigation) {
                    return changeCatalog(navigation);
                  }

                  public void add(FormPanel panel) {
                    SettingsScreen.this.addRenderableWidget(panel);
                  }

                  public void add(AbstractWidget widget) {
                    SettingsScreen.this.addRenderableWidget(widget);
                  }

                  public void remove(GuiEventListener widget) {
                    SettingsScreen.this.removeWidget(widget);
                  }

                  public GuiEventListener focused() {
                    return SettingsScreen.this.getFocused();
                  }

                  public void focus(GuiEventListener widget) {
                    SettingsScreen.this.setFocused(widget);
                  }
                })
            : null;
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  private boolean contextRecipe() {
    return actions.commitKind() == DocumentSession.CommitKind.CONTEXT;
  }

  private Component commitLabel() {
    return contextRecipe()
        ? text("admit_recipe", "Apply recipe")
        : text("save_apply", "Save and apply");
  }

  private <O> CompletionStage<O> action(Supplier<OperationHandle<O>> operation) {
    try {
      return DocumentActions.result(operation.get());
    } catch (RuntimeException refused) {
      return java.util.concurrent.CompletableFuture.<O>failedFuture(refused)
          .minimalCompletionStage();
    }
  }

  /** Cached read-only editing state for composed/native views; no validation or owner effect. */
  public EditorActor.View<T> editorView() {
    return actions.view();
  }

  /** Current native control only; the caller gains no owner or persistence authority. */
  public Optional<AbstractWidget> fieldControl(FieldPath path) {
    var scalar = controls.get(path);
    return scalar != null
        ? Optional.of(scalar.widget())
        : Optional.ofNullable(editorButtons.get(path));
  }

  /** Cached catalog selection; this performs no owner operation or draft mutation. */
  public Optional<FieldPath> selectedSection() {
    return catalog == null ? Optional.empty() : catalog.selectedPath();
  }

  /** Explicit composed-view navigation through the current native form's existing draft. */
  public boolean selectSection(FieldPath path) {
    return catalog != null && catalog.contains(path) && changeCatalog(() -> catalog.select(path));
  }

  private boolean changeCatalog(Runnable navigation) {
    if (closed || minecraft == null || minecraft.screen != this) return false;
    if (saving || loading || pendingEdits > 0 || unsent()) {
      notice = text("catalog_wait", "Finish pending input before changing sections.");
      return false;
    }
    navigation.run();
    rebuildWidgets();
    return true;
  }

  /** Focus and reveal a currently rendered field, including related diagnostic destinations. */
  public boolean focusField(FieldPath path) {
    if (fieldControl(path).isEmpty() && catalog != null) {
      var section = catalog.nearest(path);
      if (section.isEmpty() || !selectSection(section.orElseThrow().path())) return false;
    }
    var control = controls.get(path);
    var widget = control == null ? editorButtons.get(path) : control.widget();
    if (widget == null || !widget.active) {
      widget = availabilityButtons.get(path);
      if (widget == null)
        widget =
            structurePaths.entrySet().stream()
                .filter(entry -> entry.getValue().equals(path))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
    if (panel == null || !bodyVisible) return false;
    if (widget == null) {
      if (catalog == null || !catalog.selectedPath().filter(path::equals).isPresent()) return false;
      setFocused(panel);
      panel.focusVisibleControl();
      return panel.getFocused() != null;
    }
    String focus =
        panel.fieldFocus(FormModel.key(path) + (control == null ? ":editor" : ""), widget);
    if (focus == null) return false;
    setFocused(panel);
    panel.restoreFocus(focus);
    return true;
  }

  @Override
  protected void init() {
    if (catalog != null) catalog.remember(panel);
    if (catalog != null) {
      controls.forEach(
          (path, control) -> {
            if (control.widget() instanceof FormEditBox input) {
              inputPositions.remove(path);
              inputPositions.put(
                  path,
                  new InputPosition(
                      input.getCursorPosition(),
                      input.selection(),
                      Optional.ofNullable(control.quantity()).map(value -> value.input.unit())));
            }
          });
      int maximum =
          actions.view().snapshot().map(value -> value.draft().limits().maximumNodes()).orElse(1);
      while (inputPositions.size() > maximum)
        inputPositions.remove(inputPositions.keySet().iterator().next());
    }
    displayedLanguage = Language.getInstance();
    widgetGeneration = new Object();
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    footer.clear();
    structureButtons.clear();
    structurePaths.clear();
    availabilityButtons.clear();
    availabilityRows.clear();
    editorButtons.clear();
    disclosureButtons.clear();
    var current = actions.view();
    bodyTop = 40 + font.split(contextLabel, Math.max(1, width - 24)).size() * font.lineHeight;
    save = button(commitLabel(), this::requestSave);
    undo = button(text("undo", "Undo"), () -> requestHistory(false));
    redo = button(text("redo", "Redo"), () -> requestHistory(true));
    revert =
        button(
            text("revert", "Revert"),
            () -> perform(action(() -> actions.revert(EntryPoint.GUI)), true));
    defaults =
        button(
            text("defaults", "Defaults"),
            () -> perform(action(() -> actions.defaults(EntryPoint.GUI)), true));
    reload =
        button(
            contextRecipe()
                ? text("reload_context", "Reload context")
                : text("reload", "Reload saved"),
            this::requestReload);
    importData = button(text("import", "Import…"), this::openImport);
    conflict =
        current.review().isPresent()
            ? button(text("conflict", "Review conflict…"), this::openConflict)
            : null;
    done = button(text("done", "Done"), this::onClose);
    var geometry =
        FormGeometry.controls(
            footer.stream().map(widget -> new FormGeometry.Size(widget.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int footerTop = height - 12 - geometry.height();
    statusTop = footerTop - 24;
    for (int i = 0; i < footer.size(); i++) {
      var position = geometry.placements().get(i);
      var widget = footer.get(i);
      widget.setPosition(12 + position.x(), footerTop + position.y());
      widget.setWidth(position.width());
      addRenderableWidget(widget);
    }
    visibleFields.clear();
    formLayout = List.of();
    FormModel model = null;
    Component modelError = null;
    if (current.snapshot().isPresent()) {
      var snapshot = current.snapshot().orElseThrow();
      if (catalog != null
          && (!snapshot.session().equals(formSession) || snapshot.generation() != formGeneration))
        inputPositions.clear();
      formSession = snapshot.session();
      formGeneration = snapshot.generation();
      try {
        model = model(snapshot.draft());
        formLayout = model.layout();
        formUnavailable = false;
      } catch (IllegalArgumentException unavailable) {
        formUnavailable = true;
        notice = failure(unavailable);
        modelError = notice;
      }
    }
    var layout =
        new CatalogPane.Layout(
            12,
            bodyTop,
            Math.max(1, width - 24),
            Math.max(1, statusTop - bodyTop - 8),
            false,
            true);
    if (catalog != null) {
      catalog.project(
          model, current.snapshot().map(value -> value.session()).orElse(null), formGeneration);
      layout = catalog.init(minecraft, 12, bodyTop, Math.max(1, width - 24), statusTop - 8);
    }
    bodyVisible = !layout.listOnly();
    panel =
        new FormPanel(minecraft, layout.x(), layout.y(), layout.width(), layout.height(), false);
    if (modelError != null) panel.row(text("form_unavailable", "Form unavailable"), modelError);
    if (model != null && bodyVisible) {
      expandedEditors.retainAll(
          formLayout.stream()
              .map(FormModel.Layout::path)
              .collect(java.util.stream.Collectors.toSet()));
      if (catalog == null || layout.allFields()) fields(model.root(), title);
      else {
        catalog.help().ifPresent(help -> panel.row(Component.empty(), help));
        var selected = catalog.selected();
        if (selected.isPresent() && selected.orElseThrow().node().isPresent()) {
          var node = selected.orElseThrow().node().orElseThrow();
          var label = catalog.caption(node.path());
          if (node.role() == FormModel.Role.ROW || node.role() == FormModel.Role.MAP_ROW) {
            var parent = catalog.parent().orElseThrow();
            int index = parent.children().indexOf(node);
            rowActions(parent, node, index, label);
          }
          fields(node, label);
        } else panel.row(text("catalog_section", "Section unavailable"), catalog.unavailable());
      }
    }
    controls.keySet().retainAll(visibleFields);
    local.keySet().retainAll(visibleFields);
    versions.keySet().retainAll(visibleFields);
    if (bodyVisible) addRenderableWidget(panel);
    panel.finish(catalog == null ? scroll : 0);
    if (catalog == null) {
      panel.restoreFocus(focus);
      if (focus != null) setFocused(panel);
    } else if (bodyVisible) catalog.restore(panel);
    displayed = current;
    feedback();
    if (!requested) {
      requested = true;
      if (current.availability() == EditorActor.Availability.LOADING) requestReload();
    }
  }

  private FormModel model(DraftTree draft) {
    if (draft != modelDraft) {
      modelDraft = draft;
      cachedModel = null;
      modelFailure = null;
      try {
        cachedModel = new FormModel(definition.type(), draft);
      } catch (IllegalArgumentException failed) {
        modelFailure = failed;
      }
    }
    if (modelFailure != null) throw modelFailure;
    return cachedModel;
  }

  @Override
  protected void rebuildWidgets() {
    var page = catalog == null ? null : catalog.displayedDetail();
    var catalogFocus = catalog == null ? null : catalog.focus();
    boolean bodyFocused = getFocused() == panel;
    var bookmark = panel == null ? null : panel.bookmark();
    String focusedField = panel == null ? null : panel.focusKey();
    int focusedFooter = footer.indexOf(getFocused());
    super.rebuildWidgets();
    boolean samePage = catalog == null || Objects.equals(page, catalog.displayedDetail());
    if (bodyVisible && samePage && bookmark != null) panel.restore(bookmark);
    if (bodyVisible && samePage && focusedField != null && (catalog == null || bodyFocused)) {
      // Native rebuild clears the old focus tree before init. Restore the parent first.
      setFocused(panel);
      panel.restoreFocus(focusedField);
    } else if (bodyVisible && catalog != null) catalog.restore(panel);
    if (catalogFocus != null && catalogFocus.area() != CatalogPane.Area.OTHER)
      catalog.restore(catalogFocus);
    else if (focusedFooter >= 0 && focusedFooter < footer.size())
      setFocused(footer.get(focusedFooter));
  }

  @Override
  public void resize(net.minecraft.client.Minecraft client, int width, int height) {
    if (catalog != null) catalog.preserveMode(getFocused() == panel);
    super.resize(client, width, height);
  }

  @Override
  protected void setInitialFocus() {
    if (!bodyVisible && catalog != null && catalog.focusSearch()) return;
    if (panel != null
        && bodyVisible
        && (catalog != null || !controls.isEmpty() || !editorButtons.isEmpty())) {
      setFocused(panel);
      panel.focusVisibleControl();
      if (panel.getFocused() != null) return;
    }
    super.setInitialFocus();
  }

  private Button button(Component label, Runnable action) {
    var generation = widgetGeneration;
    var result =
        Button.builder(
                label,
                ignored -> {
                  if (!closed && minecraft.screen == this && widgetGeneration == generation)
                    action.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    footer.add(result);
    return result;
  }

  private void fields(FormModel.Node field, Component label) {
    if (catalog != null && catalog.boundary(field.path())) {
      catalogLink(field);
      return;
    }
    var path = field.path();
    var node = field.input();
    var shape = field.type().orElseThrow().shape();
    var editor = fieldEditors.at(path, field.type().orElseThrow());
    if (editor.isPresent()) {
      var generation = widgetGeneration;
      var change =
          Button.builder(
                  text("edit_field", "Edit…"),
                  ignored -> {
                    if (widgetGeneration == generation)
                      openField(field, label, editor.orElseThrow());
                  })
              .bounds(0, 0, 80, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      var raw =
          Button.builder(
                  expandedEditors.contains(path)
                      ? text("hide_fields", "Hide fields")
                      : text("show_fields", "Show fields"),
                  ignored -> {
                    if (closed
                        || minecraft.screen != this
                        || widgetGeneration != generation
                        || saving
                        || loading
                        || pendingEdits > 0
                        || unsent()) return;
                    if (!expandedEditors.remove(path)) expandedEditors.add(path);
                    rebuildWidgets();
                  })
              .bounds(0, 0, 100, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      editorButtons.put(path, change);
      disclosureButtons.put(path, raw);
      panel.row(
          FormModel.key(path) + ":editor",
          label,
          text("editor_help", "Edit this field, or show its raw controls to repair input."),
          change,
          raw);
      if (!expandedEditors.contains(path)) return;
    }
    if (!(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>)
        && dependencies.affects(path)) {
      String key = FormModel.key(path) + ":availability";
      availabilityRows.put(path, key);
      panel.row(key, label, dependencyText(path), availabilityButton(path, label));
    }
    if (shape instanceof ValueShape.ObjectValue && node instanceof DraftNode.ObjectValue) {
      if (!path.equals(FieldPath.ROOT)) panel.heading(label);
      children(field);
      return;
    }
    if ((shape instanceof ValueShape.Sequence && node instanceof DraftNode.Sequence)
        || (shape instanceof ValueShape.Mapping && node instanceof DraftNode.Mapping)) {
      int size = field.children().size();
      int maximum =
          shape instanceof ValueShape.Sequence sequence
              ? sequence.maximumSize()
              : ((ValueShape.Mapping) shape).maximumSize();
      var add =
          structuralButton(
              path,
              text("add_entry", "Add entry"),
              label,
              new DraftEdit.InsertRow(Optional.empty()));
      add.setTooltip(Tooltip.create(text("add_entry_help", "Add a blank entry for editing")));
      structureButtons.put(add, size < maximum);
      var collectionControls = new ArrayList<AbstractWidget>();
      collectionControls.add(add);
      if (shape instanceof ValueShape.Sequence sequence) {
        var element = sequence.element().shape();
        if (element instanceof ValueShape.Reference<?> reference)
          element = reference.element().shape();
        if (element instanceof ValueShape.Scalar scalar
            && (sequence.element().shape() instanceof ValueShape.Reference<?>
                || scalar.kind() == ValueShape.ScalarKind.BOOLEAN
                || scalar.kind() == ValueShape.ScalarKind.CHOICE)) {
          var generation = widgetGeneration;
          var choose =
              Button.builder(
                      text("choose_values", "Choose values…"),
                      ignored -> {
                        if (widgetGeneration == generation) openCollection(path, label);
                      })
                  .bounds(0, 0, 120, 20)
                  .createNarration(original -> label.copy().append(": ").append(original.get()))
                  .build();
          structureButtons.put(choose, true);
          structurePaths.put(choose, path);
          collectionControls.add(choose);
        }
      }
      panel.row(
          FormModel.key(path) + ":collection",
          label,
          Component.translatableWithFallback(
              "reform.settings.entry_count", "%s entries (limit %s)", size, maximum),
          collectionControls.toArray(AbstractWidget[]::new));
      for (int i = 0; i < size; i++) {
        var child = field.children().get(i);
        var rowLabel =
            Component.translatableWithFallback("reform.settings.entry", "Entry %s", i + 1);
        if (catalog != null && catalog.boundary(child.path())) catalogLink(child);
        else {
          rowActions(field, child, i, label);
          fields(child, rowLabel);
        }
      }
      return;
    }
    if (shape instanceof ValueShape.Defaulted<?> && node instanceof DraftNode.Defaulted value) {
      panel.row(
          FormModel.key(path) + ":default",
          label,
          value.overridden()
              ? text("override_active", "Override active")
              : text("using_default", "Using default"),
          structuralButton(
              path,
              value.overridden()
                  ? text("use_default", "Use default")
                  : text("override", "Override"),
              label,
              new DraftEdit.Override(!value.overridden())));
      for (var retained : value.retainedOverrides())
        panel.row(
            FormModel.key(path) + ":retained:" + retained.id(),
            text("retained_override", "Retained input"),
            Component.literal(preview(retained.value())),
            structuralButton(
                path,
                text("recover_input", "Recover input"),
                label,
                new DraftEdit.RestoreOverride(retained.id())),
            structuralButton(
                path,
                text("discard_input", "Discard input"),
                label,
                new DraftEdit.DiscardOverride(retained.id())));
      for (var child : field.children()) fields(child, label);
      return;
    }
    if (shape instanceof ValueShape.OptionalValue<?>
        && node instanceof DraftNode.OptionalValue value) {
      panel.row(
          FormModel.key(path) + ":optional",
          label,
          value.present() ? Component.empty() : text("empty_value", "Empty"),
          structuralButton(
              path,
              value.present() ? text("use_empty", "Use empty") : text("add_value", "Add value"),
              label,
              new DraftEdit.Presence(!value.present())));
      for (var child : field.children()) fields(child, label);
      return;
    }
    if (shape instanceof ValueShape.Alternative alternatives
        && node instanceof DraftNode.Alternative value) {
      var choices =
          new ArrayList<>(alternatives.variants().stream().map(ValueShape.Variant::id).toList());
      if (!choices.contains(value.selected())) choices.addFirst(value.selected());
      var generation = widgetGeneration;
      var choice =
          CycleButton.<String>builder(
                  id ->
                      id.isEmpty()
                          ? text("choose_variant", "Choose a variant")
                          : Component.literal(readable(id)))
              .withValues(choices)
              .withInitialValue(value.selected())
              .displayOnlyValue()
              .create(
                  0,
                  0,
                  160,
                  20,
                  label,
                  (widget, id) -> {
                    if (widgetGeneration == generation)
                      structural(path, new DraftEdit.SelectVariant(id));
                  });
      structureButtons.put(choice, true);
      structurePaths.put(choice, path);
      panel.row(FormModel.key(path) + ":variant", label, Component.empty(), choice);
      for (var branch : value.branches().keySet())
        if (!branch.equals(value.selected()))
          panel.row(
              FormModel.key(path) + ":branch:" + branch,
              text("inactive_branch", "Inactive branch"),
              Component.literal(clip(branch, 120)),
              structuralButton(
                  path,
                  text("discard_branch", "Discard branch"),
                  label,
                  new DraftEdit.DiscardBranch(branch)));
      children(field);
      return;
    }
    if (!(shape instanceof ValueShape.Scalar || shape instanceof ValueShape.Reference<?>)) {
      panel.row(
          FormModel.key(path) + ":repair",
          label,
          text(
              "retained_malformed",
              "Input is retained; repair replaces it with a blank value and can be undone."),
          structuralButton(
              path, text("repair_value", "Repair value"), label, new DraftEdit.Repair()));
    }
    visibleFields.add(path);
    var control = controls.get(path);
    var raw = raw(node);
    boolean cycle = field.cycling();
    if (control == null
        || !control.shape().equals(shape)
        || control.cycle() != cycle
        || (control.quantity() != null)
            != (node instanceof DraftNode.Scalar
                && shape instanceof ValueShape.Scalar scalar
                && scalar.numericDisplay().isPresent())
        || !control.target().session().equals(formSession)) {
      local.put(path, raw);
      versions.put(path, 0L);
      control = createControl(shape, path, node, label, cycle);
      if (control.quantity() != null) local.put(path, control.quantity().input.text());
      controls.put(path, control);
    } else if (control.target().generation() != formGeneration) {
      control =
          new Control(
              control.widget(),
              control.display(),
              control.editable(),
              control.shape(),
              control.cycle(),
              actions.view().snapshot().orElseThrow().target(path).typing(),
              control.token(),
              control.quantity());
      controls.put(path, control);
    }
    if (shape instanceof ValueShape.Reference<?> reference) {
      var source = sources.get(reference.source());
      var generation = widgetGeneration;
      var browse =
          Button.builder(
                  source == null
                      ? text("source_unavailable", "Source unavailable")
                      : text("browse", "Browse…"),
                  ignored -> {
                    if (widgetGeneration == generation) openReference(path, label, source);
                  })
              .bounds(0, 0, 120, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      browse.setTooltip(Tooltip.create(Component.literal(reference.source().toString())));
      structureButtons.put(browse, source != null);
      structurePaths.put(browse, path);
      fieldRow(path, label, control.widget(), browse);
    } else if (control.quantity() != null) {
      var quantity = control.quantity();
      if (quantity.slider == null)
        fieldRow(path, label, control.widget(), quantity.less, quantity.more, quantity.units);
      else
        fieldRow(
            path,
            label,
            control.widget(),
            quantity.less,
            quantity.more,
            quantity.units,
            quantity.slider);
    } else fieldRow(path, label, control.widget());
  }

  private void rowActions(FormModel.Node field, FormModel.Node child, int i, Component label) {
    int size = field.children().size();
    var shape = field.type().orElseThrow().shape();
    int maximum =
        shape instanceof ValueShape.Sequence sequence
            ? sequence.maximumSize()
            : ((ValueShape.Mapping) shape).maximumSize();
    var path = field.path();
    var row = ((FieldPath.Row) child.path().segments().getLast()).id();
    var rowLabel =
        catalog == null
            ? Component.translatableWithFallback("reform.settings.entry", "Entry %s", i + 1)
            : catalog.caption(child.path());
    var rowContext = label.copy().append(": ").append(rowLabel);
    var up =
        structuralButton(
            path,
            text("move_up", "Up"),
            rowContext,
            new DraftEdit.MoveRow(
                row,
                i > 0
                    ? Optional.of(
                        ((FieldPath.Row) field.children().get(i - 1).path().segments().getLast())
                            .id())
                    : Optional.of(row)));
    var down =
        structuralButton(
            path,
            text("move_down", "Down"),
            rowContext,
            new DraftEdit.MoveRow(
                row,
                i + 2 < size
                    ? Optional.of(
                        ((FieldPath.Row) field.children().get(i + 2).path().segments().getLast())
                            .id())
                    : Optional.empty()));
    var duplicate =
        structuralButton(
            path,
            text("duplicate", "Copy"),
            rowContext,
            new DraftEdit.DuplicateRow(row, Optional.empty()));
    structureButtons.put(up, i > 0);
    structureButtons.put(down, i + 1 < size);
    structureButtons.put(duplicate, size < maximum);
    panel.row(
        child.key() + ":row",
        rowLabel,
        Component.empty(),
        up,
        down,
        duplicate,
        structuralButton(path, text("remove", "Remove"), rowContext, new DraftEdit.RemoveRow(row)));
  }

  private void catalogLink(FormModel.Node field) {
    var label = catalog.caption(field.path());
    var generation = widgetGeneration;
    var open =
        Button.builder(
                text("catalog_open", "Open…"),
                ignored -> {
                  if (widgetGeneration == generation) selectSection(field.path());
                })
            .bounds(0, 0, 80, 20)
            .createNarration(original -> label.copy().append(": ").append(original.get()))
            .build();
    panel.row(FormModel.key(field.path()) + ":catalog", label, Component.empty(), open);
  }

  private void fieldRow(FieldPath path, Component label, AbstractWidget... widgets) {
    var row = new ArrayList<>(List.of(widgets));
    if (dependencies.affects(path)) row.add(availabilityButton(path, label));
    panel.relatedField(
        FormModel.key(path),
        presentation.range(path).map(range -> "range:" + range.id()).orElse(null),
        label,
        Component.empty(),
        row.toArray(AbstractWidget[]::new));
  }

  private Button availabilityButton(FieldPath path, Component label) {
    var generation = widgetGeneration;
    var button =
        Button.builder(
                text("availability", "Availability"),
                ignored -> {
                  if (!closed && minecraft.screen == this && widgetGeneration == generation)
                    notice = dependencyText(path);
                })
            .bounds(0, 0, Math.max(80, font.width(text("availability", "Availability")) + 16), 20)
            .createNarration(original -> label.copy().append(": ").append(dependencyText(path)))
            .build();
    availabilityButtons.put(path, button);
    return button;
  }

  private FormDependencies.Decision dependency(FieldPath path) {
    if (!dependencies.affects(path)) return FormDependencies.Decision.AVAILABLE;
    var current = actions.view();
    if (current.availability() != EditorActor.Availability.READY || current.snapshot().isEmpty())
      return new FormDependencies.Decision(FormDependencies.State.UNKNOWN, List.of());
    if (dependencyView != current || dependencyVersion != version || dependencyEvaluation == null) {
      var draft = current.snapshot().orElseThrow().draft();
      var pending = new java.util.HashSet<FieldPath>();
      for (var input : dependencies.inputs()) {
        var control = controls.get(input);
        if (control != null && !matches(input, control, draft)) pending.add(input);
      }
      dependencyEvaluation = dependencies.evaluate(draft, current.inputs(), pending);
      dependencyView = current;
      dependencyVersion = version;
    }
    return dependencyEvaluation.at(path);
  }

  private boolean locallyAvailable(FieldPath path) {
    return dependency(path).state() == FormDependencies.State.AVAILABLE;
  }

  private Component dependencyText(FieldPath path) {
    var decision = dependency(path);
    MutableComponent result =
        switch (decision.state()) {
          case AVAILABLE -> text("available", "Available").copy();
          case DISABLED -> text("inactive", "Inactive").copy();
          case UNKNOWN -> text("availability_unknown", "Availability unknown").copy();
        };
    for (var reason : decision.reasons())
      result
          .append("\n")
          .append(Component.translatableWithFallback(reason.translationKey(), reason.fallback()))
          .append(" (")
          .append(inputLabel(reason.input()))
          .append(")");
    return result;
  }

  private Component inputLabel(FieldPath path) {
    return FieldLabels.input(definition.id(), path, title);
  }

  private <V> void openReference(
      FieldPath path, Component label, ReferenceActions<String, V> source) {
    if (closed
        || minecraft.screen != this
        || source == null
        || saving
        || loading
        || pendingEdits > 0
        || unsent()) return;
    if (!locallyAvailable(path)) return;
    Supplier<ReferenceSelection<T, V>> factory =
        () -> new ReferenceSelection<>(actions, path, source);
    final ReferenceSelection<T, V> selection;
    try {
      selection = factory.get();
    } catch (design.inherent.reform.owner.PolicyRefusal | IllegalArgumentException refused) {
      notice = failure(refused);
      return;
    }
    var screen =
        new ReferenceScreen<>(
            label,
            selection,
            source.limits(),
            factory,
            () -> {
              synchronize(Map.copyOf(versions));
              minecraft.setScreen(this);
            },
            actions::close);
    handoff = true;
    try {
      minecraft.setScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private void openCollection(FieldPath path, Component label) {
    if (closed
        || minecraft.screen != this
        || saving
        || loading
        || pendingEdits > 0
        || unsent()
        || !locallyAvailable(path)) return;
    try {
      var selection = new CollectionSelection<>(actions, path);
      var source = selection.view().referenceSource().map(sources::get).orElse(null);
      var screen =
          new CollectionScreen<>(
              label,
              selection,
              source,
              labelKey(path) + "/choices/",
              () -> locallyAvailable(path),
              () -> {
                synchronize(Map.copyOf(versions));
                minecraft.setScreen(this);
              },
              actions::close);
      handoff = true;
      try {
        minecraft.setScreen(screen);
      } finally {
        handoff = false;
      }
    } catch (design.inherent.reform.owner.PolicyRefusal | IllegalArgumentException refused) {
      notice = failure(refused);
    }
  }

  private void openImport() {
    var view = actions.view();
    if (closed
        || minecraft.screen != this
        || saving
        || loading
        || pendingEdits > 0
        || unsent()
        || view.availability() != EditorActor.Availability.READY
        || view.snapshot().isEmpty()
        || view.pending().isPresent()
        || view.review().isPresent()) return;
    var snapshot = view.snapshot().orElseThrow();
    var screen =
        new ImportScreen<>(
            text("import_title", "Import profile"),
            contextLabel,
            actions,
            JsonImportFormat.documentFormats(
                definition,
                snapshot.jsonLimits(),
                snapshot.draft().limits(),
                ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE),
            () -> !closed,
            () -> {
              synchronize(Map.copyOf(versions));
              minecraft.setScreen(this);
            },
            actions::close);
    handoff = true;
    try {
      minecraft.setScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private void openField(FormModel.Node field, Component label, FieldEditors.Editor<T> editor) {
    var view = actions.view();
    if (closed
        || minecraft.screen != this
        || saving
        || loading
        || pendingEdits > 0
        || unsent()
        || view.availability() != EditorActor.Availability.READY
        || view.snapshot().isEmpty()
        || view.pending().isPresent()
        || view.review().isPresent()
        || !locallyAvailable(field.path())) return;
    if (layoutChanged(view)) {
      notice = text("review_changed_form", "The form changed; review this control again.");
      rebuildWidgets();
      return;
    }
    var snapshot = view.snapshot().orElseThrow();
    try {
      var request =
          new FieldEditors.Request<>(
              label,
              contextLabel,
              actions,
              snapshot.target(field.path()),
              field.type().orElseThrow(),
              snapshot.draft(),
              () -> !closed && locallyAvailable(field.path()),
              () -> {
                synchronize(Map.copyOf(versions));
                minecraft.setScreen(this);
              },
              actions::close);
      var screen = Objects.requireNonNull(editor.open(request));
      if (screen == this) throw new IllegalArgumentException("Open a separate auxiliary screen");
      handoff = true;
      try {
        minecraft.setScreen(screen);
      } finally {
        handoff = false;
      }
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
  }

  private void children(FormModel.Node parent) {
    var grouped = new java.util.HashSet<FieldPath>();
    for (var child : parent.children()) {
      if (grouped.contains(child.path())) continue;
      var range = presentation.range(child.path());
      if (range.isPresent()) {
        var pair = range.orElseThrow();
        var minimum =
            parent.children().stream()
                .filter(node -> node.path().equals(pair.minimum()))
                .findFirst();
        var maximum =
            parent.children().stream()
                .filter(node -> node.path().equals(pair.maximum()))
                .findFirst();
        if (minimum.isPresent() && maximum.isPresent()) {
          panel.beginGroup(
              "range:" + pair.id(),
              Component.translatableWithFallback(pair.translationKey(), pair.label()));
          if (!pair.help().isBlank())
            panel.row(
                Component.empty(),
                Component.translatableWithFallback(pair.translationKey() + ".help", pair.help()));
          fields(minimum.orElseThrow(), label(minimum.orElseThrow()));
          fields(maximum.orElseThrow(), label(maximum.orElseThrow()));
          panel.endGroup();
          grouped.add(pair.minimum());
          grouped.add(pair.maximum());
          continue;
        }
      }
      if (child.role() == FormModel.Role.UNKNOWN) {
        var label =
            Component.translatableWithFallback(
                "reform.settings.unknown_field", "Unknown field: %s", clip(child.name(), 120));
        var retained = new EditBox(font, 0, 0, 160, 20, label);
        String raw = raw(child.input());
        retained.setMaxLength(Math.max(128, raw.length()));
        retained.setValue(raw);
        retained.setEditable(false);
        panel.row(
            child.key(),
            label,
            text("unknown_retained", "Retained until explicitly removed"),
            retained,
            structuralButton(
                parent.path(),
                text("remove", "Remove"),
                label,
                new DraftEdit.RemoveUnknown(child.name())));
      } else fields(child, label(child));
    }
  }

  private void openConflict() {
    if (closed
        || saving
        || loading
        || pendingEdits > 0
        || unsent()
        || actions.view().review().isEmpty()
        || actions.view().pending().isPresent()) return;
    final ConflictScreen<T> screen;
    try {
      screen =
          new ConflictScreen<>(
              text("conflict_title", "Review conflict"),
              contextLabel,
              actions,
              () -> !closed,
              result -> {
                result.ifPresent(
                    resolved ->
                        notice =
                            resolved.report().valid()
                                ? text(
                                    "conflict_resolved",
                                    "Choices resolved in this draft. Save or apply separately.")
                                : text(
                                        "conflict_needs_repair",
                                        "Choices resolved in this draft; validation needs attention.")
                                    .copy()
                                    .append(" ")
                                    .append(first(resolved.report())));
                synchronize(Map.copyOf(versions));
                minecraft.setScreen(this);
              },
              actions::close);
    } catch (RuntimeException refused) {
      notice = failure(refused);
      feedback();
      return;
    }
    handoff = true;
    try {
      minecraft.setScreen(screen);
    } finally {
      handoff = false;
    }
  }

  private Component label(FormModel.Node field) {
    return switch (field.role()) {
      case ROOT -> title;
      case FIELD ->
          Component.translatableWithFallback(labelKey(field.path()), readable(field.name()));
      case ROW, MAP_ROW ->
          Component.translatableWithFallback("reform.settings.entry", "Entry %s", field.name());
      case KEY -> text("entry_key", "Key");
      case VALUE -> text("entry_value", "Value");
      case VARIANT -> Component.literal(readable(field.name()));
      case UNKNOWN -> Component.literal(clip(field.name(), 120));
    };
  }

  private Button structuralButton(
      FieldPath path, Component label, Component context, DraftEdit.Action edit) {
    var generation = widgetGeneration;
    var button =
        Button.builder(
                label,
                ignored -> {
                  if (widgetGeneration == generation) structural(path, edit);
                })
            .bounds(0, 0, Math.max(40, font.width(label) + 16), 20)
            .createNarration(original -> context.copy().append(": ").append(original.get()))
            .build();
    structureButtons.put(button, true);
    structurePaths.put(button, path);
    return button;
  }

  private void structural(FieldPath path, DraftEdit.Action edit) {
    if (closed || minecraft.screen != this || !locallyAvailable(path)) return;
    var current = actions.view();
    if (pendingEdits > 0
        || saving
        || loading
        || unsent()
        || current.availability() != EditorActor.Availability.READY
        || current.snapshot().isEmpty()) return;
    if (layoutChanged(current)) {
      notice = text("review_changed_form", "The form changed; review this control again.");
      rebuildWidgets();
      return;
    }
    var before = Map.copyOf(versions);
    pendingEdits++;
    notice = null;
    action(
            () ->
                actions.edit(
                    EntryPoint.GUI,
                    new DraftEdit(current.snapshot().orElseThrow().target(path), edit)))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      pendingEdits--;
                      if (failed != null) notice = failure(failed);
                      else {
                        synchronize(before);
                        if (edit instanceof DraftEdit.RestoreOverride)
                          notice =
                              text(
                                  "recovered_input",
                                  "Input recovered. Enable Override to use it if the default is still active.");
                      }
                      if (minecraft.screen == this) {
                        if (layoutChanged(actions.view())) rebuildWidgets();
                        else feedback();
                      }
                    }));
    feedback();
  }

  private static String preview(DraftNode node) {
    String value = raw(node);
    return value.isEmpty()
        ? text("structured_input", "Structured input retained").getString()
        : clip(value, 160);
  }

  private static String clip(String text, int maximum) {
    return text.codePointCount(0, text.length()) <= maximum
        ? text
        : text.substring(0, text.offsetByCodePoints(0, maximum)) + "…";
  }

  private Control createControl(
      ValueShape declared, FieldPath path, DraftNode node, Component label, boolean cycle) {
    String raw = raw(node);
    var target = actions.view().snapshot().orElseThrow().target(path).typing();
    var token = new Object();
    ValueShape shape =
        declared instanceof ValueShape.Reference<?> reference
            ? reference.element().shape()
            : declared;
    if (shape instanceof ValueShape.Scalar scalar) {
      var choices =
          scalar.kind() == ValueShape.ScalarKind.BOOLEAN
              ? List.of("false", "true")
              : scalar.choices();
      if (cycle) {
        var button =
            CycleButton.<String>builder(Component::literal)
                .withValues(choices)
                .withInitialValue(raw)
                .displayOnlyValue()
                .create(0, 0, 160, 20, label, (widget, value) -> edit(path, token, value));
        return new Control(button, button::setValue, true, declared, true, target, token, null);
      }
      Quantity quantity =
          scalar.numericDisplay().isPresent() && node instanceof DraftNode.Scalar value
              ? quantity(scalar, value, path, label, token)
              : null;
      if (quantity != null) raw = quantity.input.text();
      int inputLimit =
          quantity == null
              ? scalar.maximumLength() > 0 ? scalar.maximumLength() : 128
              : quantity.input.display().limits().maximumCharacters();
      var input = new FormEditBox(font, 0, 0, 160, 20, label);
      input.setMaxLength(Math.max(raw.length(), inputLimit));
      input.setValue(raw);
      var position = inputPositions.get(path);
      if (position != null) {
        input.setCursorPosition(Math.min(position.cursor(), raw.length()));
        input.setHighlightPos(Math.min(position.selection(), raw.length()));
      }
      input.setResponder(value -> edit(path, token, value));
      return new Control(
          input,
          value -> {
            input.setMaxLength(
                Math.max(inputLimit, Math.max(input.getValue().length(), value.length())));
            input.setValue(value);
          },
          true,
          declared,
          false,
          target,
          token,
          quantity);
    }
    var unavailable = new FormEditBox(font, 0, 0, 160, 20, label);
    unavailable.setMaxLength(Math.max(128, raw.length()));
    unavailable.setValue(
        raw.isEmpty() ? text("unsupported", "Editor unavailable").getString() : raw);
    unavailable.setEditable(false);
    unavailable.setTooltip(
        Tooltip.create(
            text("unsupported_help", "This type needs a dedicated editor. Its data is retained.")));
    return new Control(unavailable, ignored -> {}, false, declared, false, target, token, null);
  }

  private Quantity quantity(
      ValueShape.Scalar shape,
      DraftNode.Scalar node,
      FieldPath path,
      Component label,
      Object token) {
    var quantity = new Quantity(NumericInput.open(shape, node));
    var mapping = quantity.input.display();
    var rememberedPosition = inputPositions.get(path);
    if (rememberedPosition != null
        && rememberedPosition.unit().isPresent()
        && mapping.units().stream()
            .anyMatch(unit -> unit.id().equals(rememberedPosition.unit().orElseThrow()))) {
      var selection = quantity.input.select(rememberedPosition.unit().orElseThrow());
      if (selection.accepted()) quantity.input = selection.input();
    }
    var ids = new ArrayList<>(mapping.units().stream().map(unit -> unit.id()).toList());
    if (!ids.contains(quantity.input.unit())) ids.add(quantity.input.unit());
    quantity.units =
        CycleButton.<String>builder(
                id ->
                    mapping.units().stream()
                        .filter(unit -> unit.id().equals(id))
                        .<Component>map(
                            unit ->
                                Component.translatableWithFallback(
                                    unit.translationKey(), unit.fallback()))
                        .findFirst()
                        .orElseGet(
                            () ->
                                text("unit_unavailable", "Unavailable unit")
                                    .copy()
                                    .append(": " + id)))
            .withValues(ids)
            .withInitialValue(quantity.input.unit())
            .displayOnlyValue()
            .create(
                0,
                0,
                112,
                20,
                label.copy().append(": ").append(text("unit", "Display unit")),
                (button, unit) -> changeQuantity(path, token, quantity.input.select(unit), false));
    quantity.less =
        Button.builder(
                Component.literal("-"),
                ignored -> changeQuantity(path, token, quantity.input.step(-1), true))
            .bounds(0, 0, 24, 20)
            .createNarration(
                original -> label.copy().append(": ").append(text("decrease", "Decrease")))
            .build();
    quantity.more =
        Button.builder(
                Component.literal("+"),
                ignored -> changeQuantity(path, token, quantity.input.step(1), true))
            .bounds(0, 0, 24, 20)
            .createNarration(
                original -> label.copy().append(": ").append(text("increase", "Increase")))
            .build();
    var hint =
        text("unit_help", "Display only; stored unit: ")
            .copy()
            .append(mapping.canonicalUnit())
            .append("; ")
            .append(text("step", "step"))
            .append(": " + mapping.step());
    mapping
        .referenceTicksPerSecond()
        .ifPresent(
            rate ->
                hint.append("; ")
                    .append(text("nominal_tick_rate", "nominal ticks per second"))
                    .append(": " + rate));
    quantity.units.setTooltip(Tooltip.create(hint));
    if (mapping.slider().isPresent()) {
      quantity.slider =
          new QuantitySlider(
              label,
              position -> changeQuantity(path, token, quantity.input.slide(position), true, true));
      quantity.slider.setTooltip(
          Tooltip.create(
              text(
                  "slider_step_help",
                  "Dragging selects the nearest declared step. Exact text entry is not rounded.")));
    }
    return quantity;
  }

  private void changeQuantity(
      FieldPath path, Object token, NumericInput.Change change, boolean stage) {
    changeQuantity(path, token, change, stage, false);
  }

  private void changeQuantity(
      FieldPath path, Object token, NumericInput.Change change, boolean stage, boolean continuous) {
    var control = controls.get(path);
    if (closed
        || minecraft.screen != this
        || control == null
        || control.token() != token
        || control.quantity() == null
        || !locallyAvailable(path)
        || saving
        || loading
        || !continuous && (pendingEdits > 0 || unsent())) return;
    var quantity = control.quantity();
    boolean changed =
        !quantity.input.expected().equals(change.input().expected())
            || !Objects.equals(local.get(path), change.input().text());
    updating = true;
    try {
      if (change.accepted()) {
        quantity.input = change.input();
        if (!quantity.input.text().equals(local.get(path)))
          control.display().accept(quantity.input.text());
        local.put(path, quantity.input.text());
      } else notice = Component.literal(change.reason());
      quantity.units.setValue(quantity.input.unit());
    } finally {
      updating = false;
    }
    if (change.accepted() && stage) {
      if (continuous && !changed) {
        feedback();
        return;
      }
      versions.put(path, ++version);
      if (continuous && pendingEdits > 0) {
        quantity.deferred = true;
        feedback();
      } else {
        quantity.deferred = false;
        submit(path, quantity.input.text());
      }
    } else feedback();
  }

  private void edit(FieldPath path, Object token, String value) {
    if (closed || minecraft.screen != this) return;
    var current = controls.get(path);
    if (current == null || current.token() != token) return;
    if (!locallyAvailable(path)) return;
    if (updating || value.equals(local.get(path))) return;
    if (current.quantity() != null) {
      current.quantity().input = current.quantity().input.edit(value);
      current.quantity().deferred = false;
    }
    local.put(path, value);
    versions.put(path, ++version);
    submit(path, value);
  }

  private void submit(FieldPath path, String value) {
    var control = controls.get(path);
    var target = control.target();
    DraftEdit.Action input =
        control.quantity() == null
            ? new DraftEdit.SetRaw(value)
            : control.quantity().input.action().orElseThrow();
    pendingEdits++;
    notice = null;
    action(() -> actions.edit(EntryPoint.GUI, new DraftEdit(target, input)))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      pendingEdits--;
                      if (failed != null) {
                        notice = failure(failed);
                        controls
                            .values()
                            .forEach(
                                item -> {
                                  if (item.quantity() != null) item.quantity().deferred = false;
                                });
                      }
                      if (minecraft.screen == this) {
                        feedback();
                        if (failed == null) flushContinuous();
                      }
                    }));
    feedback();
  }

  private void flushContinuous() {
    if (closed
        || pendingEdits > 0
        || actions.view().availability() != EditorActor.Availability.READY
        || actions.view().snapshot().isEmpty()) return;
    var draft = actions.view().snapshot().orElseThrow().draft();
    for (var entry : controls.entrySet()) {
      var quantity = entry.getValue().quantity();
      if (quantity != null && quantity.deferred && locallyAvailable(entry.getKey())) {
        quantity.deferred = false;
        if (!matches(entry.getKey(), entry.getValue(), draft)) {
          submit(entry.getKey(), local.get(entry.getKey()));
          return;
        }
      }
    }
  }

  private boolean unsent() {
    var snapshot = actions.view().snapshot();
    if (snapshot.isEmpty()) return false;
    return controls.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getValue().editable()
                    && !matches(entry.getKey(), entry.getValue(), snapshot.orElseThrow().draft()));
  }

  private boolean matches(FieldPath path, Control control, DraftTree draft) {
    return control.quantity() == null
        ? Objects.equals(local.get(path), raw(at(draft, path)))
        : control.quantity().input.text().equals(local.get(path))
            && control.quantity().input.matches(at(draft, path));
  }

  private void requestSave() {
    if (saving || loading || pendingEdits > 0 || actions.view().snapshot().isEmpty()) return;
    if (unsent()) {
      var draft = actions.view().snapshot().orElseThrow().draft();
      for (var entry : controls.entrySet())
        if (entry.getValue().editable() && !matches(entry.getKey(), entry.getValue(), draft))
          submit(entry.getKey(), local.get(entry.getKey()));
      return;
    }
    if (!actions.view().validation().valid()
        || actions.view().pending().isPresent()
        || actions.view().review().isPresent()) return;
    var before = Map.copyOf(versions);
    saving = true;
    notice = null;
    action(() -> actions.commit(EntryPoint.GUI))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      saving = false;
                      if (failed != null) notice = failure(failed);
                      else if (!result.editorReport().valid())
                        notice = first(result.editorReport());
                      else
                        result
                            .receipt()
                            .ifPresent(
                                receipt -> {
                                  notice = saved(receipt);
                                  if (receipt.acknowledgesBaseline()) synchronize(before);
                                });
                      if (minecraft.screen == this) {
                        if (layoutChanged(actions.view())) rebuildWidgets();
                        else feedback();
                      }
                    }));
  }

  private void perform(CompletionStage<EditorActor.View<T>> operation, boolean synchronize) {
    perform(operation, synchronize, false);
  }

  private void requestReload() {
    if (saving || loading || pendingEdits > 0) return;
    if (unsent()) {
      notice = text("reload_unsent", "Retry edits or discard them explicitly before reloading.");
      return;
    }
    loading = true;
    feedback();
    perform(action(() -> actions.load(EntryPoint.GUI)), true, true);
  }

  private void perform(
      CompletionStage<EditorActor.View<T>> operation, boolean synchronize, boolean finishesReload) {
    var before = Map.copyOf(versions);
    notice = null;
    operation.whenComplete(
        (result, failed) ->
            minecraft.execute(
                () -> {
                  if (failed != null) notice = failure(failed);
                  else if (synchronize) synchronize(before);
                  if (finishesReload) loading = false;
                  if (minecraft.screen == this) {
                    if (layoutChanged(actions.view())) rebuildWidgets();
                    else feedback();
                  }
                }));
  }

  private void synchronize(Map<FieldPath, Long> before) {
    var snapshot = actions.view().snapshot();
    if (snapshot.isEmpty()) return;
    updating = true;
    try {
      controls.forEach(
          (path, control) -> {
            if (control.editable() && Objects.equals(before.get(path), versions.get(path))) {
              var node = at(snapshot.orElseThrow().draft(), path);
              var raw = raw(node);
              if (control.quantity() != null && node instanceof DraftNode.Scalar scalar) {
                var quantity = control.quantity();
                quantity.input = quantity.input.synchronize(scalar);
                quantity.deferred = false;
                quantity.units.setValue(quantity.input.unit());
                raw = quantity.input.text();
              }
              if (!raw.equals(local.get(path))) control.display().accept(raw);
              local.put(path, raw);
            }
          });
    } finally {
      updating = false;
    }
  }

  private void feedback() {
    var current = actions.view();
    if (save == null) return;
    boolean unaccepted = unsent();
    boolean historyReady =
        current.availability() == EditorActor.Availability.READY
            && !saving
            && !loading
            && pendingEdits == 0
            && !unaccepted;
    undo.active = historyReady && current.canUndo();
    redo.active = historyReady && current.canRedo();
    importData.active = historyReady && current.pending().isEmpty() && current.review().isEmpty();
    if (conflict != null)
      conflict.active = historyReady && current.review().isPresent() && current.pending().isEmpty();
    editorButtons.forEach(
        (path, button) -> {
          button.active =
              historyReady
                  && current.snapshot().isPresent()
                  && current.pending().isEmpty()
                  && current.review().isEmpty()
                  && locallyAvailable(path);
          button.setTooltip(locallyAvailable(path) ? null : Tooltip.create(dependencyText(path)));
        });
    disclosureButtons.forEach(
        (path, button) -> {
          button.active = !saving && !loading && pendingEdits == 0 && !unaccepted;
          button.setTooltip(
              button.active
                  ? null
                  : Tooltip.create(
                      text(
                          "settle_raw_input",
                          "Finish or explicitly discard pending input before changing field visibility.")));
        });
    save.setMessage(unaccepted ? text("retry_edits", "Retry edits") : commitLabel());
    save.active =
        current.snapshot().isPresent()
            && current.availability() == EditorActor.Availability.READY
            && !formUnavailable
            && !saving
            && !loading
            && pendingEdits == 0
            && current.pending().isEmpty()
            && current.review().isEmpty()
            && (unaccepted || current.validation().valid());
    revert.active =
        defaults.active =
            current.snapshot().isPresent() && !saving && !loading && pendingEdits == 0;
    reload.active = done.active = !saving && !loading && pendingEdits == 0;
    structureButtons.forEach(
        (button, enabled) ->
            button.active =
                enabled
                    && locallyAvailable(structurePaths.get(button))
                    && current.availability() == EditorActor.Availability.READY
                    && !loading
                    && !saving
                    && pendingEdits == 0
                    && !unaccepted);
    controls.forEach(
        (path, control) -> {
          boolean availableHere = locallyAvailable(path);
          if (control.widget() instanceof EditBox input)
            input.setEditable(
                control.editable()
                    && availableHere
                    && !loading
                    && current.availability() == EditorActor.Availability.READY
                    && (control.quantity() == null
                        || control.quantity().input.display().units().stream()
                            .anyMatch(unit -> unit.id().equals(control.quantity().input.unit()))));
          else
            control.widget().active =
                availableHere
                    && !loading
                    && current.availability() == EditorActor.Availability.READY;
          if (control.quantity() != null) {
            var quantity = control.quantity();
            boolean available =
                current.availability() == EditorActor.Availability.READY
                    && availableHere
                    && !saving
                    && !loading
                    && pendingEdits == 0
                    && !unaccepted;
            quantity.units.active = available;
            var less = quantity.input.step(-1);
            var more = quantity.input.step(1);
            quantity.less.active = available && less.accepted();
            quantity.more.active = available && more.accepted();
            quantity.less.setTooltip(
                Tooltip.create(
                    less.accepted()
                        ? text("decrease", "Decrease")
                        : Component.literal(less.reason())));
            quantity.more.setTooltip(
                Tooltip.create(
                    more.accepted()
                        ? text("increase", "Increase")
                        : Component.literal(more.reason())));
            if (quantity.slider != null) {
              quantity.slider.display(quantity.input, quantity.units.getMessage());
              quantity.slider.active =
                  !saving
                      && availableHere
                      && !loading
                      && current.availability() == EditorActor.Availability.READY
                      && quantity.input.position().isPresent();
            }
          }
          var issue =
              current.validation().issues().stream()
                  .filter(value -> value.path().equals(path) || value.related().contains(path))
                  .findFirst();
          var summary =
              issue
                  .<Component>map(
                      value ->
                          text("invalid", "Invalid").copy().append(": ").append(value.fallback()))
                  .orElseGet(
                      () ->
                          control.quantity() == null
                              ? Component.empty()
                              : Component.literal(control.quantity().input.notice()));
          if (!availableHere) {
            var explanation = dependencyText(path).copy();
            if (!summary.getString().isEmpty()) explanation.append("\n").append(summary);
            summary = explanation;
          }
          panel.summary(FormModel.key(path), summary);
          if (control.widget() instanceof EditBox input && control.editable()) {
            input.setTextColor(
                issue.isEmpty() ? FormPanel.TITLE_TEXT : ChatFormatting.RED.getColor());
            input.setTooltip(issue.isEmpty() && availableHere ? null : Tooltip.create(summary));
          }
        });
    availabilityButtons.forEach(
        (path, button) -> {
          button.active = current.availability() == EditorActor.Availability.READY && !loading;
          button.setTooltip(Tooltip.create(dependencyText(path)));
        });
    availabilityRows.forEach((path, key) -> panel.summary(key, dependencyText(path)));
  }

  @Override
  public void tick() {
    if (displayedLanguage != Language.getInstance()) {
      if (catalog != null) catalog.preserveMode(getFocused() == panel);
      rebuildWidgets();
      return;
    }
    if (displayed != actions.view()) {
      displayed = actions.view();
      boolean changed = layoutChanged(displayed);
      if (catalog != null) {
        FormModel currentModel = null;
        if (displayed.snapshot().isPresent()) {
          try {
            currentModel = model(displayed.snapshot().orElseThrow().draft());
          } catch (IllegalArgumentException unavailable) {
            /* The form reports this state during rebuild. */
          }
        }
        changed |=
            catalog.project(
                currentModel,
                displayed.snapshot().map(value -> value.session()).orElse(null),
                displayed.snapshot().map(value -> value.generation()).orElse(0L));
      }
      if (changed) rebuildWidgets();
      else {
        if (catalog != null) catalog.refresh();
        feedback();
      }
    }
  }

  private Component status() {
    var current = actions.view();
    if (notice != null) return notice;
    if (saving)
      return contextRecipe()
          ? text("admitting", "Applying recipe; waiting for the actual result")
          : text("saving", "Saving; waiting for the actual result");
    if (loading)
      return contextRecipe()
          ? text("reloading_context", "Loading context values…")
          : text("reloading", "Loading saved values…");
    if (pendingEdits > 0) return text("validating", "Validating edits…");
    if (current.availability() == EditorActor.Availability.LOADING)
      return text("loading", "Loading settings…");
    if (current.availability() == EditorActor.Availability.CLOSED)
      return text("closed", "This editing context is closed.");
    if (!current.observationReport().valid()) return first(current.observationReport());
    if (!current.validation().valid()) return first(current.validation());
    if (!current.operationReport().valid()) return first(current.operationReport());
    if (current.review().isPresent())
      return contextRecipe()
          ? text("context_review_required", "Context values changed; conflict review is required.")
          : text("review_required", "Saved values changed; conflict review is required.");
    if (current.pending().isPresent())
      return contextRecipe()
          ? text(
              "context_reconcile_required",
              "The previous application needs an observed result. Reload to review it.")
          : text(
              "reconcile_required",
              "The previous save needs an observed result. Reload to review it.");
    if (unsent())
      return text("retry_required", "Some edits were not accepted. Retry them before applying.");
    return current.dirty()
        ? contextRecipe()
            ? text("unapplied", "Unapplied changes")
            : text("unsaved", "Unsaved changes")
        : contextRecipe()
            ? text("context_ready", "Ready; recipe draft matches context")
            : text("ready", "Ready; no unsaved edits");
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    super.render(graphics, mouseX, mouseY, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    graphics.drawWordWrap(
        font, contextLabel, 12, 30, Math.max(1, width - 24), FormPanel.DESCRIPTION_TEXT);
    var lines = font.split(status(), Math.max(1, width - 24));
    for (int i = 0; i < Math.min(2, lines.size()); i++)
      graphics.drawString(
          font, lines.get(i), 12, statusTop + i * font.lineHeight, FormPanel.DESCRIPTION_TEXT);
  }

  @Override
  public Component getNarrationMessage() {
    var message = title.copy().append(". ").append(contextLabel).append(". ").append(status());
    return catalog == null ? message : message.append(". ").append(catalog.narration());
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (closed || minecraft.screen != this) return false;
    if (catalog != null && key == GLFW.GLFW_KEY_ESCAPE && catalog.clearFocusedFilter()) return true;
    if (super.keyPressed(key, scan, modifiers)) return true;
    if (loading) return false;
    if (hasControlDown()) {
      if (catalog != null && key == GLFW.GLFW_KEY_F) return catalog.openSearch();
      if (key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_ENTER) {
        requestSave();
        return true;
      }
      if (key == GLFW.GLFW_KEY_Z) {
        requestHistory(hasShiftDown());
        return true;
      }
      if (key == GLFW.GLFW_KEY_Y) {
        requestHistory(true);
        return true;
      }
    }
    return false;
  }

  private void requestHistory(boolean forward) {
    var view = actions.view();
    if (forward ? !view.canRedo() : !view.canUndo()) return;
    perform(
        action(() -> forward ? actions.redo(EntryPoint.GUI) : actions.undo(EntryPoint.GUI)), true);
  }

  @Override
  public void onClose() {
    if (closed || minecraft.screen != this) return;
    if (saving || loading || pendingEdits > 0) {
      notice = text("wait_close", "Wait for the current operation before closing.");
      return;
    }
    if (actions.view().dirty() || unsent() || actions.view().review().isPresent()) {
      boolean[] decided = {false};
      handoff = true;
      minecraft.setScreen(
          new ConfirmScreen(
              choice -> {
                decided[0] = true;
                if (choice) finish();
                else minecraft.setScreen(this);
              },
              contextRecipe()
                  ? text("discard_recipe_title", "Discard unapplied recipe edits?")
                  : text("discard_title", "Discard unsaved edits?"),
              contextRecipe()
                  ? text(
                      "discard_recipe_help",
                      "Discard this draft. This does not undo a creation-context update that already took place.")
                  : text(
                      "discard_help",
                      "Discard this draft. This does not undo any save that has already taken place."),
              text("discard", "Discard draft"),
              text("keep_editing", "Keep editing")) {
            @Override
            public void removed() {
              if (!decided[0]) actions.close();
            }
          });
      handoff = false;
    } else finish();
  }

  private void finish() {
    closed = true;
    actions.close();
    disposePresentation();
    minecraft.setScreen(parent);
  }

  private void disposePresentation() {
    if (catalog != null) catalog.close();
    inputPositions.clear();
    cachedModel = null;
    modelDraft = null;
    modelFailure = null;
  }

  @Override
  public void removed() {
    dependencyView = null;
    dependencyEvaluation = null;
    dependencyVersion = -1;
    if (!handoff) {
      if (!closed) actions.close();
      disposePresentation();
    }
  }

  private static String raw(DraftNode node) {
    if (node instanceof DraftNode.Scalar scalar) return scalar.raw();
    if (node instanceof DraftNode.Opaque opaque) return opaque.json();
    return node instanceof DraftNode.NullValue ? "null" : "";
  }

  private static DraftNode at(DraftTree draft, FieldPath path) {
    try {
      return draft.at(path);
    } catch (IllegalArgumentException unavailable) {
      return new DraftNode.Missing();
    }
  }

  private boolean layoutChanged(EditorActor.View<T> view) {
    if ((conflict != null) != view.review().isPresent()) return true;
    if (view.snapshot().isEmpty()) return !formLayout.isEmpty();
    var snapshot = view.snapshot().orElseThrow();
    if (!snapshot.session().equals(formSession) || snapshot.generation() != formGeneration)
      return true;
    try {
      return !formLayout.equals(model(snapshot.draft()).layout());
    } catch (IllegalArgumentException unavailable) {
      return !formUnavailable;
    }
  }

  private String labelKey(FieldPath path) {
    return FieldLabels.key(definition.id(), path);
  }

  private static Component first(ValidationReport report) {
    return report.issues().isEmpty()
        ? text("unavailable", "Validation is unavailable")
        : Component.literal(report.issues().getFirst().fallback());
  }

  private static Component failure(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return Component.literal(Objects.toString(failed.getMessage(), "The operation is unavailable"));
  }

  private static Component saved(CommitReceipt<?> receipt) {
    if (receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
        && receipt.acknowledgesBaseline())
      return receipt.applicationReport().valid()
          ? text("recipe_applied", "Applied to this creation context; no file saved")
          : text(
              "recipe_applied_unobserved",
              "Application confirmed; the accepted recipe could not be observed");
    if (receipt.baselineOutcomeUnknown())
      return receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
          ? text(
              "context_outcome_unknown",
              "Application outcome needs review; reopen the recipe from the current creation context")
          : text("outcome_unknown", "The operation outcome needs review; do not repeat it blindly");
    if (receipt.persistence() == CommitReceipt.Persistence.SAVED
        || receipt.persistence() == CommitReceipt.Persistence.UNCHANGED) {
      if (receipt.application() == CommitReceipt.Application.STAGED)
        return text(
            "saved_staged", "Saved; review the owner's application and restart requirements.");
      if (receipt.application() == CommitReceipt.Application.PARTIAL)
        return text("saved_partial", "Saved; only part of the application was confirmed.");
      if (receipt.persistence() == CommitReceipt.Persistence.UNCHANGED
          && receipt.application() == CommitReceipt.Application.NOT_ATTEMPTED)
        return text("unchanged", "No changes to save");
      return receipt.application() == CommitReceipt.Application.APPLIED
          ? text("saved", "Saved and applied")
          : text(
              "saved_not_applied", "Saved; application did not complete. Review the owner result.");
    }
    return first(receipt.persistenceReport());
  }
}
