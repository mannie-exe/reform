package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormTokens;
import design.inherent.reform.presentation.SectionNavigation;
import design.inherent.reform.presentation.ThemeValues;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/** Measured configuration rows; native widgets retain their own keyboard and narration behavior. */
final class FormPanel extends ScrollFrame {
  private static final int VIEWPORT_BORDER = 6, SCROLLBAR_GUTTER = 8;
  static final int ACTIVE_TEXT = FormTokens.Color.TEXT_ACTIVE;
  static final int TITLE_TEXT = FormTokens.Color.TEXT;
  static final int ERROR_TEXT = FormTokens.Color.TEXT_ERROR;
  static final int DESCRIPTION_TEXT = FormTokens.Color.TEXT_SUPPORTING;

  static NavigationButton navigationButton(
      Component label, int width, boolean category, boolean selected, Runnable action) {
    return new NavigationButton(label, width, category, selected, action);
  }

  static NavigationButton subsectionButton(Component label, int width, Runnable action) {
    var button = new NavigationButton(label, width, false, false, action);
    button.indent = 24;
    return button;
  }

  static NavigationButton sectionButton(Component label, int width, Runnable action) {
    var button = new NavigationButton(label, width, true, false, action);
    button.indent = 18;
    return button;
  }

  static final class NavigationButton extends NativeNavigationButton {
    private final boolean category;
    private boolean selected;
    private int indent;
    private int markerWidth;
    private Boolean expanded;
    private Component context = Component.empty();
    private ClientTheme theme = ClientTheme.standard();

    void theme(ClientTheme theme) {
      this.theme = theme;
      setMessage(
          theme.text(
              category ? ClientTheme.TextRole.HEADING : ClientTheme.TextRole.LABEL, getMessage()));
    }

    NavigationButton(
        Component label, int width, boolean category, boolean selected, Runnable action) {
      super(label, width, action);
      this.category = category;
      this.selected = selected;
      indent = category ? 4 : 12;
    }

    int labelWidth() {
      return Math.max(1, getWidth() - indent - markerWidth - 4);
    }

    void treeRow(int leading, Boolean expanded) {
      indent = 4 + leading;
      markerWidth = 12;
      this.expanded = expanded;
    }

    void selected(boolean value) {
      selected = value;
    }

    void context(Component value) {
      context = value.copy();
    }

    @Override
    protected void renderNavigation(FormCanvas graphics, int mx, int my, float tick) {
      if (selected) {
        graphics.fill(
            getX(),
            getY(),
            getX() + width,
            getY() + height,
            theme.values().value(ThemeValues.Role.LINE_SELECTED));
        graphics.fill(
            getX() + 1,
            getY() + 1,
            getX() + width - 1,
            getY() + height - 1,
            theme.values().value(ThemeValues.Role.SURFACE_SELECTED));
      } else if (isHoveredOrFocused())
        graphics.fill(
            getX(),
            getY(),
            getX() + width,
            getY() + height,
            theme.values().value(ThemeValues.Role.SURFACE_HOVER));
      renderString(
          graphics,
          Minecraft.getInstance().font,
          theme.color(
              selected
                  ? ClientTheme.TextRole.ACTIVE
                  : category ? ClientTheme.TextRole.HEADING : ClientTheme.TextRole.LABEL));
      if (isFocused()) {
        int edge = theme.values().value(ThemeValues.Role.FOCUS);
        graphics.fill(getX(), getY(), getX() + width, getY() + 2, edge);
        graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, edge);
        graphics.fill(getX(), getY(), getX() + 2, getY() + height, edge);
        graphics.fill(getX() + width - 2, getY(), getX() + width, getY() + height, edge);
      }
    }

    public void renderString(FormCanvas graphics, net.minecraft.client.gui.Font font, int color) {
      var lines = font.split(getMessage(), labelWidth());
      int y = getY() + (getHeight() - lines.size() * font.lineHeight) / 2;
      if (expanded != null)
        graphics.drawArgb(
            font,
            Component.literal(expanded ? "−" : "+").getVisualOrderText(),
            getX() + indent,
            y,
            color,
            true);
      for (var line : lines) {
        graphics.drawArgb(font, line, getX() + indent + markerWidth, y, color, true);
        y += font.lineHeight;
      }
    }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
      super.updateWidgetNarration(output);
      if (!context.getString().isEmpty()) output.add(NarratedElementType.HINT, context);
      var position = Component.empty();
      if (expanded != null)
        position.append(
            expanded
                ? Component.translatableWithFallback("reform.settings.section_expanded", "Expanded")
                : Component.translatableWithFallback(
                    "reform.settings.section_collapsed", "Collapsed"));
      if (selected) {
        if (!position.getString().isEmpty()) position.append(", ");
        position.append(
            Component.translatableWithFallback(
                "reform.settings.selected_section", "Selected section"));
      }
      if (!position.getString().isEmpty()) output.add(NarratedElementType.POSITION, position);
    }
  }

  private record Row(
      String key,
      Component label,
      Component summary,
      List<AbstractWidget> controls,
      boolean heading,
      String anchor,
      boolean field,
      int boundary,
      String relationship,
      List<FormGeometry.Size> preferred,
      ClientTheme theme,
      ClientTheme.TextRole labelRole) {}

  private Row row(
      String key,
      Component label,
      Component summary,
      List<AbstractWidget> controls,
      boolean heading,
      String anchor,
      boolean field,
      int boundary,
      String relationship) {
    return row(
        key,
        label,
        summary,
        controls,
        heading,
        anchor,
        field,
        boundary,
        relationship,
        heading ? ClientTheme.TextRole.HEADING : ClientTheme.TextRole.LABEL);
  }

  private Row row(
      String key,
      Component label,
      Component summary,
      List<AbstractWidget> controls,
      boolean heading,
      String anchor,
      boolean field,
      int boundary,
      String relationship,
      ClientTheme.TextRole labelRole) {
    for (var control : controls) {
      if (control instanceof NativeGraphicWidget graphic) graphic.theme(currentTheme);
      else ClientControls.theme(control, currentTheme);
      ControlSizing.protect(control);
      if (control instanceof NavigationButton navigationButton)
        navigationButton.theme(currentTheme);
      else if (control instanceof InformationButton information) information.theme(currentTheme);
    }
    return new Row(
        key,
        currentTheme.text(labelRole, label),
        currentTheme.text(ClientTheme.TextRole.SUPPORT, summary),
        controls,
        heading,
        anchor,
        field,
        boundary,
        relationship,
        controls.stream().map(ControlSizing::preferred).toList(),
        currentTheme,
        labelRole);
  }

  private record Positioned(AbstractWidget widget, int x, int y, String key) {}

  private record Layout(
      Row row, int x, int y, int height, int textWidth, int textY, int summaryY, boolean stacked) {}

  private record Outline(
      int left, int width, int top, int bottom, int labelWidth, ClientTheme theme) {}

  private record Frame(int left, int width, int top, int labelWidth, ClientTheme theme) {}

  private final List<Outline> outlines = new ArrayList<>();
  private String rememberedFocus;

  void beginGroup(String id, Component label) {
    rows.add(row(id, label, Component.empty(), List.of(), true, id, false, 1, null));
  }

  void endGroup() {
    rows.add(
        row(null, Component.empty(), Component.empty(), List.of(), true, null, false, -1, null));
  }

  private final List<Row> rows = new ArrayList<>();
  private final java.util.Set<AbstractWidget> joinedControls =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private final List<Positioned> positions = new ArrayList<>();
  private final List<Layout> layout = new ArrayList<>();
  private final Minecraft client;
  private final boolean navigation;
  private final ClientTheme baseTheme;
  private ClientTheme currentTheme;
  private int extent;
  private boolean finished;
  private String highlighted;
  private SectionNavigation sections = new SectionNavigation();

  FormPanel(Minecraft client, int x, int y, int width, int height, boolean navigation) {
    this(client, x, y, width, height, navigation, ClientTheme.standard());
  }

  FormPanel(
      Minecraft client,
      int x,
      int y,
      int width,
      int height,
      boolean navigation,
      ClientTheme theme) {
    super(width, height, y, x, VIEWPORT_BORDER);
    baseTheme = currentTheme = java.util.Objects.requireNonNull(theme);
    this.client = client;
    this.navigation = navigation;
  }

  ClientTheme theme() {
    return currentTheme;
  }

  void scoped(ClientTheme theme, Runnable append) {
    var before = currentTheme;
    currentTheme = java.util.Objects.requireNonNull(theme);
    try {
      append.run();
    } finally {
      currentTheme = before;
    }
  }

  void heading(Component label) {
    rows.add(
        row(
            null,
            label.copy().withStyle(net.minecraft.ChatFormatting.BOLD),
            Component.empty(),
            List.of(),
            true,
            null,
            false,
            0,
            null,
            ClientTheme.TextRole.TITLE));
  }

  void eyebrow(Component label) {
    rows.add(
        row(
            null,
            label.copy(),
            Component.empty(),
            List.of(),
            true,
            null,
            false,
            0,
            null,
            ClientTheme.TextRole.ACTIVE));
  }

  void section(String id, Component label) {
    rows.add(row(null, label, Component.empty(), List.of(), true, id, false, 0, null));
  }

  void anchor(String id) {
    rows.add(row(null, Component.empty(), Component.empty(), List.of(), false, id, false, 0, null));
  }

  void row(Component label, Component summary, AbstractWidget... controls) {
    row(null, label, summary, controls);
  }

  void row(String key, Component label, Component summary, AbstractWidget... controls) {
    rows.add(row(key, label, summary, List.of(controls), false, null, false, 0, null));
  }

  void anchorRow(String key, Component label, Component summary, AbstractWidget... controls) {
    rows.add(row(key, label, summary, List.of(controls), false, key, false, 0, null));
  }

  void field(String key, Component label, Component summary, AbstractWidget... controls) {
    relatedField(key, null, label, summary, controls);
  }

  void relatedField(
      String key,
      String relationship,
      Component label,
      Component summary,
      AbstractWidget... controls) {
    rows.add(row(key, label, summary, List.of(controls), false, key, true, 0, relationship));
  }

  /** A measured flow of actual buttons, without repeating their labels beside them. */
  void actions(String key, AbstractWidget... controls) {
    row(key, Component.empty(), Component.empty(), controls);
  }

  void keepTogether(AbstractWidget first, AbstractWidget second) {
    if (rows.stream()
        .noneMatch(
            row -> {
              int index = row.controls.indexOf(first);
              return index >= 0
                  && index + 1 < row.controls.size()
                  && row.controls.get(index + 1) == second;
            }))
      throw new IllegalArgumentException("Joined native controls must be adjacent in one row");
    joinedControls.add(first);
  }

  private java.util.Set<Integer> joins(Row row) {
    var joins = new java.util.HashSet<Integer>();
    for (int i = 0; i + 1 < row.controls.size(); i++)
      if (joinedControls.contains(row.controls.get(i))) joins.add(i);
    return joins;
  }

  void summary(String key, Component summary) {
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      if (key.equals(row.key)) {
        var nextSummary = row.theme.text(ClientTheme.TextRole.SUPPORT, summary);
        if (row.summary.equals(nextSummary)) return;
        rows.set(
            i,
            new Row(
                row.key,
                row.label,
                nextSummary,
                row.controls,
                row.heading,
                row.anchor,
                row.field,
                row.boundary,
                row.relationship,
                row.preferred,
                row.theme,
                row.labelRole));
        // Initial reference/dependency summaries can change while groups are still appended.
        // finish() measures the complete tree and retains its group-balance checks.
        if (!finished) return;
        var bookmark = bookmark();
        measure();
        restore(bookmark);
        return;
      }
    }
  }

  void finish(float scroll) {
    measure();
    finished = true;
    scrollDistance = Math.clamp(scroll, 0, Math.max(0, extent - height + border));
    position();
  }

  float scroll() {
    return scrollDistance;
  }

  /** The completed rows plus their actual viewport border; no assumed screen height. */
  int naturalHeight() {
    if (!finished) throw new IllegalStateException("Finish the panel before measuring its offer");
    return Math.addExact(extent, border);
  }

  boolean isNavigation() {
    return navigation;
  }

  void highlight(String id) {
    highlighted = id;
  }

  String highlighted() {
    return highlighted;
  }

  private float maximumScroll() {
    return Math.max(0, extent - height + border);
  }

  void jump(String section) {
    scrollDistance = sections.jump(section, maximumScroll());
    position();
  }

  boolean focusRow(String id) {
    if (!finished) return false;
    for (var row : rows)
      if (java.util.Objects.equals(row.key, id) && row.boundary == 0) {
        jump(id);
        focusFirst(row.controls);
        highlight(id);
        return true;
      }
    return false;
  }

  boolean focusGroup(String id) {
    int first = -1;
    for (int index = 0; index < rows.size(); index++)
      if (rows.get(index).boundary == 1 && java.util.Objects.equals(rows.get(index).key, id)) {
        first = index;
        break;
      }
    if (first < 0 || !finished) return false;
    jump(id);
    var owned = new ArrayList<AbstractWidget>();
    int depth = 1;
    for (int index = first + 1; index < rows.size(); index++) {
      var row = rows.get(index);
      depth += row.boundary;
      if (depth == 0) break;
      owned.addAll(row.controls);
    }
    focusFirst(owned);
    highlight(id);
    return true;
  }

  String activeSection() {
    return sections.active(scrollDistance, maximumScroll());
  }

  SectionNavigation.Bookmark bookmark() {
    return sections.remember(scrollDistance, maximumScroll());
  }

  void restore(SectionNavigation.Bookmark bookmark) {
    scrollDistance = sections.restore(bookmark, maximumScroll());
    position();
  }

  String focusKey() {
    return positions.stream()
        .filter(p -> p.widget == getFocused())
        .map(Positioned::key)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  String rememberedFocusKey() {
    var current = focusKey();
    if (current != null) rememberedFocus = current;
    return positions.stream()
            .anyMatch(
                p ->
                    p.widget.active
                        && p.widget.visible
                        && java.util.Objects.equals(p.key, rememberedFocus))
        ? rememberedFocus
        : null;
  }

  void restoreFocus(String key) {
    if (key != null)
      positions.stream()
          .filter(p -> key.equals(p.key) && p.widget.active && p.widget.visible)
          .findFirst()
          .ifPresent(
              p -> {
                setFocused(p.widget);
                p.widget.setFocused(true);
              });
  }

  String fieldFocus(String anchor, AbstractWidget preferred) {
    return positions.stream()
        .filter(
            p ->
                p.widget.active
                    && p.widget.visible
                    && p.key != null
                    && (preferred == null ? p.key.startsWith(anchor + "/") : p.widget == preferred))
        .map(Positioned::key)
        .findFirst()
        .orElse(null);
  }

  void focusVisibleControl() {
    position();
    positions.stream()
        .map(Positioned::widget)
        .filter(
            widget ->
                widget.active
                    && widget.visible
                    && widget.getY() >= top + border
                    && widget.getY() + widget.getHeight() <= bottom - border)
        .findFirst()
        .ifPresent(
            widget -> {
              setFocused(widget);
              widget.setFocused(true);
            });
  }

  boolean focusFirst(java.util.Collection<? extends GuiEventListener> owned) {
    var target =
        positions.stream()
            .filter(
                at ->
                    owned.contains(at.widget)
                        && at.widget.active
                        && at.widget.visible
                        && at.key != null)
            .findFirst();
    if (target.isEmpty()) return false;
    restoreFocus(target.orElseThrow().key);
    return getFocused() == target.orElseThrow().widget;
  }

  private int usableWidth() {
    return Math.max(1, width - 2 * border - SCROLLBAR_GUTTER);
  }

  int visibleRowHeight() {
    return Math.max(1, height - 2 * border);
  }

  /** Outer offer for content inside this viewport's actual framing and declared group depth. */
  static int widthForContent(int contentWidth, ClientTheme theme, int groupDepth) {
    if (contentWidth < 1 || groupDepth < 0 || groupDepth > 8)
      throw new IllegalArgumentException("Invalid measured form content offer");
    return Math.addExact(
        contentWidth,
        Math.addExact(
            2 * VIEWPORT_BORDER + SCROLLBAR_GUTTER,
            Math.multiplyExact(
                2 * groupDepth, theme.values().value(ThemeValues.Role.SPACE_CONTENT))));
  }

  private void measure() {
    int y = 0, wrap = usableWidth();
    var frames = new java.util.ArrayDeque<Frame>();
    positions.clear();
    layout.clear();
    outlines.clear();
    sections = new SectionNavigation();
    for (int i = 0; i < rows.size(); i++) {
      var row = rows.get(i);
      if (row.boundary == 1) {
        if (frames.size() >= 8) throw new IllegalStateException("Settings groups exceed depth 8");
        y += 4;
        int edge =
            frames.isEmpty()
                ? 0
                : Math.min(
                    Math.addExact(
                        frames.peek().left,
                        frames.peek().theme.values().value(ThemeValues.Role.SPACE_CONTENT)),
                    Math.max(0, (wrap - 1) / 2));
        frames.push(
            new Frame(
                edge,
                Math.max(1, wrap - edge * 2),
                y + 6 + client.font.lineHeight / 2,
                Math.max(0, Math.min(wrap - edge * 2 - 16, client.font.width(row.label))),
                row.theme));
      }
      if (row.boundary == -1) {
        if (frames.isEmpty()) throw new IllegalStateException("Unmatched settings group");
        y += 2;
        var frame = frames.pop();
        outlines.add(
            new Outline(frame.left, frame.width, frame.top, y, frame.labelWidth, frame.theme));
        y =
            Math.addExact(
                y,
                frame
                    .theme
                    .values()
                    .value(
                        frames.isEmpty()
                            ? ThemeValues.Role.SPACE_SECTION
                            : ThemeValues.Role.SPACE_NESTED_SECTION));
        continue;
      }
      int inset =
          frames.isEmpty()
              ? 0
              : (int)
                  Math.min(
                      (long) frames.peek().left
                          + frames.peek().theme.values().value(ThemeValues.Role.SPACE_CONTENT),
                      Math.max(0, (wrap - 1) / 2));
      int available = Math.max(1, wrap - inset * 2);
      var related = new ArrayList<Row>();
      related.add(row);
      if (row.field && row.relationship != null)
        for (int next = i + 1; next < rows.size() && related.size() < 3; next++) {
          var candidate = rows.get(next);
          if (!candidate.field || !row.relationship.equals(candidate.relationship)) break;
          related.add(candidate);
        }
      var tracks =
          related.size() > 1
              ? FormGeometry.relatedTracks(
                  related.stream().map(this::minimumWidth).toList(),
                  available,
                  row.theme.values().value(ThemeValues.Role.SPACE_RELATED))
              : List.<FormGeometry.Placement>of();
      if (!tracks.isEmpty()) {
        int shared = 0;
        for (int index = 0; index < related.size(); index++) {
          var at = tracks.get(index);
          shared = Math.max(shared, measureRow(related.get(index), inset + at.x(), y, at.width()));
        }
        for (var member : related) alignControls(member, shared);
        i += related.size() - 1;
        y =
            Math.addExact(
                y, Math.addExact(shared, row.theme.values().value(ThemeValues.Role.SPACE_FIELD)));
      } else {
        for (var member : related) {
          int measured = measureRow(member, inset, y, available);
          y =
              Math.addExact(
                  y,
                  Math.addExact(
                      measured,
                      member.heading || measured == 0
                          ? 0
                          : member
                              .theme
                              .values()
                              .value(
                                  navigation
                                      ? ThemeValues.Role.SPACE_INLINE
                                      : ThemeValues.Role.SPACE_FIELD)));
        }
        i += related.size() - 1;
      }
    }
    if (!frames.isEmpty()) throw new IllegalStateException("Unclosed settings group");
    extent = Math.max(1, y);
  }

  int fieldWidth() {
    return usableWidth() - (rows.stream().mapToInt(Row::boundary).sum() > 0 ? 16 : 0);
  }

  private void alignControls(Row row, int sharedHeight) {
    for (int i = 0; i < layout.size(); i++) {
      var at = layout.get(i);
      if (at.row != row) continue;
      int delta = sharedHeight - at.height;
      if (delta == 0) return;
      int offset = at.stacked ? delta : delta / 2;
      for (int j = 0; j < positions.size(); j++) {
        var position = positions.get(j);
        if (row.controls.contains(position.widget))
          positions.set(
              j, new Positioned(position.widget, position.x, position.y + offset, position.key));
      }
      layout.set(
          i,
          new Layout(
              row,
              at.x,
              at.y,
              sharedHeight,
              at.textWidth,
              at.textY + (at.stacked ? 0 : delta / 2),
              at.summaryY,
              at.stacked));
      return;
    }
  }

  private int minimumWidth(Row row) {
    return Math.max(
        Math.min(
            client.font.width(row.label),
            row.theme.values().value(ThemeValues.Role.MEASURE_LABEL_PREFERRED_LIMIT)),
        Math.max(
            1,
            FormGeometry.minimumControlWidth(
                row.preferred,
                row.theme.values().value(ThemeValues.Role.SPACE_INLINE),
                joins(row))));
  }

  private record TextMeasure(int summaryY, int height) {}

  private TextMeasure textMeasure(Row row, int width) {
    int label =
        row.label.getString().isEmpty()
            ? 0
            : client.font.split(row.label, width).size() * client.font.lineHeight;
    int summary =
        label
            + (row.summary.getString().isEmpty()
                ? 0
                : row.theme.values().value(ThemeValues.Role.SPACE_TEXT));
    int height =
        summary
            + (row.summary.getString().isEmpty()
                ? 0
                : client.font.split(row.summary, width).size() * client.font.lineHeight);
    return new TextMeasure(summary, height);
  }

  private int measureRow(Row row, int left, int y, int wrap) {
    if (row.anchor != null) sections.add(row.anchor, y);
    if (row.heading) {
      int height = 12 + client.font.split(row.label, wrap).size() * client.font.lineHeight;
      layout.add(new Layout(row, left, y + 6, height, wrap, 0, 0, false));
      return height;
    }
    if (navigation
        && row.controls.size() == 1
        && !(row.controls.getFirst() instanceof NativeGraphicWidget)) {
      var button = row.controls.getFirst();
      button.setWidth(wrap);
      button.setHeight(
          Math.max(
              row.theme.values().value(ThemeValues.Role.CONTROL_HEIGHT),
              client
                          .font
                          .split(
                              button instanceof NavigationButton ? button.getMessage() : row.label,
                              button instanceof NavigationButton nav
                                  ? nav.labelWidth()
                                  : Math.max(1, wrap - 8))
                          .size()
                      * client.font.lineHeight
                  + 10));
      positions.add(new Positioned(button, left, y, row.key));
      layout.add(new Layout(row, left, y, button.getHeight(), wrap, 0, 0, false));
      return button.getHeight();
    }
    int preferredText =
        row.label.getString().isEmpty() && row.summary.getString().isEmpty()
            ? 0
            : Math.max(
                row.theme.values().value(ThemeValues.Role.MEASURE_TEXT_MINIMUM),
                Math.min(
                    row.theme.values().value(ThemeValues.Role.MEASURE_TEXT_PREFERRED_LIMIT),
                    Math.max(client.font.width(row.label), client.font.width(row.summary))));
    var joined = joins(row);
    var tracks =
        FormGeometry.fieldTracks(
            wrap,
            preferredText,
            row.preferred,
            row.theme.values().value(ThemeValues.Role.SPACE_CONTENT),
            joined,
            row.theme.values().value(ThemeValues.Role.SPACE_INLINE));
    var text = textMeasure(row, Math.max(1, tracks.textWidth()));
    if (row.field && !tracks.stacked() && !row.controls.isEmpty()) {
      var alternative = textMeasure(row, wrap);
      int controlsHeight =
          FormGeometry.controls(
                  row.preferred,
                  wrap,
                  row.theme.values().value(ThemeValues.Role.SPACE_INLINE),
                  joined)
              .height();
      int stackedHeight =
          alternative.height()
              + (alternative.height() > 0 && controlsHeight > 0
                  ? row.theme.values().value(ThemeValues.Role.SPACE_TEXT)
                  : 0)
              + controlsHeight;
      if (stackedHeight < Math.max(text.height(), tracks.controls().height())) {
        tracks =
            FormGeometry.fieldTracks(
                wrap,
                wrap,
                row.preferred,
                row.theme.values().value(ThemeValues.Role.SPACE_CONTENT),
                joined,
                row.theme.values().value(ThemeValues.Role.SPACE_INLINE));
        text = alternative;
      }
    }
    boolean stacked = tracks.stacked();
    int textWidth = Math.max(1, tracks.textWidth());
    int summaryY = text.summaryY();
    int textHeight = text.height();
    int start = tracks.controlsX();
    var controlLayout = tracks.controls();
    int controlHeight = controlLayout.height();
    int height =
        stacked && controlHeight > 0 && textHeight > 0
            ? textHeight + row.theme.values().value(ThemeValues.Role.SPACE_TEXT) + controlHeight
            : Math.max(textHeight, controlHeight);
    int actionY =
        stacked && textHeight > 0
            ? y + textHeight + row.theme.values().value(ThemeValues.Role.SPACE_TEXT)
            : y + (height - controlHeight) / 2;
    for (int index = 0; index < row.controls.size(); index++) {
      var widget = row.controls.get(index);
      var at = controlLayout.placements().get(index);
      ControlSizing.resize(widget, at.width());
      widget.setHeight(at.height());
      positions.add(
          new Positioned(
              widget,
              left + start + at.x(),
              actionY + at.y(),
              row.key == null ? null : row.key + "/" + index));
    }
    layout.add(
        new Layout(
            row,
            left,
            y,
            height,
            textWidth,
            stacked ? 0 : (height - textHeight) / 2,
            summaryY,
            stacked));
    return height;
  }

  private void position() {
    for (var at : positions) {
      at.widget.setPosition(left + border + at.x, top + border + at.y - (int) scrollDistance);
      ControlSizing.geometryFailure(at.widget, false);
    }
  }

  @Override
  protected int getContentHeight() {
    return extent;
  }

  @Override
  protected void drawBackground(FormCanvas graphics, float tick) {
    graphics.fill(
        left,
        top,
        right,
        bottom,
        baseTheme
            .values()
            .value(
                navigation ? ThemeValues.Role.SURFACE_NAVIGATION : ThemeValues.Role.SURFACE_FORM));
  }

  @Override
  protected void drawPanel(FormCanvas graphics, int relativeY, int mx, int my) {
    position();
    for (var outline : outlines) {
      int x = left + border + outline.left, edge = x + outline.width - 1;
      int y = relativeY + outline.top, end = relativeY + outline.bottom;
      if (end < top || y >= bottom) continue;
      int color = outline.theme.values().value(ThemeValues.Role.LINE);
      graphics.vLine(x, y, end, color);
      graphics.vLine(edge, y, end, color);
      graphics.hLine(x, x + 4, y, color);
      if (x + outline.labelWidth + 12 < edge)
        graphics.hLine(x + outline.labelWidth + 12, edge, y, color);
      graphics.hLine(x, edge, end, color);
    }
    for (var at : layout) {
      int y = relativeY + at.y;
      if (y + at.height < top || y >= bottom) continue;
      var row = at.row;
      if (navigation && !row.heading && row.controls.size() == 1)
        graphics.widget(row.controls.getFirst(), mx, my, 0);
      else {
        var theme = row.theme;
        int color =
            row.anchor != null && row.anchor.equals(highlighted)
                ? theme.color(ClientTheme.TextRole.ACTIVE)
                : theme.color(row.labelRole);
        var lines = client.font.split(row.label, at.textWidth);
        for (int line = 0; line < lines.size(); line++)
          graphics.drawArgb(
              client.font,
              lines.get(line),
              left + border + at.x,
              y + at.textY + line * client.font.lineHeight,
              color,
              true);
        if (!row.summary.getString().isEmpty())
          theme.wrap(
              graphics,
              client.font,
              ClientTheme.TextRole.SUPPORT,
              row.summary,
              left + border + at.x,
              y + at.textY + at.summaryY,
              at.textWidth);
        for (var widget : row.controls)
          if (widget.getY() + widget.getHeight() > top && widget.getY() < bottom)
            graphics.widget(widget, mx, my, 0);
      }
    }
  }

  @Override
  public List<? extends GuiEventListener> children() {
    return positions.stream().map(Positioned::widget).toList();
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (!isMouseOver(x, y) || extent <= height - border && x >= right - 8) return false;
    position();
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (getFocused() != null
        && isDragging()
        && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
        && dragFocused(x, y, button, dx, dy)) return true;
    boolean handled = super.mouseDragged(x, y, button, dx, dy);
    if (handled) position();
    return handled;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double dx, double dy) {
    if (!isMouseOver(x, y) || extent <= height - border) return false;
    boolean result = super.mouseScrolled(x, y, dx, dy);
    position();
    return result;
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    super.setFocused(listener);
    if (listener != null && getFocused() == listener) rememberedFocus = focusKey();
    if (listener instanceof AbstractWidget widget) {
      if (widget.getY() < top + border) scrollDistance -= top + border - widget.getY();
      else if (widget.getY() + widget.getHeight() > bottom - border)
        scrollDistance += widget.getY() + widget.getHeight() - bottom + border;
      scrollDistance = Math.clamp(scrollDistance, 0, Math.max(0, extent - height + border));
      position();
    }
  }

  @Override
  public void setFocused(boolean focused) {
    if (!focused) {
      var current = focusKey();
      if (current != null) rememberedFocus = current;
      if (getFocused() != null) getFocused().setFocused(false);
      super.setFocused((GuiEventListener) null);
    }
  }

  @Override
  public ComponentPath nextFocusPath(
      net.minecraft.client.gui.navigation.FocusNavigationEvent event) {
    position();
    return super.nextFocusPath(event);
  }

  @Override
  public NarrationPriority narrationPriority() {
    return getFocused() == null ? NarrationPriority.NONE : NarrationPriority.FOCUSED;
  }

  @Override
  public void updateNarration(NarrationElementOutput output) {
    if (getFocused() instanceof AbstractWidget widget) {
      // Keep native value, usage and error narration at its own depth.
      widget.updateNarration(output.nest());
      var row =
          rows.stream()
              .filter(candidate -> candidate.controls.contains(widget))
              .findFirst()
              .orElse(null);
      if (row != null) {
        Component help = row.summary;
        if (help.getString().isEmpty() && row.key != null) {
          // Collection controls inherit the authored field description, not a neighboring row.
          var parent =
              rows.stream()
                  .filter(
                      candidate ->
                          candidate.key != null
                              && row.key.startsWith(candidate.key + "/")
                              && !candidate.summary.getString().isEmpty())
                  .max(java.util.Comparator.comparingInt(candidate -> candidate.key.length()))
                  .orElse(null);
          if (parent != null) help = parent.summary;
        }
        if (!help.getString().isEmpty()) output.add(NarratedElementType.HINT, help);
      }
    } else
      output.add(
          NarratedElementType.TITLE,
          Component.translatableWithFallback("reform.settings.title", "Settings"));
  }
}
