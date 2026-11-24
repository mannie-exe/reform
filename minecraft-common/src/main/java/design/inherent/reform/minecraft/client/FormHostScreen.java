package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.presentation.FormGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** One native screen owns widgets/input while an actual retained presentation is mounted. */
abstract class FormHostScreen extends NativeFormScreen {
  private FormView mounted;
  private final List<FormView> views = new ArrayList<>();
  private boolean ticking, rebuildRequested, handingOff;
  private boolean presentationBorrowed;

  final void sealPresentation() {
    presentationBorrowed = true;
  }

  @Override
  protected final boolean presentationSealed() {
    return presentationBorrowed;
  }

  private Runnable deferredNavigation;
  private final FormView.Host host =
      new FormView.Host() {
        public Screen screen() {
          return FormHostScreen.this;
        }

        public Font font() {
          return FormHostScreen.this.font;
        }

        public ClientTheme theme() {
          return FormHostScreen.this.theme();
        }

        public boolean current(FormView candidate) {
          return views.contains(candidate)
              && minecraft != null
              && NativeScreens.current(minecraft) == FormHostScreen.this;
        }

        public <W extends GuiEventListener & Renderable & NarratableEntry> W add(W widget) {
          return FormHostScreen.super.addRenderableWidget(widget);
        }

        public void remove(GuiEventListener widget) {
          FormHostScreen.this.removeWidget(widget);
        }

        public GuiEventListener focused() {
          return FormHostScreen.this.getFocused();
        }

        public void focus(GuiEventListener widget) {
          FormHostScreen.this.setFocused(widget);
        }

        public void rebuild() {
          requestRebuild();
        }

        public void initialFocus() {
          FormHostScreen.super.setInitialFocus();
        }

        public boolean nativeKeyPressed(int key, int scan, int modifiers) {
          return FormHostScreen.super.keyPressed(key, scan, modifiers);
        }

        public boolean controlDown() {
          return nativeControlDown();
        }

        public boolean shiftDown() {
          return nativeShiftDown();
        }

        public boolean close(FormView view) {
          return closeMountedView(view);
        }

        public void navigate(Screen next) {
          FormHostScreen.this.navigate(next);
        }

        public void information(FormView view, FieldPath path, Component label) {
          if (current(view) && view instanceof SettingsView<?> settings)
            showInformation(settings, path, label);
        }
      };

  protected FormHostScreen(Component title) {
    super(title);
  }

  protected FormHostScreen(Component title, Screen parent) {
    super(title, parent);
  }

  protected final FormView mountedView() {
    var focused = focusedView();
    if (focused != null) mounted = focused;
    return mounted;
  }

  /** Actual native focus, independently of the fallback used to route screen-level keys. */
  protected final FormView focusedView() {
    for (var view : views) if (view.ownsFocus(getFocused())) return view;
    return null;
  }

  protected final void mount(FormView view) {
    mount(view, new FormGeometry.Placement(0, 0, width, height));
  }

  protected final void mount(FormView view, FormGeometry.Placement bounds) {
    if (!views.contains(view)) views.add(view);
    if (mounted == null) mounted = view;
    view.mount(host, bounds);
  }

  /** One root handoff retains every composed owner, including owners other than the initiator. */
  protected final void navigate(Screen next) {
    if (next instanceof NativeFormScreen themed && next != this) {
      themed.inheritTheme(theme());
      themed.inheritFallbackTasks(this);
    }
    if (managedNavigation(next)) return;
    if (minecraft != null && NativeScreens.current(minecraft) == this && next != this)
      beforeRebuild();
    handingOff = true;
    try {
      NativeScreens.open(minecraft, next);
    } finally {
      handingOff = false;
    }
  }

  /** Adopt a known shared contribution without replacing this actual native screen. */
  protected boolean managedNavigation(Screen next) {
    return false;
  }

  protected final boolean handingOff() {
    return handingOff;
  }

  /** A foreign public API may replace the native screen itself. Keep every composed owner. */
  protected final <T> T externalHandoff(java.util.function.Supplier<T> open) {
    beforeRebuild();
    handingOff = true;
    try {
      return open.get();
    } finally {
      handingOff = false;
    }
  }

  protected boolean closeMountedView(FormView view) {
    return false;
  }

  protected void showInformation(SettingsView<?> view, FieldPath path, Component label) {}

  protected void beforeRebuild() {}

  protected final void suspendView() {
    mounted = null;
    views.clear();
  }

  /** Coalesce the latest selection until mounted view ticks and their rebuilds have settled. */
  protected final boolean deferNavigation(Runnable selection) {
    if (!ticking) return false;
    deferredNavigation = java.util.Objects.requireNonNull(selection);
    return true;
  }

  protected final void cancelDeferredNavigation() {
    deferredNavigation = null;
  }

  private void requestRebuild() {
    if (ticking) rebuildRequested = true;
    else {
      beforeRebuild();
      super.rebuildWidgets();
      if (!hasCurrentFocus()) setInitialFocus();
    }
  }

  private boolean nativeControlDown() {
    return hasControlDown();
  }

  private boolean nativeShiftDown() {
    return hasShiftDown();
  }

  @Override
  protected void rebuildWidgets() {
    requestRebuild();
  }

  @Override
  protected void beforeResize() {
    for (var view : List.copyOf(views)) view.beforeResize();
  }

  @Override
  protected void setInitialFocus() {
    if (hasCurrentFocus()) return;
    if (mounted == null) super.setInitialFocus();
    else mounted.setInitialFocus();
  }

  /** Re-entering a retained screen must not retain focus on a retired native container. */
  protected final boolean hasCurrentFocus() {
    if (getFocused() == null) return false;
    if (children().contains(getFocused())) return true;
    setFocused(null);
    return false;
  }

  @Override
  public void tick() {
    ticking = true;
    try {
      for (var view : List.copyOf(views)) view.tick();
    } finally {
      ticking = false;
    }
    if (rebuildRequested) {
      rebuildRequested = false;
      requestRebuild();
    }
    var selected = deferredNavigation;
    deferredNavigation = null;
    if (selected != null) selected.run();
  }

  @Override
  public void render(FormCanvas canvas, int x, int y, float delta) {
    super.render(canvas, x, y, delta);
    for (var view : views) view.render(canvas, x, y, delta);
  }

  @Override
  public Component getNarrationMessage() {
    return mounted == null ? super.getNarrationMessage() : mounted.getNarrationMessage();
  }

  @Override
  public boolean keyPressed(int key, int scan, int modifiers) {
    mountedView();
    return mounted == null
        ? super.keyPressed(key, scan, modifiers)
        : mounted.keyPressed(key, scan, modifiers);
  }

  @Override
  public void onFilesDrop(java.util.List<java.nio.file.Path> paths) {
    mountedView();
    if (mounted != null) mounted.onFilesDrop(paths);
  }

  @Override
  public void onClose() {
    if (mounted == null) super.onClose();
    else mounted.onClose();
  }

  @Override
  public void removed() {
    if (!handingOff) for (var view : List.copyOf(views)) view.removed();
  }
}
