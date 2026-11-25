package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.client.ui.UiComponent;
import design.inherent.reform.minecraft.client.ui.UiRowFailure;
import design.inherent.reform.presentation.FormGeometry;
import design.inherent.reform.presentation.ui.UiActionResult;
import design.inherent.reform.presentation.ui.UiLimits;
import design.inherent.reform.presentation.ui.UiScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;

/**
 * A native component viewport for a caller-owned screen. Add this one stable child to the native
 * screen, call update on its host turn and reflow on resize. Neither mounting nor closing creates
 * or closes a document/workflow, and rendering never refreshes observations or invokes a factory.
 */
public final class ClientUiMount extends NativeViewport implements AutoCloseable {
  private final ClientViewContext context;
  private final MountedUiTree tree;
  private FormPanel panel;
  private FormGeometry.Placement bounds;
  private ClientTheme theme;
  private boolean visible = true;

  private ClientUiMount(ClientViewContext context, MountedUiTree tree) {
    this.context = context;
    this.tree = tree;
    tree.scope()
        .onRetire(
            () -> {
              if (panel != null) panel.cancelPointerCapture();
              setDragging(false);
              panel = null;
              visible = false;
              setFocused((GuiEventListener) null);
            });
  }

  public static <P> ClientUiMount mount(
      ClientViewContext context,
      UiLimits limits,
      UiComponent<P> component,
      P props,
      FormGeometry.Placement bounds,
      ClientTheme theme) {
    Objects.requireNonNull(context).requireCurrent();
    design.inherent.reform.minecraft.internal.ClientSettingsRuntime.requireClientThread();
    var tree = MountedUiTree.open(context, limits, component, props);
    try {
      var mounted = new ClientUiMount(context, tree);
      mounted.reflow(bounds, theme);
      return mounted;
    } catch (RuntimeException | Error failed) {
      try {
        tree.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failed) failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  public UiScope scope() {
    return tree.scope();
  }

  public boolean isOpen() {
    return tree.isOpen();
  }

  public Optional<UiActionResult> lastAction() {
    return tree.lastAction();
  }

  public List<UiRowFailure> rowFailures() {
    return tree.rowFailures();
  }

  /** Explicit cached-observation/update boundary; never call this from paint or measurement. */
  public void update() {
    context.requireCurrent();
    if (visible && tree.refresh(false)) reflow(bounds, theme);
  }

  /** Suspension preserves local data while removing native input/render participation. */
  public void visible(boolean value) {
    context.requireCurrent();
    if (visible == value) return;
    visible = value;
    if (!value) {
      tree.hide();
      if (panel != null) panel.cancelPointerCapture();
      setDragging(false);
      setFocused((GuiEventListener) null);
    } else {
      tree.refresh(false);
      reflow(bounds, theme);
    }
  }

  public void reflow(FormGeometry.Placement bounds, ClientTheme theme) {
    context.requireCurrent();
    tree.scope().requireCurrent();
    Objects.requireNonNull(bounds);
    Objects.requireNonNull(theme);
    if (bounds.width() < 1 || bounds.height() < 1)
      throw new IllegalArgumentException("A component viewport needs a positive measured offer");
    var old = panel;
    var bookmark = old == null ? null : old.bookmark();
    String focus = old == null ? null : old.rememberedFocusKey();
    boolean wasFocused = old != null && getFocused() == old;
    var next =
        new FormPanel(
            Minecraft.getInstance(),
            bounds.x(),
            bounds.y(),
            bounds.width(),
            bounds.height(),
            false,
            theme);
    tree.append(
        next,
        theme,
        effect ->
            () -> {
              if (visible && isOpen()) effect.run();
            },
        () -> reflow(this.bounds, this.theme),
        () -> setFocused(next));
    next.finish(0);
    if (bookmark != null) next.restore(bookmark);
    next.restoreFocus(focus);
    this.bounds = bounds;
    this.theme = theme;
    if (old != null) old.cancelPointerCapture();
    panel = next;
    if (wasFocused) setFocused(next);
  }

  @Override
  public List<? extends GuiEventListener> children() {
    return visible && isOpen() && panel != null ? List.of(panel) : List.of();
  }

  @Override
  public boolean isMouseOver(double x, double y) {
    return visible && isOpen() && panel != null && panel.isMouseOver(x, y);
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    if (listener != null && !children().contains(listener)) return;
    super.setFocused(listener);
  }

  @Override
  public void render(FormCanvas canvas, int x, int y, float delta) {
    if (!visible || !isOpen() || panel == null) return;
    context.checkNativeThread();
    panel.render(canvas, x, y, delta);
  }

  @Override
  public NarrationPriority narrationPriority() {
    return visible && isOpen() && panel != null
        ? panel.narrationPriority()
        : NarrationPriority.NONE;
  }

  @Override
  public void updateNarration(NarrationElementOutput output) {
    if (visible && isOpen() && panel != null) panel.updateNarration(output);
  }

  @Override
  public void close() {
    tree.close();
  }
}
