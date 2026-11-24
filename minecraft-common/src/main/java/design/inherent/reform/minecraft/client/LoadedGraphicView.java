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
    var cancel = button("cancel", "Cancel preparation", view::cancel, theme, guard);
    cancel.active =
        observed.state() == ClientGraphicHost.State.PREPARING
            || observed.state() == ClientGraphicHost.State.QUEUED;
    panel.actions(
        prefix + ":prepare",
        button(
            "prepare_loaded",
            "Prepare loaded image",
            () -> view.request(spec.subject()),
            theme,
            guard),
        cancel);
    int size =
        theme
            .values()
            .value(design.inherent.reform.presentation.ThemeValues.Role.MEASURE_GRAPHIC_PREFERRED);
    ControlSizing.offer(widget, size, Math.min(size, panel.visibleRowHeight()));
    panel.anchorRow(prefix + ":image", Component.empty(), Component.empty(), widget);
    panel.actions(
        prefix + ":camera",
        cameraButton("rotate_left", "Rotate left", () -> view.orbit(-15, 0), theme, guard),
        cameraButton("rotate_right", "Rotate right", () -> view.orbit(15, 0), theme, guard),
        cameraButton("zoom_in", "Zoom in", () -> view.zoom(1.2), theme, guard),
        cameraButton("zoom_out", "Zoom out", () -> view.zoom(1 / 1.2), theme, guard),
        cameraButton("reset_camera", "Reset view", view::resetCamera, theme, guard),
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
    if (details) {
      panel.row(prefix + ":state", text("state", "Status"), stateText(observed.state()));
      view.displayedStamp()
          .ifPresent(
              stamp -> {
                panel.row(
                    prefix + ":provenance",
                    text("provenance", "Prepared image"),
                    text(
                        observed.state() == ClientGraphicHost.State.READY
                            ? "current_image"
                            : "previous_image",
                        observed.state() == ClientGraphicHost.State.READY
                            ? "Current image"
                            : "Earlier image; not a current result"));
                panel.row(
                    prefix + ":source",
                    text("source", "Source"),
                    Component.literal(stamp.kind().name()));
                panel.row(
                    prefix + ":provider",
                    text("provider", "Provider"),
                    Component.literal(stamp.provider().toString()));
                panel.row(
                    prefix + ":request",
                    text("request", "Request"),
                    Component.literal(stamp.request().toString()));
                panel.row(
                    prefix + ":context",
                    text("context", "Context"),
                    Component.literal(stamp.context().toString()));
                panel.row(
                    prefix + ":resources",
                    text("resources", "Resource snapshot"),
                    Component.literal(
                        stamp.resources().source()
                            + " / "
                            + stamp.resources().snapshot()
                            + " / "
                            + stamp.resources().revision()));
              });
      observed
          .operation()
          .ifPresent(
              operation ->
                  panel.row(
                      prefix + ":operation",
                      text("operation", "Preparation"),
                      Component.literal(operation.id() + " / " + operation.state())));
      view.failure()
          .ifPresent(
              failed -> {
                String cause =
                    failed.getClass().getSimpleName() + ": " + String.valueOf(failed.getMessage());
                panel.row(
                    prefix + ":cause",
                    text("cause", "Cause"),
                    Component.literal(cause.substring(0, Math.min(cause.length(), 1024))));
              });
      for (var fact : view.facts())
        panel.row(
            prefix + ":fact:" + fact.id(),
            Component.literal(fact.title()),
            Component.literal(fact.value()));
    }
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

  private static Component stateText(ClientGraphicHost.State state) {
    String fallback =
        switch (state) {
          case IDLE -> "No image prepared";
          case QUEUED -> "Waiting for earlier preparation";
          case PREPARING -> "Preparing image";
          case CANCELLING -> "Waiting for cancellation to finish";
          case READY -> "Image ready";
          case STALE -> "Image is out of date; prepare it again";
          case CLOSED -> "Image closed";
          case CANCELLED -> "Preparation cancelled";
          case REFUSED -> "Preparation refused";
          case FAILED -> "Preparation failed";
          case UNAVAILABLE -> "Image unavailable";
        };
    return text("state_" + state.name().toLowerCase(java.util.Locale.ROOT), fallback);
  }

  private AbstractWidget cameraButton(
      String key,
      String fallback,
      Runnable action,
      ClientTheme theme,
      Function<Runnable, Runnable> guard) {
    var control = button(key, fallback, action, theme, guard);
    control.active = observed.state() == ClientGraphicHost.State.READY;
    return control;
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
