package example.reform.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/** Version-neutral fixture traversal; actual input is dispatched through the native game seam. */
final class PlatformWidgets {
  static List<AbstractWidget> widgets(ContainerEventHandler parent) {
    var found = new ArrayList<AbstractWidget>();
    for (var child : parent.children()) {
      if (child instanceof AbstractWidget widget) found.add(widget);
      if (child instanceof ContainerEventHandler nested) found.addAll(widgets(nested));
    }
    return found;
  }

  static boolean focus(ContainerEventHandler parent, GuiEventListener target) {
    for (var child : parent.children()) {
      if (child == target) {
        parent.setFocused(child);
        return true;
      }
      if (child instanceof ContainerEventHandler nested && widgets(nested).contains(target)) {
        parent.setFocused(child);
        return focus(nested, target);
      }
    }
    return false;
  }

  static Button button(ContainerEventHandler parent, String label) {
    return widgets(parent).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing native button: " + label));
  }

  /** Match native indentation/expansion decoration without bypassing its pointer interaction. */
  static Button navigation(Screen screen, String label) {
    return widgets(screen).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(
            widget ->
                widget.getMessage().getString().strip().replaceFirst("^[+−] ", "").equals(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing native navigation: " + label));
  }

  /** Reach a real owner action through More, without invoking business services. */
  static Button ownerAction(Screen screen, String label) {
    if (widgets(screen).stream()
        .noneMatch(
            widget -> widget instanceof Button && widget.getMessage().getString().equals(label))) {
      var more = button(screen, "More…");
      if (!focus(screen, more)) throw new AssertionError("More is outside the native focus tree");
      click(screen, more);
    }
    var action = button(screen, label);
    if (!focus(screen, action))
      throw new AssertionError("Owner action is outside the native focus tree");
    return action;
  }

  static EditBox input(ContainerEventHandler parent, String label) {
    return widgets(parent).stream()
        .filter(EditBox.class::isInstance)
        .map(EditBox.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing native input: " + label));
  }

  static void replace(Screen screen, EditBox input, String value) {
    if (!focus(screen, input)) throw new AssertionError("Input is not in native focus tree");
    input.moveCursorToEnd(false);
    input.setHighlightPos(0);
    if (value.isEmpty() && !input.getValue().isEmpty()) {
      NativeClientProbe.key(screen, "key.keyboard.backspace", true);
      NativeClientProbe.key(screen, "key.keyboard.backspace", false);
    }
    for (char character : value.toCharArray())
      if (!NativeClientProbe.character(screen, character))
        throw new AssertionError("Native text event was not consumed");
  }

  static void click(Screen screen, AbstractWidget button) {
    if (!button.active || !button.visible || !NativeClientProbe.click(screen, button))
      throw new AssertionError("Native button was not usable: " + button.getMessage().getString());
  }

  static void geometry(Screen screen) {
    var buttons =
        screen.children().stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .toList();
    for (var button : buttons) {
      if (button.getX() < 0
          || button.getY() < 0
          || button.getRight() > screen.width
          || button.getBottom() > screen.height)
        throw new AssertionError("Footer outside native screen bounds");
      for (var other : buttons)
        if (button != other
            && !(button.getRight() <= other.getX()
                || other.getRight() <= button.getX()
                || button.getBottom() <= other.getY()
                || other.getBottom() <= button.getY()))
          throw new AssertionError("Native footer controls overlap");
    }
  }

  private PlatformWidgets() {}
}
