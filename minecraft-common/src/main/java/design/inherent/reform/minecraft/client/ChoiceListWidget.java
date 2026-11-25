package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.ui.ChoiceListData;
import design.inherent.reform.presentation.ThemeValues.Role;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** One native focus target with a bounded prepared row window. Rendering reads no source. */
final class ChoiceListWidget extends NativeChoiceWidget {
  private record Cell(int index, Component title) {}

  private ChoiceListData data = new ChoiceListData("", List.of(), OptionalLong.empty());
  private List<Cell> cells = List.of();
  private Set<String> accepted = Set.of();
  private Optional<String> selected = Optional.empty();
  private ClientTheme theme = ClientTheme.standard();
  private int first, rowHeight = 20, shown = 4, overscan = 2;
  private boolean dragging;
  private Consumer<String> navigation = ignored -> {};
  private Consumer<String> acceptance = ignored -> {};

  ChoiceListWidget(Component label) {
    super(label);
  }

  void prepare(
      ChoiceListData next,
      Optional<String> activeKey,
      Set<String> acceptedKeys,
      int rows,
      int extra,
      ClientTheme theme,
      Consumer<String> navigate,
      Consumer<String> accept) {
    String anchor = first < data.items().size() ? data.items().get(first).key() : null;
    if (!next.generation().equals(data.generation())) first = 0;
    else if (anchor != null) {
      int index = indexOf(next, anchor);
      if (index >= 0) first = index;
    }
    data = next;
    selected = activeKey;
    accepted = Set.copyOf(acceptedKeys);
    shown = rows;
    overscan = extra;
    this.theme = theme;
    rowHeight = Math.max(20, theme.values().value(Role.CONTROL_HEIGHT));
    navigation = navigate;
    acceptance = accept;
    setHeight(shown * rowHeight);
    clamp();
    prepareCells();
  }

  private static int indexOf(ChoiceListData data, String key) {
    for (int i = 0; i < data.items().size(); i++)
      if (data.items().get(i).key().equals(key)) return i;
    return -1;
  }

  private void clamp() {
    first = Math.clamp(first, 0, Math.max(0, data.items().size() - shown));
  }

  private void prepareCells() {
    var next = new ArrayList<Cell>();
    for (int i = Math.max(0, first - overscan);
        i < Math.min(data.items().size(), first + shown + overscan);
        i++) next.add(new Cell(i, Component.literal(data.items().get(i).title())));
    cells = List.copyOf(next);
  }

  int preparedRowCount() {
    return cells.size();
  }

  int firstVisibleIndex() {
    return first;
  }

  void detach() {
    navigation = ignored -> {};
    acceptance = ignored -> {};
    dragging = false;
  }

  void clear() {
    detach();
    data = new ChoiceListData("", List.of(), OptionalLong.empty());
    cells = List.of();
    accepted = Set.of();
    selected = Optional.empty();
    first = 0;
  }

  @Override
  protected void drawList(FormCanvas canvas) {
    var values = theme.values();
    canvas.fill(getX(), getY(), getRight(), getBottom(), values.value(Role.SURFACE_FORM));
    canvas.enableScissor(getX(), getY(), Math.max(getX(), getRight() - 7), getBottom());
    try {
      var font = Minecraft.getInstance().font;
      for (var cell : cells) {
        int y = getY() + (cell.index() - first) * rowHeight;
        if (y + rowHeight <= getY() || y >= getBottom()) continue;
        var item = data.items().get(cell.index());
        boolean activeRow = selected.filter(item.key()::equals).isPresent();
        if (activeRow)
          canvas.fill(
              getX(), y, getRight() - 7, y + rowHeight, values.value(Role.SURFACE_SELECTED));
        if (accepted.contains(item.key()))
          canvas.fill(getX(), y, getX() + 2, y + rowHeight, values.value(Role.LINE_SELECTED));
        theme.draw(
            canvas,
            font,
            item.enabled() ? ClientTheme.TextRole.VALUE : ClientTheme.TextRole.SUPPORT,
            cell.title(),
            getX() + 5,
            y + (rowHeight - font.lineHeight) / 2,
            false);
      }
    } finally {
      canvas.disableScissor();
    }
    if (data.items().size() > shown) {
      int thumb = Math.max(8, getHeight() * shown / data.items().size());
      int y = getY() + (getHeight() - thumb) * first / Math.max(1, data.items().size() - shown);
      canvas.fill(getRight() - 5, getY(), getRight(), getBottom(), values.value(Role.LINE));
      canvas.fill(getRight() - 5, y, getRight(), y + thumb, values.value(Role.TEXT_SUPPORTING));
    }
    if (isFocused()) {
      int c = values.value(Role.FOCUS);
      canvas.hLine(getX(), getRight() - 1, getY(), c);
      canvas.hLine(getX(), getRight() - 1, getBottom() - 1, c);
      canvas.vLine(getX(), getY(), getBottom() - 1, c);
      canvas.vLine(getRight() - 1, getY(), getBottom() - 1, c);
    }
  }

  @Override
  protected boolean listKey(int key) {
    if (!active || !isFocused()) return false;
    if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_SPACE) {
      selected.ifPresent(this::accept);
      return true;
    }
    int direction =
        switch (key) {
          case InputConstants.KEY_UP, InputConstants.KEY_HOME, InputConstants.KEY_PAGEUP -> -1;
          case InputConstants.KEY_DOWN, InputConstants.KEY_END, InputConstants.KEY_PAGEDOWN -> 1;
          default -> 0;
        };
    if (direction == 0) return false;
    int current = selected.map(keyValue -> indexOf(data, keyValue)).orElse(-1);
    int next =
        key == InputConstants.KEY_HOME
            ? 0
            : key == InputConstants.KEY_END
                ? data.items().size() - 1
                : current < 0
                    ? (direction > 0 ? 0 : data.items().size() - 1)
                    : current
                        + direction
                            * ((key == InputConstants.KEY_PAGEUP
                                    || key == InputConstants.KEY_PAGEDOWN)
                                ? shown
                                : 1);
    next = Math.clamp(next, 0, Math.max(0, data.items().size() - 1));
    while (next >= 0 && next < data.items().size()) {
      if (data.items().get(next).enabled()) {
        reveal(next);
        navigation.accept(data.items().get(next).key());
        return true;
      }
      next += direction;
    }
    return true;
  }

  private void reveal(int index) {
    if (index < first) first = index;
    else if (index >= first + shown) first = index - shown + 1;
    clamp();
    prepareCells();
  }

  private void accept(String key) {
    int i = indexOf(data, key);
    if (i >= 0 && data.items().get(i).enabled()) acceptance.accept(key);
  }

  @Override
  protected boolean listClick(double x, double y, int button) {
    if (!active || button != 0 || !isMouseOver(x, y)) return false;
    setFocused(true);
    if (x >= getRight() - 7 && data.items().size() > shown) {
      dragging = true;
      scrollTo(y);
      return true;
    }
    int index = first + (int) (y - getY()) / rowHeight;
    if (index < data.items().size() && data.items().get(index).enabled()) {
      String key = data.items().get(index).key();
      accept(key);
    }
    return true;
  }

  private void scrollTo(double y) {
    first =
        (int) Math.round((y - getY()) / Math.max(1, getHeight()) * (data.items().size() - shown));
    clamp();
    prepareCells();
  }

  @Override
  protected boolean listDrag(double x, double y, int button) {
    if (!dragging || button != 0 || !active) return false;
    scrollTo(y);
    return true;
  }

  @Override
  protected boolean listRelease() {
    boolean was = dragging;
    dragging = false;
    return was;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (!active || !isMouseOver(x, y) || data.items().size() <= shown || vertical == 0)
      return false;
    int old = first;
    first += vertical > 0 ? -1 : 1;
    clamp();
    prepareCells();
    return old != first;
  }

  @Override
  protected Component listNarration() {
    var text = getMessage().copy();
    selected.ifPresent(
        key -> {
          int i = indexOf(data, key);
          if (i >= 0) {
            var item = data.items().get(i);
            text.append(". ").append(item.title());
            if (!item.detail().isBlank()) text.append(". ").append(item.detail());
            if (accepted.contains(key))
              text.append(Component.translatableWithFallback("reform.ui.selected", ". Selected"));
            if (data.total().isPresent())
              text.append(
                  Component.translatableWithFallback(
                      "reform.ui.choice_total",
                      ". Source contains %s choices",
                      data.total().getAsLong()));
          }
        });
    if (data.items().isEmpty())
      text.append(
          Component.translatableWithFallback(
              "reform.ui.window_empty", ". No choices in this window"));
    return text;
  }
}
