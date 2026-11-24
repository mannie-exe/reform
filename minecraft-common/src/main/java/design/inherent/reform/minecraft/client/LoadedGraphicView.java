package design.inherent.reform.minecraft.client;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/**
 * Shared loaded-subject controls over the same workflow host, measured panel, and native widget.
 */
final class LoadedGraphicView<S> implements AutoCloseable {
  private final ClientViewContent.LoadedGraphic<S> spec;
  private final ClientGraphicHost.View<Void, S> view;
  private final AbstractWidget widget;
  private ClientGraphicHost.Snapshot observed;
  private FormPanel panel;
  private boolean details, show;

  LoadedGraphicView(ClientViewContent.LoadedGraphic<S> spec, ClientGraphicHost host) {
    this.spec = spec;
    view = host.openLoaded(spec.provider());
    widget = view.widget(spec.title());
  }

  boolean matches(ClientViewContent.LoadedGraphic<?> other) {
    return spec.equals(other);
  }

  boolean changed() {
    return !Objects.equals(observed, view.snapshot());
  }

  void hide() {
    show = false;
    panel = null;
    view.visible(false);
  }

  void append(
      FormPanel panel, ClientTheme theme, Function<Runnable, Runnable> guard, Runnable rebuild) {
    this.panel = panel;
    view.visible(true);
    observed = view.snapshot();
    String prefix = "custom:" + spec.id();
    panel.beginGroup(prefix, spec.title());
    if (!spec.help().getString().isBlank())
      panel.row(prefix + ":help", Component.empty(), spec.help());
    panel.actions(
        prefix + ":prepare",
        button(
            "prepare_loaded",
            "Prepare loaded image",
            () -> view.request(spec.subject()),
            theme,
            guard),
        button("cancel", "Cancel preparation", view::cancel, theme, guard));
    int size =
        theme
            .values()
            .value(design.inherent.reform.presentation.ThemeValues.Role.MEASURE_GRAPHIC_PREFERRED);
    ControlSizing.offer(widget, size, Math.min(size, panel.visibleRowHeight()));
    panel.anchorRow(prefix + ":image", Component.empty(), Component.empty(), widget);
    panel.actions(
        prefix + ":camera",
        button("rotate_left", "Rotate left", () -> view.orbit(-15, 0), theme, guard),
        button("rotate_right", "Rotate right", () -> view.orbit(15, 0), theme, guard),
        button("zoom_in", "Zoom in", () -> view.zoom(1.2), theme, guard),
        button("zoom_out", "Zoom out", () -> view.zoom(1 / 1.2), theme, guard),
        button("reset_camera", "Reset view", view::resetCamera, theme, guard),
        button("show_loaded", "Show loaded image", () -> show = true, theme, guard));
    panel.actions(
        prefix + ":details",
        button(
            details ? "hide_details" : "details",
            details ? "Hide image details" : "Image details",
            () -> {
              details = !details;
              rebuild.run();
            },
            theme,
            guard));
    if (details)
      for (var fact : view.facts())
        panel.row(
            prefix + ":fact:" + fact.id(),
            Component.literal(fact.title()),
            Component.literal(fact.value()));
    if (!observed.report().issues().isEmpty())
      panel.row(
          prefix + ":status",
          Component.empty(),
          ClientStatusScreen.reason(observed.report(), text("unavailable", "Image unavailable")));
    panel.endGroup();
  }

  void focus(Consumer<FormPanel> focus) {
    if (show && panel != null) {
      show = false;
      focus.accept(panel);
      panel.focusRow("custom:" + spec.id() + ":image");
    }
  }

  private static AbstractWidget button(
      String key,
      String fallback,
      Runnable action,
      ClientTheme theme,
      Function<Runnable, Runnable> guard) {
    return ClientControls.action(
            Minecraft.getInstance().font, text(key, fallback), guard.apply(action), theme)
        .build();
  }

  private static Component text(String key, String fallback) {
    return Component.translatableWithFallback("reform.loaded_graphic." + key, fallback);
  }

  public void close() {
    view.close();
  }
}
