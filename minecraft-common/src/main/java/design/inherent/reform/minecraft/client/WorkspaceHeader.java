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
            w -> locationLayout(w).height()));
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
      List<Button> history,
      BooleanSupplier current,
      Consumer<List<Crumb>> overflow) {
    this.bounds = bounds;
    ensureCanvas();
    var location = at(HeaderComposition.Slot.LOCATION);
    if (location == null || trail.isEmpty()) return List.of();
    return locationControls(font, location, trail, history, current, overflow);
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
      List<Button> history,
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
            var controls =
                locationControls(
                    font,
                    new FormGeometry.Placement(
                        0, 0, panel.contentWidth(), locationLayout(panel.contentWidth()).height()),
                    trail,
                    history,
                    current,
                    overflow);
            panel.actions("workspace:location", controls.toArray(AbstractWidget[]::new));
            panel.keepTogether(history.get(0), history.get(1));
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
      if (i > 0) full.append(" > ");
      full.append(trail.get(i).title());
    }
    return full;
  }

  private FormGeometry.Controls locationLayout(int width) {
    return HeaderLayout.location(
        width,
        theme.values().value(ThemeValues.Role.CONTROL_HEIGHT),
        31,
        theme.values().value(ThemeValues.Role.CONTROL_MINIMUM_WIDTH),
        theme.values().value(ThemeValues.Role.SPACE_INLINE));
  }

  private List<AbstractWidget> locationControls(
      Font font,
      FormGeometry.Placement location,
      List<Crumb> trail,
      List<Button> history,
      BooleanSupplier current,
      Consumer<List<Crumb>> overflow) {
    if (history.size() != 2) throw new IllegalArgumentException("History needs Back and Forward");
    var geometry = locationLayout(location.width());
    var controls = new ArrayList<AbstractWidget>();
    for (int i = 0; i < history.size(); i++) {
      var at = geometry.placements().get(i);
      var control = history.get(i);
      control.setPosition(location.x() + at.x(), location.y() + at.y());
      control.setWidth(at.width());
      controls.add(control);
    }
    var at = geometry.placements().get(2);
    var path =
        new FormGeometry.Placement(
            location.x() + at.x(), location.y() + at.y(), at.width(), at.height());
    var full = locationText(trail);
    var separator = theme.text(ClientTheme.TextRole.SUPPORT, Component.literal(" > "));
    long total =
        trail.stream()
                .mapToLong(c -> font.width(theme.text(ClientTheme.TextRole.VALUE, c.title())))
                .sum()
            + (long) (trail.size() - 1) * font.width(separator);
    int x = path.x();
    if (total <= path.width()) {
      for (int i = 0; i < trail.size(); i++) {
        var crumb = trail.get(i);
        boolean leaf = i + 1 == trail.size();
        var prefix = i == 0 ? Component.empty() : separator;
        var control =
            new LocationText(
                font,
                crumb.title(),
                prefix,
                full,
                leaf,
                () -> {
                  if (!leaf && current.getAsBoolean()) crumb.selected().run();
                });
        control.setPosition(x, path.y());
        control.setWidth(
            font.width(theme.text(ClientTheme.TextRole.VALUE, crumb.title())) + font.width(prefix));
        controls.add(control);
        x += control.getWidth();
      }
    } else {
      var menu =
          new LocationText(
              font,
              Component.literal("…"),
              Component.empty(),
              full,
              false,
              () -> {
                if (current.getAsBoolean()) overflow.accept(trail);
              });
      menu.setMessage(Component.translatableWithFallback("reform.workspace.location", "Location"));
      menu.display = Component.literal("…");
      menu.setPosition(x, path.y());
      menu.setWidth(Math.min(path.width(), Math.max(20, font.width(menu.display))));
      controls.add(menu);
      int remaining = path.width() - menu.getWidth();
      if (remaining > 0) {
        var leaf = new LocationText(font, trail.getLast().title(), separator, full, true, () -> {});
        leaf.setPosition(x + menu.getWidth(), path.y());
        leaf.setWidth(remaining);
        controls.add(leaf);
      }
    }
    return List.copyOf(controls);
  }

  /** Text links retain native activation/focus; the current item is descriptive and inactive. */
  private final class LocationText extends NativeNavigationButton {
    private final Font font;
    private final Component prefix, full;
    private final boolean leaf;
    private Component display;

    LocationText(
        Font font,
        Component label,
        Component prefix,
        Component full,
        boolean leaf,
        Runnable selected) {
      super(label.copy(), 1, selected);
      this.font = font;
      this.prefix = prefix;
      this.full = full;
      this.leaf = leaf;
      display = label.copy();
      active = !leaf;
      setHeight(theme.values().value(ThemeValues.Role.CONTROL_HEIGHT));
      setTooltip(Tooltip.create(full, null));
    }

    @Override
    protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {
      return Component.translatableWithFallback(
              leaf ? "reform.workspace.current_location" : "reform.workspace.location",
              leaf ? "Current location" : "Location")
          .append(": ")
          .append(full);
    }

    @Override
    protected void renderNavigation(FormCanvas target, int mouseX, int mouseY, float delta) {
      int y = getY() + (getHeight() - font.lineHeight) / 2;
      int leading = font.width(prefix);
      target.enableScissor(getX(), getY(), getRight(), getBottom());
      try {
        theme.draw(target, font, ClientTheme.TextRole.SUPPORT, prefix, getX(), y, true);
        var label =
            theme
                .text(ClientTheme.TextRole.VALUE, display)
                .copy()
                .withStyle(style -> style.withUnderlined(leaf || isHoveredOrFocused()));
        target.drawArgb(
            font,
            label.getVisualOrderText(),
            getX() + leading,
            y,
            theme.values().value(leaf ? ThemeValues.Role.TEXT_ACTIVE : ThemeValues.Role.TEXT),
            true);
        if (isFocused()) {
          int color = theme.values().value(ThemeValues.Role.FOCUS);
          target.hLine(getX(), getRight() - 1, getY(), color);
          target.hLine(getX(), getRight() - 1, getBottom() - 1, color);
        }
      } finally {
        target.disableScissor();
      }
    }
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
