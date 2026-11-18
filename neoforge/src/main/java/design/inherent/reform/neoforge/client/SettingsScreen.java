package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/** Initial native scalar/record form over an admitted owner-backed editing actor. */
public final class SettingsScreen<T> extends Screen {
  private record Control(AbstractWidget widget, Consumer<String> display, boolean editable) {}

  private final Screen parent;
  private final Component contextLabel;
  private final DocumentDefinition<T> definition;
  private final DocumentActions<T> actions;
  private final Map<FieldPath, Control> controls = new LinkedHashMap<>();
  private final Map<FieldPath, String> local = new LinkedHashMap<>();
  private final Map<FieldPath, Long> versions = new LinkedHashMap<>();
  private final List<AbstractWidget> footer = new ArrayList<>();
  private final List<FieldPath> visibleFields = new ArrayList<>();
  private FormPanel panel;
  private EditorActor.View<T> displayed;
  private Button save, revert, defaults, reload, done;
  private int bodyTop, statusTop, pendingEdits;
  private long version;
  private boolean requested, updating, saving, loading, handoff, closed;
  private Component notice;

  /** No implicit initialization, actor lookup or settings ownership is inferred by the screen. */
  public SettingsScreen(
      Screen parent,
      Component title,
      Component contextLabel,
      DocumentDefinition<T> definition,
      DocumentActions<T> actions) {
    super(title);
    this.parent = parent;
    this.contextLabel = Objects.requireNonNull(contextLabel);
    this.definition = Objects.requireNonNull(definition);
    this.actions = Objects.requireNonNull(actions);
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

  @Override
  protected void init() {
    float scroll = panel == null ? 0 : panel.scroll();
    String focus = panel == null ? null : panel.focusKey();
    footer.clear();
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
    if (current.snapshot().isPresent())
      fields(
          definition.type(),
          FieldPath.ROOT,
          current.snapshot().orElseThrow().draft().root(),
          title);
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
        Button.builder(label, ignored -> action.run())
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    footer.add(result);
    return result;
  }

  private void fields(ValueType<?> type, FieldPath path, DraftNode node, Component label) {
    if (type.shape() instanceof ValueShape.ObjectValue object
        && node instanceof DraftNode.ObjectValue values) {
      if (!path.equals(FieldPath.ROOT)) panel.heading(label);
      for (var field : object.fields()) {
        DraftNode child = values.fields().getOrDefault(field.id(), new DraftNode.Missing());
        var childPath = path.field(field.id());
        var title = Component.translatableWithFallback(labelKey(childPath), readable(field.id()));
        fields(field.type(), childPath, child, title);
      }
      return;
    }
    visibleFields.add(path);
    var control = controls.get(path);
    if (control == null) {
      var raw = raw(node);
      local.put(path, raw);
      versions.put(path, 0L);
      control = createControl(type.shape(), path, raw, label);
      controls.put(path, control);
    }
    panel.field(path.toString(), label, Component.empty(), control.widget());
  }

  private Control createControl(ValueShape shape, FieldPath path, String raw, Component label) {
    if (shape instanceof ValueShape.Reference<?> reference)
      return createControl(reference.element().shape(), path, raw, label);
    if (shape instanceof ValueShape.Scalar scalar) {
      var choices =
          scalar.kind() == ValueShape.ScalarKind.BOOLEAN
              ? List.of("false", "true")
              : scalar.choices();
      if (!choices.isEmpty() && choices.contains(raw)) {
        var button =
            CycleButton.<String>builder(Component::literal)
                .withValues(choices)
                .withInitialValue(raw)
                .displayOnlyValue()
                .create(0, 0, 160, 20, label, (widget, value) -> edit(path, value));
        return new Control(button, button::setValue, true);
      }
      var input = new EditBox(font, 0, 0, 160, 20, label);
      input.setMaxLength(
          Math.max(raw.length(), scalar.maximumLength() > 0 ? scalar.maximumLength() : 128));
      input.setValue(raw);
      input.setResponder(value -> edit(path, value));
      return new Control(input, input::setValue, true);
    }
    var unavailable = new EditBox(font, 0, 0, 160, 20, label);
    unavailable.setValue(text("unsupported", "Editor unavailable").getString());
    unavailable.setEditable(false);
    unavailable.setTooltip(
        Tooltip.create(
            text("unsupported_help", "This type needs a dedicated editor. Its data is retained.")));
    return new Control(unavailable, ignored -> {}, false);
  }

  private void edit(FieldPath path, String value) {
    if (updating || value.equals(local.get(path))) return;
    local.put(path, value);
    versions.put(path, ++version);
    submit(path, value);
  }

  private void submit(FieldPath path, String value) {
    pendingEdits++;
    notice = null;
    action(
            () ->
                actions.edit(
                    EntryPoint.GUI, actions.fields().field(path).orElseThrow().selector(), value))
        .whenComplete(
            (result, failed) ->
                minecraft.execute(
                    () -> {
                      pendingEdits--;
                      if (failed != null) notice = failure(failed);
                      if (minecraft.screen == this) feedback();
                    }));
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
                      else result.receipt().ifPresent(receipt -> notice = saved(receipt));
                      if (minecraft.screen == this) feedback();
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
    controls.forEach(
        (path, control) -> {
          if (control.widget() instanceof EditBox input)
            input.setEditable(control.editable() && !loading);
          else control.widget().active = !loading;
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
          panel.summary(path.toString(), summary);
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
    var expected = new ArrayList<FieldPath>();
    view.snapshot()
        .ifPresent(
            snapshot ->
                leaves(definition.type(), FieldPath.ROOT, snapshot.draft().root(), expected));
    return !expected.equals(visibleFields);
  }

  private static void leaves(
      ValueType<?> type, FieldPath path, DraftNode node, List<FieldPath> found) {
    if (type.shape() instanceof ValueShape.ObjectValue object
        && node instanceof DraftNode.ObjectValue value) {
      for (var field : object.fields())
        leaves(
            field.type(),
            path.field(field.id()),
            value.fields().getOrDefault(field.id(), new DraftNode.Missing()),
            found);
    } else found.add(path);
  }

  private String labelKey(FieldPath path) {
    var parts =
        path.segments().stream().map(segment -> escape(((FieldPath.Field) segment).id())).toList();
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
