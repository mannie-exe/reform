package example.reform.platform;

import java.util.List;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;

/** Verification-only nested native input; no owner, value model, or parent effect callback. */
final class NativeControlFrame extends AbstractWidget implements ContainerEventHandler {
  final AbstractWidget child;
  private GuiEventListener focused;
  private boolean dragging;
  int parentActivations;

  NativeControlFrame(AbstractWidget child) {
    super(0, 0, child.getWidth() + 8, child.getHeight() + 8, child.getMessage());
    this.child = child;
    place();
  }

  private void place() {
    if (child == null) return;
    child.setPosition(getX() + 4, getY() + 4);
    child.setWidth(Math.max(1, getWidth() - 8));
    child.setHeight(Math.max(1, getHeight() - 8));
  }

  @Override
  public void setX(int value) {
    super.setX(value);
    place();
  }

  @Override
  public void setY(int value) {
    super.setY(value);
    place();
  }

  @Override
  public void setWidth(int value) {
    super.setWidth(value);
    place();
  }

  @Override
  public void setHeight(int value) {
    super.setHeight(value);
    place();
  }

  @Override
  public List<? extends GuiEventListener> children() {
    return List.of(child);
  }

  @Override
  public boolean isDragging() {
    return dragging;
  }

  @Override
  public void setDragging(boolean value) {
    dragging = value;
  }

  @Override
  public GuiEventListener getFocused() {
    return focused;
  }

  @Override
  public void setFocused(GuiEventListener value) {
    if (focused != null && focused != value) focused.setFocused(false);
    focused = value;
    if (value != null) value.setFocused(true);
  }

  @Override
  public void setFocused(boolean value) {
    super.setFocused(value);
    setFocused(value ? child : null);
  }

  @Override
  public ComponentPath nextFocusPath(FocusNavigationEvent event) {
    return ContainerEventHandler.super.nextFocusPath(event);
  }

  @Override
  public ComponentPath getCurrentFocusPath() {
    return ContainerEventHandler.super.getCurrentFocusPath();
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    child.updateNarration(output.nest());
  }

  @Override
  public boolean mouseClicked(double x, double y, int button) {
    return active && visible && ContainerEventHandler.super.mouseClicked(x, y, button);
  }

  @Override
  public boolean mouseReleased(double x, double y, int button) {
    return ContainerEventHandler.super.mouseReleased(x, y, button);
  }

  @Override
  public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
    return ContainerEventHandler.super.mouseDragged(x, y, button, dx, dy);
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    return active && ContainerEventHandler.super.keyPressed(key, scan, modifiers);
  }

  @Override
  public boolean keyReleased(int key, int scan, int modifiers) {
    return ContainerEventHandler.super.keyReleased(key, scan, modifiers);
  }

  @Override
  public boolean charTyped(char value, int modifiers) {
    return active && ContainerEventHandler.super.charTyped(value, modifiers);
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onClick(double x, double y) {
    parentActivations++;
  }

  @Override
  protected void renderWidget(
      net.minecraft.client.gui.GuiGraphics graphics, int x, int y, float delta) {
    place();
    graphics.fill(getX(), getY(), getRight(), getBottom(), 0xff244050);
    child.render(graphics, x, y, delta);
  }
}
