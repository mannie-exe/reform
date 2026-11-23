package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.ThemeValues;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

/** One native projection of measured screen regions; chrome never owns editing state. */
final class FormChrome {
  private final Minecraft client;
  private final int originX, originY;
  private final Component title, context, status;
  private final List<? extends AbstractWidget> actions;
  final FormGeometry.ScreenFrame frame;
  private final ClientTheme theme;

  FormChrome(
      Minecraft client,
      int width,
      int height,
      Component title,
      Component context,
      Component status,
      List<? extends AbstractWidget> actions) {
    this(client, new FormGeometry.Placement(0, 0, width, height), title, context, status, actions);
  }

  FormChrome(
      Minecraft client,
      int width,
      int height,
      Component title,
      Component context,
      Component status,
      List<? extends AbstractWidget> actions,
      ClientTheme theme) {
    this(
        client,
        new FormGeometry.Placement(0, 0, width, height),
        title,
        context,
        status,
        actions,
        theme);
  }

  FormChrome(
      Minecraft client,
      FormGeometry.Placement host,
      Component title,
      Component context,
      Component status,
      List<? extends AbstractWidget> actions) {
    this(client, host, title, context, status, actions, ClientTheme.standard());
  }

  FormChrome(
      Minecraft client,
      FormGeometry.Placement host,
      Component title,
      Component context,
      Component status,
      List<? extends AbstractWidget> actions,
      ClientTheme theme) {
    this.theme = java.util.Objects.requireNonNull(theme);
    int width = host.width(), height = host.height();
    originX = host.x();
    originY = host.y();
    this.client = client;
    this.title = theme.text(ClientTheme.TextRole.TITLE, title);
    this.context = theme.text(ClientTheme.TextRole.SUPPORT, context);
    this.status = theme.text(ClientTheme.TextRole.SUPPORT, status);
    this.actions = List.copyOf(actions);
    int gutter =
        Math.min(
            theme.values().value(ThemeValues.Role.SPACE_SCREEN), (Math.min(width, height) - 1) / 2);
    int available = Math.max(1, width - 2 * gutter);
    frame =
        FormGeometry.screenFrame(
            width,
            height,
            title.getString().isEmpty()
                ? 0
                : Math.max(
                    theme.values().value(ThemeValues.Role.MEASURE_TITLE_MINIMUM_HEIGHT),
                    textHeight(this.title, available)),
            textHeight(this.context, available),
            status.getString().isEmpty()
                ? 0
                : Math.max(
                    theme.values().value(ThemeValues.Role.MEASURE_STATUS_MINIMUM_HEIGHT),
                    Math.min(2 * client.font.lineHeight, textHeight(this.status, available))),
            actions.stream()
                .map(widget -> new FormGeometry.Size(widget.getWidth(), widget.getHeight()))
                .toList(),
            theme.values());
  }

  private int textHeight(Component text, int width) {
    return text.getString().isEmpty()
        ? 0
        : client.font.split(text, width).size() * client.font.lineHeight;
  }

  FormPanel panel() {
    var at = frame.content();
    var panel =
        new FormPanel(
            client, originX + at.x(), originY + at.y(), at.width(), at.height(), false, theme);
    if (frame.headerInContent()) {
      if (!title.getString().isEmpty()) panel.heading(title);
      if (!context.getString().isEmpty()) panel.row(Component.empty(), context);
    }
    return panel;
  }

  void mountActions(Consumer<AbstractWidget> mount, FormPanel panel) {
    if (frame.statusInContent()) panel.row(Component.empty(), status);
    if (frame.actionsInContent()) {
      panel.actions("screen:actions", actions.toArray(AbstractWidget[]::new));
      return;
    }
    if (frame.actions().isEmpty()) return;
    var region = frame.actions().orElseThrow();
    for (int index = 0; index < actions.size(); index++) {
      var widget = actions.get(index);
      var at = frame.actionFlow().placements().get(index);
      widget.setPosition(originX + region.x() + at.x(), originY + region.y() + at.y());
      ControlSizing.resize(widget, at.width());
      widget.setHeight(at.height());
      mount.accept(widget);
    }
  }

  void render(FormCanvas canvas) {
    render(canvas, status);
  }

  void alignTrailingAction() {
    if (frame.actions().isEmpty() || actions.isEmpty()) return;
    var at = frame.actions().orElseThrow();
    var last = actions.getLast();
    last.setX(originX + at.x() + at.width() - last.getWidth());
  }

  void render(FormCanvas canvas, Component currentStatus) {
    frame
        .title()
        .ifPresent(
            at -> {
              if (client.font.width(title) <= at.width())
                theme.draw(
                    canvas,
                    client.font,
                    ClientTheme.TextRole.TITLE,
                    title,
                    originX + at.x() + (at.width() - client.font.width(title)) / 2,
                    originY + at.y(),
                    true);
              else
                theme.wrap(
                    canvas,
                    client.font,
                    ClientTheme.TextRole.TITLE,
                    title,
                    originX + at.x(),
                    originY + at.y(),
                    at.width());
            });
    frame
        .context()
        .ifPresent(
            at ->
                theme.wrap(
                    canvas,
                    client.font,
                    ClientTheme.TextRole.SUPPORT,
                    context,
                    originX + at.x(),
                    originY + at.y(),
                    at.width()));
    frame
        .status()
        .ifPresent(
            at -> {
              var lines =
                  client.font.split(
                      theme.text(ClientTheme.TextRole.SUPPORT, currentStatus), at.width());
              for (int i = 0; i < Math.min(lines.size(), at.height() / client.font.lineHeight); i++)
                canvas.drawArgb(
                    client.font,
                    lines.get(i),
                    originX + at.x(),
                    originY + at.y() + i * client.font.lineHeight,
                    theme.color(ClientTheme.TextRole.SUPPORT),
                    true);
            });
  }
}
