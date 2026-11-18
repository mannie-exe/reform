package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormModel;
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
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Generated recursive native form over the same admitted owner/operation services. */
public final class SettingsScreen<T> extends Screen {
  private record Control(
      AbstractWidget widget,
      Consumer<String> display,
      boolean editable,
      ValueShape shape,
      boolean cycle,
      DraftTarget target,
      Object token) {}

  private final Screen parent;
  private final Component contextLabel;
  private final DocumentDefinition<T> definition;
  private final DocumentActions<T> actions;
  private final Map<DocumentId, ReferenceActions<String, ?>> sources;
  private final Map<FieldPath, Control> controls = new LinkedHashMap<>();
  private final Map<FieldPath, String> local = new LinkedHashMap<>();
  private final Map<FieldPath, Long> versions = new LinkedHashMap<>();
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final List<FieldPath> visibleFields = new ArrayList<>();
  private final Map<AbstractWidget, Boolean> structureButtons = new LinkedHashMap<>();
  private List<FormModel.Layout> formLayout = List.of();
  private UUID formSession;
  private long formGeneration;
  private FormPanel panel;
  private EditorActor.View<T> displayed;
  private Button save, revert, defaults, reload, done;
  private int bodyTop, statusTop, pendingEdits;
  private long version;
  private boolean requested, updating, saving, loading, handoff, closed, formUnavailable;
  private Component notice;

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
    if (definition != actions.definition())
      throw new IllegalArgumentException("A form requires its actual owner definition");
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
    return Optional.ofNullable(controls.get(path)).map(Control::widget);
  }

  /** Focus and reveal a currently rendered field, including related diagnostic destinations. */
  public boolean focusField(FieldPath path) {
    var control = controls.get(path);
    if (control == null || panel == null) return false;
    String focus = panel.fieldFocus(FormModel.key(path), control.widget());
    if (focus == null) return false;
    setFocused(panel);
    panel.restoreFocus(focus);
    return true;
  }

  @Override
  protected void init() {
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    footer.clear();
    structureButtons.clear();
    var current = actions.view();
    bodyTop = 40 + font.split(contextLabel, Math.max(1, width - 24)).size() * font.lineHeight;
    save = button(commitLabel(), this::requestSave);
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
    panel =
        new FormPanel(
            minecraft,
            12,
            bodyTop,
            Math.max(1, width - 24),
            Math.max(1, statusTop - bodyTop - 8),
            false);
    visibleFields.clear();
    formLayout = List.of();
    if (current.snapshot().isPresent()) {
      var snapshot = current.snapshot().orElseThrow();
      formSession = snapshot.session();
      formGeneration = snapshot.generation();
      FormModel model = null;
      try {
        model = new FormModel(definition.type(), snapshot.draft());
        formLayout = model.layout();
        formUnavailable = false;
      } catch (IllegalArgumentException unavailable) {
        formUnavailable = true;
        notice = failure(unavailable);
        panel.row(text("form_unavailable", "Form unavailable"), notice);
      }
      if (model != null) fields(model.root(), title);
    }
    controls.keySet().retainAll(visibleFields);
    local.keySet().retainAll(visibleFields);
    versions.keySet().retainAll(visibleFields);
    addRenderableWidget(panel);
    panel.finish(scroll);
    panel.restoreFocus(focus);
    if (focus != null) setFocused(panel);
    displayed = current;
    feedback();
    if (!requested) {
      requested = true;
      if (current.availability() == EditorActor.Availability.LOADING) requestReload();
    }
  }

  @Override
  protected void rebuildWidgets() {
    var bookmark = panel == null ? null : panel.bookmark();
    String focusedField = panel == null ? null : panel.focusKey();
    int focusedFooter = footer.indexOf(getFocused());
    super.rebuildWidgets();
    if (bookmark != null) panel.restore(bookmark);
    if (focusedField != null) {
      // Native rebuild clears the old focus tree before init. Restore the parent first.
      setFocused(panel);
      panel.restoreFocus(focusedField);
    } else if (focusedFooter >= 0 && focusedFooter < footer.size())
      setFocused(footer.get(focusedFooter));
  }

  @Override
  protected void setInitialFocus() {
    if (panel != null && !controls.isEmpty()) {
      setFocused(panel);
      panel.focusVisibleControl();
      if (panel.getFocused() != null) return;
    }
    super.setInitialFocus();
  }

  private Button button(Component label, Runnable action) {
    var result =
        Button.builder(
                label,
                ignored -> {
                  if (!closed && minecraft.screen == this) action.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    footer.add(result);
    return result;
  }

  private void fields(FormModel.Node field, Component label) {
    var path = field.path();
    var node = field.input();
    var shape = field.type().orElseThrow().shape();
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
      panel.row(
          FormModel.key(path) + ":collection",
          label,
          Component.translatableWithFallback(
              "reform.settings.entry_count", "%s entries (limit %s)", size, maximum),
          add);
      for (int i = 0; i < size; i++) {
        var child = field.children().get(i);
        var row = ((FieldPath.Row) child.path().segments().getLast()).id();
        var rowLabel =
            Component.translatableWithFallback("reform.settings.entry", "Entry %s", i + 1);
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
                            ((FieldPath.Row)
                                    field.children().get(i - 1).path().segments().getLast())
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
                            ((FieldPath.Row)
                                    field.children().get(i + 2).path().segments().getLast())
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
            structuralButton(
                path, text("remove", "Remove"), rowContext, new DraftEdit.RemoveRow(row)));
        fields(child, rowLabel);
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
                  (widget, id) -> structural(path, new DraftEdit.SelectVariant(id)));
      structureButtons.put(choice, true);
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
        || !control.target().session().equals(formSession)) {
      local.put(path, raw);
      versions.put(path, 0L);
      control = createControl(shape, path, raw, label, cycle);
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
              control.token());
      controls.put(path, control);
    }
    if (shape instanceof ValueShape.Reference<?> reference) {
      var source = sources.get(reference.source());
      var browse =
          Button.builder(
                  source == null
                      ? text("source_unavailable", "Source unavailable")
                      : text("browse", "Browse…"),
                  ignored -> openReference(path, label, source))
              .bounds(0, 0, 120, 20)
              .createNarration(original -> label.copy().append(": ").append(original.get()))
              .build();
      browse.setTooltip(Tooltip.create(Component.literal(reference.source().toString())));
      structureButtons.put(browse, source != null);
      panel.field(FormModel.key(path), label, Component.empty(), control.widget(), browse);
    } else panel.field(FormModel.key(path), label, Component.empty(), control.widget());
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

  private void children(FormModel.Node parent) {
    for (var child : parent.children()) {
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
    var button =
        Button.builder(label, ignored -> structural(path, edit))
            .bounds(0, 0, Math.max(40, font.width(label) + 16), 20)
            .createNarration(original -> context.copy().append(": ").append(original.get()))
            .build();
    structureButtons.put(button, true);
    return button;
  }

  private void structural(FieldPath path, DraftEdit.Action edit) {
    if (closed || minecraft.screen != this) return;
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
      ValueShape declared, FieldPath path, String raw, Component label, boolean cycle) {
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
        return new Control(button, button::setValue, true, declared, true, target, token);
      }
      var input = new EditBox(font, 0, 0, 160, 20, label);
      input.setMaxLength(
          Math.max(raw.length(), scalar.maximumLength() > 0 ? scalar.maximumLength() : 128));
      input.setValue(raw);
      input.setResponder(value -> edit(path, token, value));
      return new Control(
          input,
          value -> {
            input.setMaxLength(
                Math.max(
                    scalar.maximumLength() > 0 ? scalar.maximumLength() : 128,
                    Math.max(input.getValue().length(), value.length())));
            input.setValue(value);
          },
          true,
          declared,
          false,
          target,
          token);
    }
    var unavailable = new EditBox(font, 0, 0, 160, 20, label);
    unavailable.setMaxLength(Math.max(128, raw.length()));
    unavailable.setValue(
        raw.isEmpty() ? text("unsupported", "Editor unavailable").getString() : raw);
    unavailable.setEditable(false);
    unavailable.setTooltip(
        Tooltip.create(
            text("unsupported_help", "This type needs a dedicated editor. Its data is retained.")));
    return new Control(unavailable, ignored -> {}, false, declared, false, target, token);
  }

  private void edit(FieldPath path, Object token, String value) {
    if (closed || minecraft.screen != this) return;
    var current = controls.get(path);
    if (current == null || current.token() != token) return;
    if (updating || value.equals(local.get(path))) return;
    local.put(path, value);
    versions.put(path, ++version);
    submit(path, value);
  }

  private void submit(FieldPath path, String value) {
    var target = controls.get(path).target();
    pendingEdits++;
    notice = null;
    action(() -> actions.edit(EntryPoint.GUI, new DraftEdit(target, new DraftEdit.SetRaw(value))))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      pendingEdits--;
                      if (failed != null) notice = failure(failed);
                      if (minecraft.screen == this) feedback();
                    }));
    feedback();
  }

  private boolean unsent() {
    var snapshot = actions.view().snapshot();
    if (snapshot.isEmpty()) return false;
    return controls.entrySet().stream()
        .anyMatch(
            entry ->
                entry.getValue().editable()
                    && !Objects.equals(
                        local.get(entry.getKey()),
                        raw(at(snapshot.orElseThrow().draft(), entry.getKey()))));
  }

  private void requestSave() {
    if (saving || loading || pendingEdits > 0 || actions.view().snapshot().isEmpty()) return;
    if (unsent()) {
      var draft = actions.view().snapshot().orElseThrow().draft();
      for (var entry : controls.entrySet())
        if (entry.getValue().editable()
            && !Objects.equals(local.get(entry.getKey()), raw(at(draft, entry.getKey()))))
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
              var raw = raw(at(snapshot.orElseThrow().draft(), path));
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
    save.setMessage(unsent() ? text("retry_edits", "Retry edits") : commitLabel());
    save.active =
        current.snapshot().isPresent()
            && current.availability() == EditorActor.Availability.READY
            && !formUnavailable
            && !saving
            && !loading
            && pendingEdits == 0
            && current.pending().isEmpty()
            && current.review().isEmpty()
            && (unsent() || current.validation().valid());
    revert.active =
        defaults.active =
            current.snapshot().isPresent() && !saving && !loading && pendingEdits == 0;
    reload.active = done.active = !saving && !loading && pendingEdits == 0;
    structureButtons.forEach(
        (button, enabled) ->
            button.active =
                enabled
                    && current.availability() == EditorActor.Availability.READY
                    && !loading
                    && !saving
                    && pendingEdits == 0
                    && !unsent());
    controls.forEach(
        (path, control) -> {
          if (control.widget() instanceof EditBox input)
            input.setEditable(
                control.editable()
                    && !loading
                    && current.availability() == EditorActor.Availability.READY);
          else
            control.widget().active =
                !loading && current.availability() == EditorActor.Availability.READY;
          var issue =
              current.validation().issues().stream()
                  .filter(value -> value.path().equals(path) || value.related().contains(path))
                  .findFirst();
          var summary =
              issue
                  .<Component>map(
                      value ->
                          text("invalid", "Invalid").copy().append(": ").append(value.fallback()))
                  .orElse(Component.empty());
          panel.summary(FormModel.key(path), summary);
          if (control.widget() instanceof EditBox input && control.editable()) {
            input.setTextColor(
                issue.isEmpty() ? FormPanel.TITLE_TEXT : ChatFormatting.RED.getColor());
            input.setTooltip(issue.isEmpty() ? null : Tooltip.create(summary));
          }
        });
  }

  @Override
  public void tick() {
    if (displayed != actions.view()) {
      displayed = actions.view();
      if (layoutChanged(displayed)) rebuildWidgets();
      else feedback();
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
    return title.copy().append(". ").append(contextLabel).append(". ").append(status());
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (closed || minecraft.screen != this) return false;
    if (super.keyPressed(key, scan, modifiers)) return true;
    if (loading) return false;
    if (hasControlDown()) {
      if (key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_ENTER) {
        requestSave();
        return true;
      }
      if (key == GLFW.GLFW_KEY_Z) {
        perform(
            hasShiftDown()
                ? action(() -> actions.redo(EntryPoint.GUI))
                : action(() -> actions.undo(EntryPoint.GUI)),
            true);
        return true;
      }
      if (key == GLFW.GLFW_KEY_Y) {
        perform(action(() -> actions.redo(EntryPoint.GUI)), true);
        return true;
      }
    }
    return false;
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
    minecraft.setScreen(parent);
  }

  @Override
  public void removed() {
    if (!handoff && !closed) actions.close();
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
    if (view.snapshot().isEmpty()) return !formLayout.isEmpty();
    var snapshot = view.snapshot().orElseThrow();
    if (!snapshot.session().equals(formSession) || snapshot.generation() != formGeneration)
      return true;
    try {
      return !formLayout.equals(new FormModel(definition.type(), snapshot.draft()).layout());
    } catch (IllegalArgumentException unavailable) {
      return !formUnavailable;
    }
  }

  private String labelKey(FieldPath path) {
    var parts =
        path.segments().stream()
            .map(
                segment ->
                    switch (segment) {
                      case FieldPath.Field field -> escape(field.id());
                      case FieldPath.Row ignored -> "%row";
                      case FieldPath.Variant variant -> "%variant:" + escape(variant.id());
                      case FieldPath.Part part -> part == FieldPath.Part.KEY ? "%key" : "%value";
                    })
            .toList();
    return definition.id().namespace()
        + ":settings/"
        + escape(definition.id().path())
        + "/fields/"
        + String.join("/", parts);
  }

  private static String escape(String value) {
    return value.replace("%", "%25").replace("/", "%2F");
  }

  private static String readable(String id) {
    var words = id.replace('_', ' ').replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
    return words.isEmpty() ? id : Character.toUpperCase(words.charAt(0)) + words.substring(1);
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
