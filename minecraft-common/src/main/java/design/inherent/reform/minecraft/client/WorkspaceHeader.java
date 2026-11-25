package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;

/** Stable authored header measurement, independent of the currently selected trail's length. */
final class WorkspaceHeader implements AutoCloseable {
  record Crumb(Component title, Runnable selected) {
    Crumb {
      title = title.copy();
      Objects.requireNonNull(selected);
    }
  }

  private final ClientWorkspacePresentation presentation;
  private final ClientTheme theme;
  private ClientHeaderCanvas canvas;
  private OwnerContext canvasOwner;
  private Throwable canvasFailure;
  private boolean closed;
  private HeaderLayout.Layout<HeaderComposition.Slot> measured;
  private List<net.minecraft.util.FormattedCharSequence> titleLines = List.of(),
      subtitleLines = List.of();
  private FormGeometry.Placement bounds;

  WorkspaceHeader(ClientWorkspacePresentation presentation, ClientTheme theme) {
    this.presentation = presentation;
    this.theme = Objects.requireNonNull(theme);
  }

  int measure(Font font, int width) {
    var roles = theme.values();
    var offers =
        new EnumMap<HeaderComposition.Slot, HeaderLayout.Offer>(HeaderComposition.Slot.class);
    offers.put(
        HeaderComposition.Slot.IDENTITY,
        new HeaderLayout.Offer(
            Math.min(roles.value(ThemeValues.Role.MEASURE_TEXT_MINIMUM), width),
            Math.max(
                roles.value(ThemeValues.Role.MEASURE_TEXT_MINIMUM),
                Math.min(
                    roles.value(ThemeValues.Role.MEASURE_TEXT_PREFERRED_LIMIT),
                    font.width(
                        theme.text(ClientTheme.TextRole.TITLE, presentation.identity().title())))),
            w ->
                Math.max(
                    font.lineHeight,
                    font.split(
                                theme.text(
                                    ClientTheme.TextRole.TITLE, presentation.identity().title()),
                                w)
                            .size()
                        * font.lineHeight)));
    offers.put(
        HeaderComposition.Slot.LOCATION,
        new HeaderLayout.Offer(
            roles.value(ThemeValues.Role.CONTROL_MINIMUM_WIDTH),
            roles.value(ThemeValues.Role.MEASURE_INFORMATION_PREFERRED),
            w -> roles.value(ThemeValues.Role.CONTROL_HEIGHT)));
    if (!theme
        .text(ClientTheme.TextRole.SUPPORT, presentation.identity().subtitle())
        .getString()
        .isEmpty())
      offers.put(
          HeaderComposition.Slot.SUBTITLE,
          new HeaderLayout.Offer(
              1,
              roles.value(ThemeValues.Role.MEASURE_TEXT_PREFERRED_LIMIT),
              w ->
                  Math.max(
                      font.lineHeight,
                      font.split(
                                  theme.text(
                                      ClientTheme.TextRole.SUPPORT,
                                      presentation.identity().subtitle()),
                                  w)
                              .size()
                          * font.lineHeight)));
    presentation
        .header()
        .canvas()
        .ifPresent(
            c ->
                offers.put(
                    HeaderComposition.Slot.CANVAS,
                    new HeaderLayout.Offer(c.minimumWidth(), c.preferredWidth(), w -> c.height())));
    measured =
        HeaderLayout.measure(
            presentation.header().rows().stream().map(HeaderComposition.Row::slots).toList(),
            offers,
            width,
            roles.value(ThemeValues.Role.SPACE_TEXT));
    titleLines =
        font.split(
            theme.text(ClientTheme.TextRole.TITLE, presentation.identity().title()),
            measured.slots().get(HeaderComposition.Slot.IDENTITY).width());
    var sub = measured.slots().get(HeaderComposition.Slot.SUBTITLE);
    subtitleLines =
        sub == null
            ? List.of()
            : font.split(
                theme.text(ClientTheme.TextRole.SUPPORT, presentation.identity().subtitle()),
                sub.width());
    return measured.height();
  }

  private FormGeometry.Placement at(HeaderComposition.Slot slot) {
    var p = measured.slots().get(slot);
    return p == null
        ? null
        : new FormGeometry.Placement(bounds.x() + p.x(), bounds.y() + p.y(), p.width(), p.height());
  }

  List<AbstractWidget> mount(
      Font font,
      FormGeometry.Placement bounds,
      List<Crumb> trail,
      BooleanSupplier current,
      Consumer<List<Crumb>> overflow) {
    this.bounds = bounds;
    ensureCanvas();
    var location = at(HeaderComposition.Slot.LOCATION);
    if (location == null || trail.isEmpty()) return List.of();
    return locationButtons(font, location, trail, current, overflow);
  }

  private void ensureCanvas() {
    if (!closed
        && canvas == null
        && canvasFailure == null
        && presentation.header().canvas().isPresent())
      try {
        var client = net.minecraft.client.Minecraft.getInstance();
        var owner =
            new OwnerContext(
                client::execute,
                ClientSettingsRuntime.workers(),
                ClientSettingsRuntime::requireClientThread,
                OwnerContext.Limits.STANDARD);
        canvasOwner = owner;
        // Retire the returned view before the resource hooks registered during its construction.
        owner.onClose(
            () -> {
              var returned = canvas;
              canvas = null;
              if (returned != null) returned.close();
            });
        var created =
            Objects.requireNonNull(
                presentation
                    .header()
                    .canvas()
                    .orElseThrow()
                    .create()
                    .create(new ClientViewContext(owner)));
        if (!owner.isOpen() || closed) {
          created.close();
          throw new IllegalStateException("The header closed during canvas construction");
        }
        canvas = created;
      } catch (RuntimeException | Error failed) {
        canvasFailure = failed;
        try {
          retireCanvas();
        } catch (RuntimeException | Error cleanup) {
          if (cleanup != failed) failed.addSuppressed(cleanup);
        }
        if (failed instanceof Error fatal) throw fatal;
      }
  }

  /**
   * When fixed chrome cannot fit, its complete authored reading order enters the scrolling tree.
   */
  void appendTo(
      FormPanel panel,
      Font font,
      List<Crumb> trail,
      BooleanSupplier current,
      Consumer<List<Crumb>> overflow) {
    bounds = null;
    ensureCanvas();
    for (var row : presentation.header().rows())
      for (var slot : row.slots())
        switch (slot) {
          case IDENTITY ->
              panel.heading(
                  theme.text(ClientTheme.TextRole.TITLE, presentation.identity().title()));
          case SUBTITLE -> {
            var subtitle =
                theme.text(ClientTheme.TextRole.SUPPORT, presentation.identity().subtitle());
            if (!subtitle.getString().isEmpty()) panel.row(Component.empty(), subtitle);
          }
          case LOCATION -> {
            if (trail.isEmpty()) continue;
            var location = locationText(trail);
            var button =
                theme
                    .buttonBuilder(
                        trail.getLast().title(),
                        ignored -> {
                          if (current.getAsBoolean()) overflow.accept(trail);
                        })
                    .bounds(
                        0,
                        0,
                        theme.values().value(ThemeValues.Role.CONTROL_MINIMUM_WIDTH),
                        theme.values().value(ThemeValues.Role.CONTROL_HEIGHT))
                    .createNarration(
                        original ->
                            Component.translatableWithFallback(
                                    "reform.workspace.location", "Location")
                                .append(": ")
                                .append(location))
                    .build();
            // The custom narration already contains this complete path.
            button.setTooltip(Tooltip.create(location, null));
            panel.actions("workspace:location", button);
          }
          case CANVAS -> {
            var offer = presentation.header().canvas().orElseThrow();
            var widget =
                new NativeGraphicWidget(offer.description()) {
                  @Override
                  protected void drawGraphic(FormCanvas target) {
                    drawCanvas(
                        target,
                        font,
                        new FormGeometry.Placement(getX(), getY(), getWidth(), getHeight()));
                  }
                };
            widget.setWidth(offer.preferredWidth());
            widget.setHeight(offer.height());
            panel.row("workspace:canvas", Component.empty(), offer.description(), widget);
          }
        }
  }

  private static Component locationText(List<Crumb> trail) {
    var full = Component.empty();
    for (int i = 0; i < trail.size(); i++) {
      if (i > 0) full.append(" / ");
      full.append(trail.get(i).title());
    }
    return full;
  }

  private List<AbstractWidget> locationButtons(
      Font font,
      FormGeometry.Placement location,
      List<Crumb> trail,
      BooleanSupplier current,
      Consumer<List<Crumb>> overflow) {
    var full = locationText(trail);
    var buttons = new ArrayList<AbstractWidget>();
    int gap = theme.values().value(ThemeValues.Role.SPACE_INLINE);
    int total =
        trail.stream().mapToInt(c -> Math.max(32, font.width(c.title()) + 20)).sum()
            + gap * (trail.size() - 1);
    int x = location.x();
    if (total <= location.width()) {
      for (int index = 0; index < trail.size(); index++) {
        var crumb = trail.get(index);
        boolean leaf = index + 1 == trail.size();
        var label = crumb.title().copy();
        var button =
            theme
                .buttonBuilder(
                    label,
                    ignored -> {
                      if (current.getAsBoolean()) crumb.selected().run();
                    })
                .bounds(x, location.y(), Math.max(32, font.width(label) + 20), location.height())
                .createNarration(
                    original ->
                        Component.translatableWithFallback(
                                leaf
                                    ? "reform.workspace.current_location"
                                    : "reform.workspace.location",
                                leaf ? "Current location" : "Location")
                            .append(": ")
                            .append(full))
                .build();
        button.active = !leaf;
        button.setTooltip(Tooltip.create(full, null));
        buttons.add(button);
        x += button.getWidth() + gap;
      }
    } else {
      var leaf = trail.getLast().title();
      var button =
          theme
              .buttonBuilder(
                  leaf,
                  ignored -> {
                    if (current.getAsBoolean()) overflow.accept(trail);
                  })
              .bounds(x, location.y(), location.width(), location.height())
              .createNarration(
                  original ->
                      Component.translatableWithFallback("reform.workspace.location", "Location")
                          .append(": ")
                          .append(full))
              .build();
      button.setTooltip(Tooltip.create(full, null));
      buttons.add(button);
    }
    return List.copyOf(buttons);
  }

  void render(FormCanvas target, Font font) {
    if (bounds == null) return;
    var values = theme.values();
    var identity = at(HeaderComposition.Slot.IDENTITY);
    int color = values.value(ThemeValues.Role.TEXT);
    for (int i = 0; i < titleLines.size(); i++)
      target.drawArgb(
          font, titleLines.get(i), identity.x(), identity.y() + i * font.lineHeight, color, true);
    var sub = at(HeaderComposition.Slot.SUBTITLE);
    for (int i = 0; sub != null && i < subtitleLines.size(); i++)
      target.drawArgb(
          font,
          subtitleLines.get(i),
          sub.x(),
          sub.y() + i * font.lineHeight,
          values.value(ThemeValues.Role.TEXT_SUPPORTING),
          true);
    var art = at(HeaderComposition.Slot.CANVAS);
    if (art != null) drawCanvas(target, font, art);
  }

  private void drawCanvas(FormCanvas target, Font font, FormGeometry.Placement art) {
    if (closed) return;
    if (canvas != null && canvasFailure == null) {
      target.enableScissor(art.x(), art.y(), art.x() + art.width(), art.y() + art.height());
      try {
        canvas.render(target, font, art, theme.values());
      } catch (RuntimeException | Error failed) {
        canvasFailure = failed;
        try {
          retireCanvas();
        } catch (RuntimeException | Error cleanup) {
          if (cleanup != failed) failed.addSuppressed(cleanup);
        }
        if (failed instanceof Error fatal) throw fatal;
      } finally {
        target.disableScissor();
      }
    }
    if (canvasFailure != null) {
      var message =
          Component.translatableWithFallback(
              "reform.workspace.canvas_failed",
              "Header artwork could not be displayed. Settings remain available.");
      var lines = font.split(message, art.width());
      for (int i = 0; i < Math.min(lines.size(), Math.max(1, art.height() / font.lineHeight)); i++)
        target.drawArgb(
            font,
            lines.get(i),
            art.x(),
            art.y() + i * font.lineHeight,
            theme.values().value(ThemeValues.Role.TEXT_ERROR),
            true);
    }
  }

  Optional<Throwable> failure() {
    return Optional.ofNullable(canvasFailure);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retireCanvas();
  }

  private void retireCanvas() {
    var owner = canvasOwner;
    canvasOwner = null;
    if (owner != null) owner.close();
  }
}
