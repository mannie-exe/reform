package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** A bounded native draft decision; its callback requests work from the actual owner. */
public final class UnsavedChangesScreen extends NativeFormScreen {
  public enum Choice {
    SAVE,
    DISCARD,
    KEEP_EDITING
  }

  private final Component contextLabel, explanation, saveLabel;
  private final Supplier<Optional<Component>> saveBlocked;
  private final Consumer<Choice> selected;
  private final Runnable abandoned;
  private final EnumMap<Choice, Button> controls = new EnumMap<>(Choice.class);
  private FormPanel panel;
  private Optional<Component> blocked = Optional.empty();
  private Language language;
  private Object generation;
  private Choice preferredFocus = Choice.KEEP_EDITING;
  private boolean decided, closed;

  /**
   * saveBlocked reads cached local readiness only: no IO, validation or native permission lookup. A
   * choice never authorizes a save; the callback must retain actual owner preflight and receipts.
   */
  public UnsavedChangesScreen(
      Component title,
      Component contextLabel,
      Component explanation,
      Component saveLabel,
      Supplier<Optional<Component>> saveBlocked,
      Consumer<Choice> selected,
      Runnable abandoned) {
    super(Objects.requireNonNull(title).copy());
    this.contextLabel = Objects.requireNonNull(contextLabel).copy();
    this.explanation = Objects.requireNonNull(explanation).copy();
    this.saveLabel = Objects.requireNonNull(saveLabel).copy();
    this.saveBlocked = Objects.requireNonNull(saveBlocked);
    this.selected = Objects.requireNonNull(selected);
    this.abandoned = Objects.requireNonNull(abandoned);
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.close." + key, fallback);
  }

  private Optional<Component> readiness() {
    try {
      return Objects.requireNonNull(saveBlocked.get()).map(Component::copy);
    } catch (RuntimeException unavailable) {
      return Optional.of(
          text("unavailable", "This action is unavailable. Keep editing to review the problem."));
    }
  }

  @Override
  protected void init() {
    generation = new Object();
    language = Language.getInstance();
    blocked = readiness();
    controls.clear();
    var widgets = new ArrayList<Button>();
    widgets.add(button(Choice.SAVE, saveLabel));
    widgets.add(button(Choice.DISCARD, text("discard", "Discard draft")));
    widgets.add(button(Choice.KEEP_EDITING, text("keep_editing", "Keep editing")));
    controls.get(Choice.SAVE).active = blocked.isEmpty();
    blocked.ifPresent(reason -> controls.get(Choice.SAVE).setTooltip(Tooltip.create(reason)));
    var layout =
        FormGeometry.controls(
            widgets.stream().map(widget -> new FormGeometry.Size(widget.getWidth(), 20)).toList(),
            Math.max(1, width - 24),
            4);
    int footerTop = height - 12 - layout.height();
    for (int index = 0; index < widgets.size(); index++) {
      var widget = widgets.get(index);
      var at = layout.placements().get(index);
      widget.setPosition(12 + at.x(), footerTop + at.y());
      widget.setWidth(at.width());
      addRenderableWidget(widget);
    }
    int bodyTop = 40 + font.split(contextLabel, Math.max(1, width - 24)).size() * font.lineHeight;
    panel =
        new FormPanel(
            minecraft,
            12,
            bodyTop,
            Math.max(1, width - 24),
            Math.max(1, footerTop - bodyTop - 8),
            false);
    panel.row(Component.empty(), explanation);
    blocked.ifPresent(reason -> panel.row(text("save_blocked", "Action unavailable"), reason));
    panel.finish(0);
    addRenderableWidget(panel);
    var preferred = controls.get(preferredFocus);
    setFocused(preferred.active ? preferred : controls.get(Choice.KEEP_EDITING));
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    super.setFocused(listener);
    controls.forEach(
        (choice, control) -> {
          if (getFocused() == control) preferredFocus = choice;
        });
  }

  private Button button(Choice choice, Component label) {
    var token = generation;
    var button =
        Button.builder(label, ignored -> choose(token, choice))
            .bounds(0, 0, Math.max(20, font.width(label) + 20), 20)
            .build();
    controls.put(choice, button);
    return button;
  }

  private void choose(Object token, Choice choice) {
    if (closed
        || decided
        || generation != token
        || minecraft == null
        || NativeScreens.current(minecraft) != this) return;
    if (choice == Choice.SAVE && readiness().isPresent()) {
      rebuildWidgets();
      return;
    }
    decided = true;
    selected.accept(choice);
  }

  @Override
  protected void setInitialFocus() {
    var focused = getFocused();
    if (focused == null || focused instanceof Button button && !button.active)
      setFocused(controls.get(Choice.KEEP_EDITING));
  }

  @Override
  public void tick() {
    if (closed || minecraft == null || NativeScreens.current(minecraft) != this) return;
    var next = readiness();
    if (language != Language.getInstance()
        || !next.map(Component::getString).equals(blocked.map(Component::getString)))
      rebuildWidgets();
  }

  @Override
  public void onClose() {
    choose(generation, Choice.KEEP_EDITING);
  }

  @Override
  public void removed() {
    if (closed) return;
    closed = true;
    controls.clear();
    panel = null;
    generation = null;
    if (!decided) abandoned.run();
  }

  @Override
  public Component getNarrationMessage() {
    var message = title.copy().append(". ").append(contextLabel).append(". ").append(explanation);
    blocked.ifPresent(reason -> message.append(". ").append(reason));
    return message;
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    graphics.drawCenteredString(font, title, width / 2, 12, FormPanel.TITLE_TEXT);
    graphics.drawWordWrap(
        font, contextLabel, 12, 30, Math.max(1, width - 24), FormPanel.DESCRIPTION_TEXT);
  }
}
