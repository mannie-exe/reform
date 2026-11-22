package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.FormGeometry;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Retained presentation mounted in one real native host; never a nested Minecraft screen. */
abstract class FormView {
  interface Host {
    Screen screen();

    Font font();

    boolean current(FormView view);

    <W extends GuiEventListener & Renderable & NarratableEntry> W add(W widget);

    void remove(GuiEventListener widget);

    GuiEventListener focused();

    void focus(GuiEventListener widget);

    void rebuild();

    void initialFocus();

    boolean nativeKeyPressed(int key, int scan, int modifiers);

    boolean controlDown();

    boolean shiftDown();

    boolean close(FormView view);

    void navigate(Screen next);

    void information(FormView view, FieldPath path, Component label);
  }

  protected final Component title;
  protected Minecraft minecraft;
  protected Font font;
  protected int left, top, width, height;
  private Host host;

  FormView(Component title) {
    this.title = Objects.requireNonNull(title).copy();
  }

  final void mount(Host host, int width, int height) {
    mount(host, new FormGeometry.Placement(0, 0, width, height));
  }

  final void mount(Host host, FormGeometry.Placement bounds) {
    Objects.requireNonNull(host);
    Objects.requireNonNull(bounds);
    if (bounds.x() < 0
        || bounds.y() < 0
        || bounds.width() < 1
        || bounds.height() < 1
        || (long) bounds.x() + bounds.width() > host.screen().width
        || (long) bounds.y() + bounds.height() > host.screen().height)
      throw new IllegalArgumentException("The mounted view must fit its actual native host");
    this.host = Objects.requireNonNull(host);
    minecraft = Minecraft.getInstance();
    font = host.font();
    left = bounds.x();
    top = bounds.y();
    width = bounds.width();
    height = bounds.height();
    init();
  }

  protected final Screen ownerScreen() {
    return Objects.requireNonNull(host, "The view is not mounted").screen();
  }

  protected final boolean isCurrent() {
    return host != null && host.current(this);
  }

  protected final <W extends GuiEventListener & Renderable & NarratableEntry> W addRenderableWidget(
      W widget) {
    return host.add(widget);
  }

  protected final void removeWidget(GuiEventListener widget) {
    host.remove(widget);
  }

  protected final GuiEventListener getFocused() {
    return host.focused();
  }

  protected final void setFocused(GuiEventListener widget) {
    host.focus(widget);
  }

  protected void init() {}

  protected void rebuildWidgets() {
    host.rebuild();
  }

  protected void beforeResize() {}

  protected void setInitialFocus() {
    host.initialFocus();
  }

  protected final boolean hasControlDown() {
    return host.controlDown();
  }

  protected final boolean hasShiftDown() {
    return host.shiftDown();
  }

  protected final boolean closeThroughHost() {
    return host.close(this);
  }

  protected final void openScreen(Screen next) {
    // Minecraft uses null to return to the world or choose its native title screen.
    host.navigate(next);
  }

  protected final void information(FieldPath path, Component label) {
    host.information(this, path, label);
  }

  boolean ownsFocus(GuiEventListener focused) {
    return false;
  }

  public void tick() {}

  public void render(FormCanvas canvas, int x, int y, float delta) {}

  public Component getNarrationMessage() {
    return title.copy();
  }

  public boolean keyPressed(int key, int scan, int modifiers) {
    return host.nativeKeyPressed(key, scan, modifiers);
  }

  public void onClose() {}

  public void removed() {}
}
