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
  private final boolean malformed;
  int parentActivations;

  NativeControlFrame(AbstractWidget child) {
    this(child, false);
  }

  NativeControlFrame(AbstractWidget child, boolean malformed) {
    super(0, 0, child.getWidth() + 8, child.getHeight() + 8, child.getMessage());
    this.child = child;
    this.malformed = malformed;
    place();
  }

  private void place() {
    if (child == null) return;
    child.setPosition(getX() + (malformed ? getWidth() + 4 : 4), getY() + 4);
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
  public boolean mouseClicked(
      net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
    return active && visible && ContainerEventHandler.super.mouseClicked(event, doubleClick);
  }

  @Override
  public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
    return ContainerEventHandler.super.mouseReleased(event);
  }

  @Override
  public boolean mouseDragged(
      net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
    return ContainerEventHandler.super.mouseDragged(event, dx, dy);
  }

  @Override
  public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
    return active && ContainerEventHandler.super.keyPressed(event);
  }

  @Override
  public boolean keyReleased(net.minecraft.client.input.KeyEvent event) {
    return ContainerEventHandler.super.keyReleased(event);
  }

  @Override
  public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
    return active && ContainerEventHandler.super.charTyped(event);
  }

  @Override
  public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
    parentActivations++;
  }

  @Override
  protected void extractWidgetRenderState(
      net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y, float delta) {
    place();
    graphics.fill(getX(), getY(), getRight(), getBottom(), 0xff244050);
    child.extractRenderState(graphics, x, y, delta);
  }
}
