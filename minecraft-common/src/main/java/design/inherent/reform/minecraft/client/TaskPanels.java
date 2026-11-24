package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.SectionNavigation;
import design.inherent.reform.presentation.TaskPresentation;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;

/** One measured task, either adjacent scroll regions or a complete focused flow. No owner state. */
final class TaskPanels {
  /** Keep an auxiliary task's controls with its text even on an unusually wide native window. */
  static FormChrome readingChrome(
      Minecraft client,
      int width,
      int height,
      Component title,
      Component context,
      Component status,
      List<? extends AbstractWidget> actions,
      ClientTheme theme,
      TaskPresentation.Offer primary,
      TaskPresentation.Offer detail) {
    return readingChrome(
        client,
        new design.inherent.reform.presentation.FormGeometry.Placement(0, 0, width, height),
        title,
        context,
        status,
        actions,
        theme,
        primary,
        detail);
  }

  static FormChrome readingChrome(
      Minecraft client,
      design.inherent.reform.presentation.FormGeometry.Placement bounds,
      Component title,
      Component context,
      Component status,
      List<? extends AbstractWidget> actions,
      ClientTheme theme,
      TaskPresentation.Offer primary,
      TaskPresentation.Offer detail) {
    var values = theme.values();
    int padding =
        Math.multiplyExact(
            2, values.value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_SCREEN));
    int gap = values.value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT);
    int preferred =
        Math.max(
            Math.addExact(
                Math.multiplyExact(
                    client.font.width("M"),
                    values.value(
                        design.inherent.reform.presentation.ThemeValues.Role
                            .MEASURE_READING_COLUMNS)),
                padding),
            Math.addExact(
                Math.addExact(primary.preferredWidth(), detail.preferredWidth()),
                Math.addExact(gap, padding)));
    var host = design.inherent.reform.presentation.FormGeometry.centeredWidth(bounds, preferred);
    return new FormChrome(client, host, title, context, status, actions, theme);
  }

  enum Region {
    PRIMARY,
    DETAIL
  }

  record State(
      SectionNavigation.Bookmark primary,
      SectionNavigation.Bookmark detail,
      String focus,
      Region focused,
      Region viewport) {
    static State empty() {
      return new State(null, null, null, null, Region.PRIMARY);
    }
  }

  private final FormPanel primary, detail;
  private final TaskPresentation.Regions regions;
  private State state;

  TaskPanels(
      FormChrome chrome,
      TaskPresentation policy,
      TaskPresentation.Kind kind,
      TaskPresentation.Offer primaryOffer,
      TaskPresentation.Offer detailOffer,
      ClientTheme theme,
      State state) {
    this(
        chrome.contentBounds(),
        chrome.unifiedContentRequired(),
        chrome::panel,
        policy,
        kind,
        primaryOffer,
        detailOffer,
        theme,
        state);
  }

  TaskPanels(
      design.inherent.reform.presentation.FormGeometry.Placement bounds,
      boolean unified,
      TaskPresentation policy,
      TaskPresentation.Kind kind,
      TaskPresentation.Offer primaryOffer,
      TaskPresentation.Offer detailOffer,
      ClientTheme theme,
      State state) {
    this(
        bounds,
        unified,
        () ->
            new FormPanel(
                Minecraft.getInstance(),
                bounds.x(),
                bounds.y(),
                bounds.width(),
                bounds.height(),
                false,
                theme),
        policy,
        kind,
        primaryOffer,
        detailOffer,
        theme,
        state);
  }

  private TaskPanels(
      design.inherent.reform.presentation.FormGeometry.Placement bounds,
      boolean unified,
      java.util.function.Supplier<FormPanel> focused,
      TaskPresentation policy,
      TaskPresentation.Kind kind,
      TaskPresentation.Offer primaryOffer,
      TaskPresentation.Offer detailOffer,
      ClientTheme theme,
      State state) {
    this.state = state;
    var effective =
        unified ? policy.withPreference(kind, TaskPresentation.Preference.FOCUSED) : policy;
    regions =
        effective.arrange(
            kind,
            bounds,
            primaryOffer,
            Optional.of(detailOffer),
            theme
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT));
    if (regions.arrangement() == TaskPresentation.Arrangement.FOCUSED) {
      primary = detail = focused.get();
    } else {
      var first = regions.primary();
      var second = regions.detail().orElseThrow();
      primary =
          new FormPanel(
              Minecraft.getInstance(),
              first.x(),
              first.y(),
              first.width(),
              first.height(),
              false,
              theme);
      detail =
          new FormPanel(
              Minecraft.getInstance(),
              second.x(),
              second.y(),
              second.width(),
              second.height(),
              false,
              theme);
    }
  }

  FormPanel primary() {
    return primary;
  }

  FormPanel detail() {
    return detail;
  }

  TaskPresentation.Arrangement arrangement() {
    return regions.arrangement();
  }

  State remember(GuiEventListener focused) {
    var first = state.primary();
    var second = state.detail();
    Region viewport = state.viewport();
    if (primary != detail) {
      first = primary.bookmark();
      second = detail.bookmark();
    } else {
      var bookmark = primary.bookmark();
      if (bookmark != null) {
        viewport = bookmark.section().startsWith("detail:") ? Region.DETAIL : Region.PRIMARY;
        if (viewport == Region.DETAIL) second = bookmark;
        else first = bookmark;
      }
    }
    String key =
        focused == primary ? primary.focusKey() : focused == detail ? detail.focusKey() : null;
    Region region = key == null ? null : key.startsWith("detail:") ? Region.DETAIL : Region.PRIMARY;
    return state = new State(first, second, key, region, viewport);
  }

  /** Finish both real panels before resolving focus into the currently mounted child tree. */
  Optional<FormPanel> finish() {
    primary.finish(0);
    if (detail != primary) detail.finish(0);
    if (primary == detail) {
      var region = state.focused() == null ? state.viewport() : state.focused();
      primary.restore(region == Region.DETAIL ? state.detail() : state.primary());
    } else {
      primary.restore(state.primary());
      detail.restore(state.detail());
    }
    if (state.focus() == null) return Optional.empty();
    var target = state.focused() == Region.DETAIL ? detail : primary;
    target.restoreFocus(state.focus());
    return target.getFocused() == null ? Optional.empty() : Optional.of(target);
  }
}
