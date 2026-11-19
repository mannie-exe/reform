package design.inherent.reform.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormGeometry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.settings.KeyModifier;

/** Capture/reset one primary key as an undoable draft action. Native Save stays in the parent. */
public final class KeyBindingScreen extends FormScreen {
  public enum Control {
    CAPTURE,
    RESET,
    UNBIND,
    REFRESH,
    APPLY,
    BACK
  }

  private final FieldEditors.Request<Map<String, KeyBindingOptions.Binding>> request;
  private final KeyBindingOptions.Session session;
  private final String mapping;
  private final KeyBindingOptions.Binding defaults;
  private final Map<Control, Button> controls = new EnumMap<>(Control.class);
  private final List<Button> footer = new ArrayList<>();
  private KeyBindingOptions.Binding proposed;
  private KeyBindingOptions.Conflicts conflicts;
  private KeyCapture capture;
  private FormPanel panel;
  private Object widgets, reviewEpoch;
  private OperationHandle<EditorActor.View<Map<String, KeyBindingOptions.Binding>>> operation;
  private Component notice, conflictNotice;
  private int statusTop;
  private boolean requested, reviewing, reviewAgain, closed, returning, wasCurrent;

  public KeyBindingScreen(
      FieldEditors.Request<Map<String, KeyBindingOptions.Binding>> request,
      KeyBindingOptions.Session session,
      String mapping) {
    super(
        Component.translatableWithFallback(
            "reform.keys.title", "Edit binding: %s", Component.translatable(mapping)));
    this.request = Objects.requireNonNull(request);
    this.session = Objects.requireNonNull(session);
    this.mapping = Objects.requireNonNull(mapping);
    if (request.actions().definition() != session.definition()
        || !request.actions().context().equals(session.context())
        || !request
            .target()
            .path()
            .equals(design.inherent.reform.definition.FieldPath.ROOT.field(mapping)))
      throw new IllegalArgumentException(
          "Use this native key owner's actual form, context and field");
    defaults = session.defaultBinding(mapping);
    if (request.input() instanceof DraftNode.ObjectValue record
        && record.fields().get("key") instanceof DraftNode.Scalar key
        && record.fields().get("modifier") instanceof DraftNode.Scalar modifier) {
      try {
        var value = new KeyBindingOptions.Binding(key.raw(), KeyModifier.valueOf(modifier.raw()));
        KeyBindingOptions.nativeKey(value);
        proposed = value;
      } catch (IllegalArgumentException | design.inherent.reform.owner.PolicyRefusal invalid) {
        // Keep invalid input in the actual draft; capture/reset can repair only these two fields.
      }
    }
  }

  private static Component text(String key, String fallback, Object... arguments) {
    return Component.translatableWithFallback("reform.keys." + key, fallback, arguments);
  }

  public Optional<AbstractWidget> control(Control control) {
    return Optional.ofNullable(controls.get(control));
  }

  public Optional<KeyBindingOptions.Binding> proposedBinding() {
    return Optional.ofNullable(proposed);
  }

  public Optional<KeyBindingOptions.Conflicts> conflictReview() {
    return Optional.ofNullable(conflicts);
  }

  public boolean capturing() {
    return capture != null;
  }

  private boolean current() {
    return !closed && session.isOpen() && request.current();
  }

  @Override
  protected void init() {
    widgets = new Object();
    controls.clear();
    footer.clear();
    button(
        Control.CAPTURE,
        capture == null ? text("capture", "Capture…") : text("cancel_capture", "Cancel capture"),
        () -> {
          if (capture != null) capture = null;
          else if (current()) capture = new KeyCapture();
          notice = null;
          rebuildWidgets();
        });
    button(Control.RESET, text("reset", "Reset to default"), () -> choose(defaults));
    button(
        Control.UNBIND,
        text("unbind", "Unbind"),
        () ->
            choose(
                new KeyBindingOptions.Binding(InputConstants.UNKNOWN.getName(), KeyModifier.NONE)));
    button(Control.REFRESH, text("review", "Refresh conflicts"), this::review);
    button(Control.APPLY, text("apply", "Apply draft"), this::apply);
    button(Control.BACK, text("back", "Back"), this::onClose);
    var layout =
        FormGeometry.controls(
            footer.stream().map(widget -> new FormGeometry.Size(widget.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int top = height - 12 - layout.height();
    statusTop = top - 24;
    for (int i = 0; i < footer.size(); i++) {
      var widget = footer.get(i);
      var placement = layout.placements().get(i);
      widget.setPosition(12 + placement.x(), top + placement.y());
      widget.setWidth(placement.width());
      addRenderableWidget(widget);
    }
    int bodyTop =
        34 + font.split(request.contextLabel(), Math.max(1, width - 24)).size() * font.lineHeight;
    panel =
        new FormPanel(
            minecraft,
            12,
            bodyTop,
            Math.max(1, width - 24),
            Math.max(1, statusTop - bodyTop - 8),
            false);
    panel.row(
        "purpose",
        text("purpose", "Native keybinding"),
        text(
            "help",
            "Capture one keyboard or mouse button, optionally with one native modifier. Release the key and modifier to finish. Escape unbinds while capturing; Cancel capture keeps the earlier selection."));
    panel.row("candidate", text("candidate", "Proposed binding"), name(proposed));
    panel.row("default", text("default", "Registered default"), name(defaults));
    panel.row(
        "effects",
        text("effects", "Draft only"),
        text(
            "effects_help",
            "Apply draft makes one Undo step. Save and apply in the parent form updates native Controls and options.txt. Back discards this local selection."));
    if (conflicts != null) {
      panel.row(
          "review_scope",
          text("review_scope", "Conflict review"),
          conflicts.nativeCurrent()
              ? text(
                  "native_review",
                  "Compared actual current native Controls bindings (%s entries).",
                  conflicts.inspected())
              : text(
                  "projected_review",
                  "Draft preview using native default context/modifier rules (%s entries). Custom mod dispatch may differ.",
                  conflicts.inspected()));
      if (conflicts.mappings().isEmpty())
        panel.row(
            "no_conflicts",
            text("conflicts", "Conflicts"),
            text("none", "No conflicts found in this review."));
      else
        for (int i = 0; i < conflicts.mappings().size(); i++)
          panel.row(
              "conflict:" + i,
              text("conflicts", "Conflicts"),
              Component.translatable(conflicts.mappings().get(i)));
    }
    if (conflictNotice != null)
      panel.row("review_unavailable", text("review_scope", "Conflict review"), conflictNotice);
    addRenderableWidget(panel);
    panel.finish(0);
    wasCurrent = current();
    feedback();
  }

  private Component name(KeyBindingOptions.Binding value) {
    if (value == null)
      return text("invalid", "Invalid input retained; capture, reset or repair the raw fields.");
    var key = KeyBindingOptions.nativeKey(value);
    return value.modifier().getCombinedName(key, key::getDisplayName);
  }

  private void button(Control id, Component label, Runnable callback) {
    var generation = widgets;
    var button =
        Button.builder(
                label,
                ignored -> {
                  if (!closed
                      && minecraft.screen == this
                      && widgets == generation
                      && operation == null) callback.run();
                })
            .bounds(0, 0, Math.max(56, font.width(label) + 20), 20)
            .build();
    controls.put(id, button);
    footer.add(button);
  }

  private void choose(KeyBindingOptions.Binding value) {
    if (!current() || operation != null) return;
    KeyBindingOptions.nativeKey(value);
    proposed = value;
    capture = null;
    notice = null;
    reviewEpoch = new Object();
    conflicts = null;
    conflictNotice = null;
    review();
    rebuildWidgets();
  }

  private void review() {
    if (!current() || proposed == null || capture != null || operation != null) return;
    requested = true;
    if (reviewing) {
      reviewAgain = true;
      return;
    }
    reviewing = true;
    reviewAgain = false;
    var epoch = reviewEpoch = new Object();
    var value = proposed;
    try {
      session
          .conflicts(mapping, value, request.draft())
          .whenComplete(
              (reviewed, failed) ->
                  minecraft.execute(
                      () -> {
                        if (closed) return;
                        reviewing = false;
                        if (reviewEpoch == epoch && current() && value.equals(proposed)) {
                          conflicts = failed == null ? reviewed : null;
                          conflictNotice = failed == null ? null : failure(failed);
                        }
                        if (reviewAgain) review();
                        if (minecraft.screen == this) rebuildWidgets();
                      }));
    } catch (RuntimeException refused) {
      reviewing = false;
      conflicts = null;
      conflictNotice = failure(refused);
    }
  }

  private void apply() {
    if (!current() || proposed == null || capture != null || operation != null) return;
    try {
      operation =
          request
              .actions()
              .edit(
                  EntryPoint.GUI,
                  new DraftEdit(
                      request.target(),
                      new DraftEdit.SetRecordScalars(
                          Map.of("key", proposed.key(), "modifier", proposed.modifier().name()))));
      notice = null;
    } catch (RuntimeException refused) {
      notice = failure(refused);
    }
    feedback();
  }

  private void feedback() {
    boolean editable = current() && operation == null;
    controls.get(Control.CAPTURE).active = editable;
    controls.get(Control.RESET).active = editable && capture == null;
    controls.get(Control.UNBIND).active = editable && capture == null;
    controls.get(Control.REFRESH).active =
        editable && proposed != null && capture == null && !reviewing;
    controls.get(Control.APPLY).active = editable && proposed != null && capture == null;
    controls.get(Control.BACK).active = operation == null;
  }

  @Override
  public void tick() {
    if (closed || minecraft.screen != this) return;
    if (!requested) review();
    if (capture != null && (!current() || !minecraft.isWindowActive())) {
      capture = null;
      notice = text("capture_interrupted", "Capture stopped; the earlier selection is retained.");
      rebuildWidgets();
    }
    if (operation != null && operation.snapshot().terminal()) {
      var result = operation.snapshot();
      operation = null;
      if (result.result().isPresent()) {
        finish();
        return;
      }
      notice =
          result.report().issues().isEmpty()
              ? text("refused", "The draft change was not accepted.")
              : Component.literal(result.report().issues().getFirst().fallback());
    }
    boolean now = current();
    if (now != wasCurrent) {
      wasCurrent = now;
      rebuildWidgets();
    } else feedback();
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    if (closed || minecraft.screen != this) return false;
    if (capture != null) {
      if (current()) capture.press(key, scan);
      return true;
    }
    return super.keyPressed(key, scan, modifiers);
  }

  @Override
  public boolean keyReleased(int key, int scan, int modifiers) {
    if (closed || minecraft.screen != this) return false;
    if (capture != null) {
      if (current()) capture.release(key, scan).ifPresent(this::choose);
      return true;
    }
    return super.keyReleased(key, scan, modifiers);
  }

  @Override
  public boolean charTyped(char character, int modifiers) {
    return capture != null || super.charTyped(character, modifiers);
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (closed || minecraft.screen != this) return false;
    if (capture != null
        && !controls.get(Control.CAPTURE).isMouseOver(x, y)
        && !controls.get(Control.BACK).isMouseOver(x, y)) {
      if (current()) capture.mouse(button).ifPresent(this::choose);
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  protected void rebuildWidgets() {
    var bookmark = panel == null ? null : panel.bookmark();
    String focus = panel == null ? null : panel.focusKey();
    Control footerFocus =
        controls.entrySet().stream()
            .filter(entry -> entry.getValue() == getFocused())
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    super.rebuildWidgets();
    if (bookmark != null) panel.restore(bookmark);
    if (focus != null) {
      setFocused(panel);
      panel.restoreFocus(focus);
    } else if (footerFocus != null) setFocused(controls.get(footerFocus));
  }

  @Override
  protected void setInitialFocus() {
    setFocused(controls.get(Control.CAPTURE));
  }

  private Component status() {
    if (operation != null) return text("applying", "Validating the draft change…");
    if (!current())
      return text(
          "stale",
          "This input or context changed. Back retains your shared draft; open the field again.");
    if (notice != null) return notice;
    if (capture != null)
      return text(
          "capturing", "Press a key or mouse button. Release keys to finish; Escape unbinds.");
    if (reviewing) return text("reviewing", "Reviewing native Controls conflicts…");
    if (conflictNotice != null) return conflictNotice;
    return text("ready", "No native mapping changes until Save and apply in the parent form.");
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    super.render(graphics, mouseX, mouseY, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    graphics.drawWordWrap(
        font, request.contextLabel(), 12, 30, Math.max(1, width - 24), FormPanel.DESCRIPTION_TEXT);
    var lines = font.split(status(), Math.max(1, width - 24));
    for (int i = 0; i < Math.min(2, lines.size()); i++)
      graphics.drawString(
          font, lines.get(i), 12, statusTop + i * font.lineHeight, FormPanel.DESCRIPTION_TEXT);
  }

  @Override
  public Component getNarrationMessage() {
    return title.copy().append(". ").append(request.contextLabel()).append(". ").append(status());
  }

  @Override
  public void onClose() {
    if (closed || minecraft.screen != this || operation != null) return;
    finish();
  }

  private void finish() {
    closed = returning = true;
    capture = null;
    request.finish();
  }

  @Override
  public void removed() {
    if (!returning && !closed) {
      closed = true;
      capture = null;
      request.abandon();
    }
  }

  private static Component failure(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return Component.literal(
        Objects.toString(
            failed.getMessage(),
            "Conflict review is unavailable; no complete result was obtained."));
  }
}
