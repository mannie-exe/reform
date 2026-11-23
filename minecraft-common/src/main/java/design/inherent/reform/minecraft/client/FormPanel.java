package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.FormTokens;
import design.inherent.reform.presentation.SectionNavigation;
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
            getX(), getY(), getX() + width, getY() + height, FormTokens.Color.LINE_SELECTED);
        graphics.fill(
            getX() + 1,
            getY() + 1,
            getX() + width - 1,
            getY() + height - 1,
            FormTokens.Color.SURFACE_SELECTED);
      } else if (isHoveredOrFocused())
        graphics.fill(
            getX(), getY(), getX() + width, getY() + height, FormTokens.Color.SURFACE_HOVER);
      renderString(
          graphics,
          Minecraft.getInstance().font,
          selected ? ACTIVE_TEXT : category ? DESCRIPTION_TEXT : TITLE_TEXT);
      if (isFocused()) {
        int edge = 0xffffffff;
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
        graphics.drawString(font, expanded ? "−" : "+", getX() + indent, y, color);
      for (var line : lines) {
        graphics.drawString(font, line, getX() + indent + markerWidth, y, color);
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
      List<FormGeometry.Size> preferred) {
    Row(
        String key,
        Component label,
        Component summary,
        List<AbstractWidget> controls,
        boolean heading,
        String anchor,
        boolean field,
        int boundary,
        String relationship) {
      this(
          key,
          label,
          summary,
          controls,
          heading,
          anchor,
          field,
          boundary,
          relationship,
          controls.stream().map(ControlSizing::preferred).toList());
    }
  }

  private record Positioned(AbstractWidget widget, int x, int y, String key) {}

  private record Layout(
      Row row, int x, int y, int height, int textWidth, int textY, int summaryY, boolean stacked) {}

  private record Outline(int left, int width, int top, int bottom, int labelWidth) {}

  private record Frame(int left, int width, int top, int labelWidth) {}

  private final List<Outline> outlines = new ArrayList<>();
  private String rememberedFocus;

  void beginGroup(String id, Component label) {
    rows.add(new Row(id, label, Component.empty(), List.of(), true, id, false, 1, null));
  }

  void endGroup() {
    rows.add(
        new Row(
            null, Component.empty(), Component.empty(), List.of(), true, null, false, -1, null));
  }

  private final List<Row> rows = new ArrayList<>();
  private final java.util.Set<AbstractWidget> joinedControls =
      java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
  private final List<Positioned> positions = new ArrayList<>();
  private final List<Layout> layout = new ArrayList<>();
  private final Minecraft client;
  private final boolean navigation;
  private int extent;
  private boolean finished;
  private String highlighted;
  private SectionNavigation sections = new SectionNavigation();

  FormPanel(Minecraft client, int x, int y, int width, int height, boolean navigation) {
    super(width, height, y, x, 6);
    this.client = client;
    this.navigation = navigation;
  }

  void heading(Component label) {
    rows.add(
        new Row(
            null,
            label
                .copy()
                .withStyle(net.minecraft.ChatFormatting.BOLD, net.minecraft.ChatFormatting.WHITE),
            Component.empty(),
            List.of(),
            true,
            null,
            false,
            0,
            null));
  }

  void eyebrow(Component label) {
    rows.add(
        new Row(
            null,
            label.copy().withStyle(net.minecraft.ChatFormatting.YELLOW),
            Component.empty(),
            List.of(),
            true,
            null,
            false,
            0,
            null));
  }

  void section(String id, Component label) {
    rows.add(new Row(null, label, Component.empty(), List.of(), true, id, false, 0, null));
  }

  void anchor(String id) {
    rows.add(
        new Row(null, Component.empty(), Component.empty(), List.of(), false, id, false, 0, null));
  }

  void row(Component label, Component summary, AbstractWidget... controls) {
    row(null, label, summary, controls);
  }

  void row(String key, Component label, Component summary, AbstractWidget... controls) {
    rows.add(new Row(key, label, summary, List.of(controls), false, null, false, 0, null));
  }

  void anchorRow(String key, Component label, Component summary, AbstractWidget... controls) {
    rows.add(new Row(key, label, summary, List.of(controls), false, key, false, 0, null));
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
    rows.add(new Row(key, label, summary, List.of(controls), false, key, true, 0, relationship));
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
        if (row.summary.equals(summary)) return;
        rows.set(
            i,
            new Row(
                row.key,
                row.label,
                summary,
                row.controls,
                row.heading,
                row.anchor,
                row.field,
                row.boundary,
                row.relationship,
                row.preferred));
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
    return Math.max(1, width - 2 * border - 8);
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
        int edge = Math.min(frames.size() * FormTokens.Space.CONTENT, Math.max(0, (wrap - 1) / 2));
        frames.push(
            new Frame(
                edge,
                Math.max(1, wrap - edge * 2),
                y + 6 + client.font.lineHeight / 2,
                Math.max(0, Math.min(wrap - edge * 2 - 16, client.font.width(row.label)))));
      }
      if (row.boundary == -1) {
        if (frames.isEmpty()) throw new IllegalStateException("Unmatched settings group");
        y += 2;
        var frame = frames.pop();
        outlines.add(new Outline(frame.left, frame.width, frame.top, y, frame.labelWidth));
        y += frames.isEmpty() ? FormTokens.Space.SECTION : FormTokens.Space.NESTED_SECTION;
        continue;
      }
      int inset = Math.min(frames.size() * FormTokens.Space.CONTENT, Math.max(0, (wrap - 1) / 2));
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
                  FormTokens.Space.RELATED)
              : List.<FormGeometry.Placement>of();
      if (!tracks.isEmpty()) {
        int shared = 0;
        for (int index = 0; index < related.size(); index++) {
          var at = tracks.get(index);
          shared = Math.max(shared, measureRow(related.get(index), inset + at.x(), y, at.width()));
        }
        for (var member : related) alignControls(member, shared);
        i += related.size() - 1;
        y += shared + FormTokens.Space.FIELD;
      } else {
        for (var member : related) {
          int measured = measureRow(member, inset, y, available);
          y +=
              measured
                  + (member.heading || measured == 0
                      ? 0
                      : navigation ? FormTokens.Space.INLINE : FormTokens.Space.FIELD);
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
        Math.min(client.font.width(row.label), FormTokens.Measure.LABEL_PREFERRED_LIMIT),
        Math.max(1, FormGeometry.minimumControlWidth(row.preferred, 4, joins(row))));
  }

  private record TextMeasure(int summaryY, int height) {}

  private TextMeasure textMeasure(Row row, int width) {
    int label =
        row.label.getString().isEmpty()
            ? 0
            : client.font.split(row.label, width).size() * client.font.lineHeight;
    int summary = label + (row.summary.getString().isEmpty() ? 0 : FormTokens.Space.TEXT);
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
              20,
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
                FormTokens.Measure.TEXT_MINIMUM,
                Math.min(
                    FormTokens.Measure.TEXT_PREFERRED_LIMIT,
                    Math.max(client.font.width(row.label), client.font.width(row.summary))));
    var joined = joins(row);
    var tracks =
        FormGeometry.fieldTracks(
            wrap, preferredText, row.preferred, FormTokens.Space.CONTENT, joined);
    var text = textMeasure(row, Math.max(1, tracks.textWidth()));
    if (row.field && !tracks.stacked() && !row.controls.isEmpty()) {
      var alternative = textMeasure(row, wrap);
      int controlsHeight =
          FormGeometry.controls(row.preferred, wrap, FormTokens.Space.INLINE, joined).height();
      int stackedHeight =
          alternative.height()
              + (alternative.height() > 0 && controlsHeight > 0 ? FormTokens.Space.TEXT : 0)
              + controlsHeight;
      if (stackedHeight < Math.max(text.height(), tracks.controls().height())) {
        tracks =
            FormGeometry.fieldTracks(wrap, wrap, row.preferred, FormTokens.Space.CONTENT, joined);
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
            ? textHeight + FormTokens.Space.TEXT + controlHeight
            : Math.max(textHeight, controlHeight);
    int actionY =
        stacked && textHeight > 0
            ? y + textHeight + FormTokens.Space.TEXT
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
    for (var at : positions)
      at.widget.setPosition(left + border + at.x, top + border + at.y - (int) scrollDistance);
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
        navigation ? FormTokens.Color.SURFACE_NAVIGATION : FormTokens.Color.SURFACE_FORM);
  }

  @Override
  protected void drawPanel(FormCanvas graphics, int relativeY, int mx, int my) {
    position();
    for (var outline : outlines) {
      int x = left + border + outline.left, edge = x + outline.width - 1;
      int y = relativeY + outline.top, end = relativeY + outline.bottom;
      if (end < top || y >= bottom) continue;
      int color = FormTokens.Color.LINE;
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
        int color =
            row.anchor != null && row.anchor.equals(highlighted)
                ? ACTIVE_TEXT
                : row.heading ? DESCRIPTION_TEXT : TITLE_TEXT;
        graphics.drawWordWrap(
            client.font, row.label, left + border + at.x, y + at.textY, at.textWidth, color);
        if (!row.summary.getString().isEmpty())
          graphics.drawWordWrap(
              client.font,
              row.summary,
              left + border + at.x,
              y + at.textY + at.summaryY,
              at.textWidth,
              DESCRIPTION_TEXT);
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
