package design.inherent.reform.minecraft.client;

import net.minecraft.client.gui.components.events.GuiEventListener;

/** A clipped form viewport; child controls keep native input, focus and narration. */
public abstract class ScrollFrame extends NativeViewport {
  protected final int left;
  protected final int top;
  protected final int right;
  protected final int bottom;
  protected final int width;
  protected final int height;
  protected final int border;
  protected float scrollDistance;
  private boolean thumbDragging;

  protected ScrollFrame(int width, int height, int top, int left, int border) {
    if (width < 1 || height < 1 || border < 0)
      throw new IllegalArgumentException("Invalid viewport");
    this.width = width;
    this.height = height;
    this.top = top;
    this.left = left;
    this.right = Math.addExact(left, width);
    this.bottom = Math.addExact(top, height);
    this.border = border;
  }

  protected abstract int getContentHeight();

  protected abstract void drawBackground(FormCanvas canvas, float delta);

  protected abstract void drawPanel(FormCanvas canvas, int relativeY, int x, int y);

  /** Ends viewport capture on hide/replacement without replaying a synthetic input event. */
  final void cancelPointerCapture() {
    thumbDragging = false;
    setDragging(false);
  }

  private int maximumScroll() {
    return Math.max(0, getContentHeight() - height + border);
  }

  private int thumbHeight() {
    return Math.clamp(
        (int) ((long) height * height / Math.max(height, getContentHeight() + border)),
        Math.min(12, height),
        height);
  }

  private int thumbTop() {
    int range = maximumScroll();
    return top + (range == 0 ? 0 : Math.round(scrollDistance * (height - thumbHeight()) / range));
  }

  @Override
  public final void render(FormCanvas canvas, int x, int y, float delta) {
    scrollDistance = Math.clamp(scrollDistance, 0, maximumScroll());
    drawBackground(canvas, delta);
    canvas.enableScissor(left, top, right - 8, bottom);
    try {
      drawPanel(canvas, top + border - (int) scrollDistance, x, y);
    } finally {
      canvas.disableScissor();
    }
    if (maximumScroll() > 0) {
      int thumbY = thumbTop();
      canvas.fill(right - 6, top, right, bottom, 0xff101010);
      canvas.fill(right - 6, thumbY, right, thumbY + thumbHeight(), 0xff808080);
      canvas.fill(right - 5, thumbY, right - 1, thumbY + thumbHeight() - 1, 0xffc0c0c0);
    }
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return x >= left && x < right && y >= top && y < bottom;
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    if (!isMouseOver(x, y)) return false;
    if (x >= right - 8) {
      if (button != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
          || maximumScroll() == 0) return false;
      thumbDragging = true;
      setDragging(true);
      if (y < thumbTop() || y >= thumbTop() + thumbHeight()) {
        scrollDistance =
            (float)
                ((y - top - thumbHeight() / 2.0)
                    * maximumScroll()
                    / Math.max(1, height - thumbHeight()));
        scrollDistance = Math.clamp(scrollDistance, 0, maximumScroll());
      }
      return true;
    }
    return super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    if (thumbDragging && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
      scrollDistance =
          Math.clamp(
              (float) (scrollDistance + dy * maximumScroll() / Math.max(1, height - thumbHeight())),
              0,
              maximumScroll());
      return true;
    }
    return super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    boolean scrollbar =
        thumbDragging && button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    if (scrollbar) thumbDragging = false;
    // Always deliver release to a captured native control, including outside the viewport.
    boolean child = super.mouseReleased(x, y, button);
    return scrollbar || child;
  }

  @Override
  public boolean mouseScrolled(double x, double y, double dx, double dy) {
    if (!isMouseOver(x, y) || maximumScroll() == 0 || dy == 0 || !Double.isFinite(dy)) return false;
    scrollDistance = Math.clamp((float) (scrollDistance - dy * 20), 0, maximumScroll());
    return true;
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    if (listener != null && !children().contains(listener)) return;
    super.setFocused(listener);
  }
}
