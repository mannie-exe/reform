package design.inherent.reform.minecraft.client;

import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Cached setup/availability UI with optional entry and retirement callbacks. */
public final class ClientStatusScreen extends NativeFormScreen {
  public record State(boolean ready, boolean canRetry, Component message) {
    public State {
      message = Objects.requireNonNull(message).copy();
    }

    @Override
    public Component message() {
      return message.copy();
    }
  }

  private final Screen parent;
  private final Supplier<State> state;
  private final Supplier<Screen> editor;
  private final Optional<Runnable> retry;
  private final Runnable enter, retire;
  private State displayed;
  private FormPanel diagnostics;
  private FormChrome chrome;
  private Object widgets;
  private boolean closed;

  public ClientStatusScreen(
      Screen parent,
      Component title,
      Supplier<State> state,
      Supplier<Screen> editor,
      Optional<Runnable> retry) {
    this(parent, title, state, editor, retry, () -> {}, () -> {});
  }

  public ClientStatusScreen(
      Screen parent,
      Component title,
      Supplier<State> state,
      Supplier<Screen> editor,
      Optional<Runnable> retry,
      Runnable enter,
      Runnable retire) {
    super(title);
    this.parent = parent;
    this.state = Objects.requireNonNull(state);
    this.editor = Objects.requireNonNull(editor);
    this.retry = Objects.requireNonNull(retry);
    this.enter = Objects.requireNonNull(enter);
    this.retire = Objects.requireNonNull(retire);
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.settings." + key, fallback);
  }

  public static Component reason(ValidationReport report, Component fallback) {
    return report.issues().isEmpty()
        ? fallback.copy()
        : Component.literal(
            report.issues().stream()
                .map(Issue::fallback)
                .distinct()
                .limit(4)
                .reduce((first, second) -> first + "\n" + second)
                .orElseThrow());
  }

  @Override
  protected void init() {
    enter.run();
    widgets = new Object();
    displayed = state.get();
    float scroll = diagnostics == null ? 0 : diagnostics.scroll();
    var actions = new ArrayList<Button>();
    var generation = widgets;
    if (displayed.canRetry() && retry.isPresent())
      actions.add(
          Button.builder(
                  text("retry", "Retry"),
                  ignored -> {
                    if (!closed
                        && NativeScreens.current(minecraft) == this
                        && widgets == generation
                        && state.get().canRetry()) retry.orElseThrow().run();
                  })
              .bounds(0, 0, Math.max(56, font.width(text("retry", "Retry")) + 20), 20)
              .build());
    actions.add(
        Button.builder(
                text("back", "Back"),
                ignored -> {
                  if (widgets == generation) onClose();
                })
            .bounds(0, 0, Math.max(56, font.width(text("back", "Back")) + 20), 20)
            .build());
    chrome =
        new FormChrome(
            minecraft, width, height, title, Component.empty(), Component.empty(), actions);
    diagnostics = chrome.panel();
    chrome.mountActions(this::addRenderableWidget, diagnostics);
    diagnostics.row(Component.empty(), message(displayed));
    diagnostics.finish(scroll);
    addRenderableWidget(diagnostics);
  }

  private Component message(State current) {
    return current.ready() ? text("opening_editor", "Opening editor…") : current.message();
  }

  @Override
  public void tick() {
    if (closed || NativeScreens.current(minecraft) != this) return;
    var current = state.get();
    if (current.ready()) NativeScreens.open(minecraft, editor.get());
    else if (!current.equals(displayed)) rebuildWidgets();
  }

  @Override
  public Component getNarrationMessage() {
    return title.copy().append(". ").append(message(state.get()));
  }

  @Override
  public void render(FormCanvas graphics, int x, int y, float delta) {
    super.render(graphics, x, y, delta);
    chrome.render(graphics);
  }

  @Override
  public void onClose() {
    if (closed || NativeScreens.current(minecraft) != this) return;
    closed = true;
    NativeScreens.open(minecraft, parent);
  }

  @Override
  public void removed() {
    closed = true;
    retire.run();
  }
}
