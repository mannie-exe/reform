package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.FormGeometry;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;

/** Native horizontal text reflow without changing the caret, selection or draft. */
final class ControlSizing {
  // Native views may retain a widget through reflow. Assigned bounds are not its intrinsic offer.
  // Values do not retain keys; abandoned widgets and their measurements can be collected together.
  private static final Map<AbstractWidget, FormGeometry.Size> PREFERRED = new WeakHashMap<>();

  static FormGeometry.Size preferred(AbstractWidget widget) {
    return PREFERRED.computeIfAbsent(
        widget, key -> new FormGeometry.Size(key.getWidth(), key.getHeight()));
  }

  static void offer(AbstractWidget widget, int width, int height) {
    PREFERRED.put(widget, new FormGeometry.Size(width, height));
  }

  static java.util.Set<AbstractWidget> nativeTree(AbstractWidget root) {
    var seen =
        java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<
                net.minecraft.client.gui.components.events.GuiEventListener, Boolean>());
    var widgets =
        java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<AbstractWidget, Boolean>());
    var pending =
        new java.util.ArrayDeque<net.minecraft.client.gui.components.events.GuiEventListener>();
    pending.push(root);
    while (!pending.isEmpty()) {
      var entry = pending.pop();
      if (!seen.add(entry) || seen.size() > 64)
        throw new IllegalArgumentException(
            "A custom field control needs a bounded nonshared native tree");
      if (entry instanceof AbstractWidget widget) widgets.add(widget);
      if (entry instanceof net.minecraft.client.gui.components.events.ContainerEventHandler group)
        for (var child : group.children()) pending.push(child);
    }
    return widgets;
  }

  static void custom(AbstractWidget root, java.util.Collection<AbstractWidget> tree) {
    var style = (NativeControlStyle) root;
    style.reform$controlGeometry(
        tree == null ? null : new NativeControlStyle.ControlGeometry(java.util.List.copyOf(tree)));
    style.reform$controlGeometryFailure(null);
  }

  static void own(AbstractWidget widget, Object owner) {
    var style = (NativeControlStyle) widget;
    if (style.reform$controlOwner() != null && style.reform$controlOwner() != owner)
      throw new IllegalArgumentException(
          "A native control belongs to another field or retired binding");
    style.reform$controlOwner(owner);
  }

  static void protect(AbstractWidget widget) {
    if (widget instanceof NativeControlStyle style && style.reform$controlOwner() == null)
      style.reform$controlOwner(new Object());
  }

  /** Called after actual placement and before dispatch; healthy placement allocates no tree. */
  static RuntimeException geometryFailure(AbstractWidget root, boolean checkTree) {
    if (!(root instanceof NativeControlStyle style)) return null;
    var geometry = style.reform$controlGeometry();
    if (geometry == null) return null;
    if (style.reform$controlGeometryFailure() != null) return style.reform$controlGeometryFailure();
    try {
      if (root.getWidth() < 1 || root.getHeight() < 1)
        throw new IllegalArgumentException("The custom control has no usable native bounds");
      if (checkTree) {
        var current = nativeTree(root);
        if (current.size() != geometry.tree().size() || !current.containsAll(geometry.tree()))
          throw new IllegalArgumentException("The custom control changed its admitted native tree");
      }
      for (var child : geometry.tree()) {
        if (child == root) continue;
        if (child.getWidth() < 1
            || child.getHeight() < 1
            || child.getX() < root.getX()
            || child.getY() < root.getY()
            || (long) child.getX() + child.getWidth() > (long) root.getX() + root.getWidth()
            || (long) child.getY() + child.getHeight() > (long) root.getY() + root.getHeight())
          throw new IllegalArgumentException(
              "A custom control's native child must fit its measured root");
      }
      return null;
    } catch (RuntimeException failed) {
      style.reform$controlGeometryFailure(failed);
      root.active = false;
      root.visible = false;
      return failed;
    }
  }

  static void resize(AbstractWidget widget, int width) {
    preferred(widget);
    if (widget.getWidth() == width) return;
    widget.setWidth(width);
    if (widget instanceof EditBox input) {
      int cursor = input.getCursorPosition();
      int selection = input instanceof FormEditBox form ? form.selection() : cursor;
      input.setCursorPosition(0);
      input.setCursorPosition(cursor);
      input.setHighlightPos(selection);
    }
  }

  private ControlSizing() {}
}
