package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.SectionNavigation;
import design.inherent.reform.presentation.TaskPresentation;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;

/** One measured task, either adjacent scroll regions or a complete focused flow. No owner state. */
final class TaskPanels {
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
    this.state = state;
    var effective =
        chrome.unifiedContentRequired()
            ? policy.withPreference(kind, TaskPresentation.Preference.FOCUSED)
            : policy;
    regions =
        effective.arrange(
            kind,
            chrome.contentBounds(),
            primaryOffer,
            Optional.of(detailOffer),
            theme
                .values()
                .value(design.inherent.reform.presentation.ThemeValues.Role.SPACE_CONTENT));
    if (regions.arrangement() == TaskPresentation.Arrangement.FOCUSED) {
      primary = detail = chrome.panel();
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
