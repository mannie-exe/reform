package example.reform.platform;

import design.inherent.reform.minecraft.client.NativeScreens;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/** Version-neutral fixture traversal; actual input is dispatched through the native game seam. */
final class PlatformWidgets {
  private static long textEvents,
      textNanos,
      textMaximum,
      pointerEvents,
      pointerNanos,
      pointerMaximum;

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

  /** Explicit auxiliary document actions replaced the old in-body action mode. */
  static Screen actionHost(Screen root) {
    var current = NativeScreens.current(Minecraft.getInstance());
    return current != null && current.getClass().getSimpleName().equals("DocumentActionsScreen")
        ? current
        : root;
  }

  static void returnToSettings(Screen root) {
    var host = actionHost(root);
    if (host != root) click(host, button(host, "Back to settings"));
    if (NativeScreens.current(Minecraft.getInstance()) != root)
      throw new AssertionError("Document actions did not return to their retained owner");
  }

  /** Reach a real owner action through native navigation, without invoking business services. */
  static Button ownerAction(Screen screen, String label) {
    var host = actionHost(screen);
    if (widgets(host).stream()
        .noneMatch(
            widget -> widget instanceof Button && widget.getMessage().getString().equals(label))) {
      returnToSettings(screen);
      if (widgets(screen).stream()
          .noneMatch(
              widget ->
                  widget instanceof Button && widget.getMessage().getString().equals(label))) {
        var open = button(screen, "Document actions…");
        if (!focus(screen, open))
          throw new AssertionError("Actions are outside the native focus tree");
        click(screen, open);
      }
      host = actionHost(screen);
    }
    var selectedHost = host;
    var action =
        widgets(host).stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(widget -> widget.getMessage().getString().equals(label))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Missing owner action "
                            + label
                            + " in "
                            + selectedHost.getClass().getSimpleName()
                            + "; narration="
                            + selectedHost.getNarrationMessage().getString()
                            + "; controls="
                            + widgets(selectedHost).stream()
                                .map(
                                    widget ->
                                        widget.getMessage().getString()
                                            + " (active="
                                            + widget.active
                                            + ")")
                                .toList()));
    if (!focus(host, action))
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
    for (char character : value.toCharArray()) {
      long start = System.nanoTime();
      boolean consumed = NativeClientProbe.character(screen, character);
      long elapsed = System.nanoTime() - start;
      textEvents++;
      textNanos += elapsed;
      textMaximum = Math.max(textMaximum, elapsed);
      if (!consumed) throw new AssertionError("Native text event was not consumed");
    }
  }

  static void click(Screen screen, AbstractWidget button) {
    var host = actionHost(screen);
    if (host != screen && widgets(host).contains(button)) screen = host;
    long start = System.nanoTime();
    boolean consumed = button.active && button.visible && NativeClientProbe.click(screen, button);
    long elapsed = System.nanoTime() - start;
    pointerEvents++;
    pointerNanos += elapsed;
    pointerMaximum = Math.max(pointerMaximum, elapsed);
    if (!consumed)
      throw new AssertionError("Native button was not usable: " + button.getMessage().getString());
  }

  static com.google.gson.JsonObject inputCosts() {
    var result = new com.google.gson.JsonObject();
    result.addProperty(
        "scope",
        "Synchronous fixture-dispatched native callbacks in this JVM; excludes async work, rendering and physical input");
    result.addProperty("textEvents", textEvents);
    result.addProperty(
        "textMeanMillis", textEvents == 0 ? 0 : textNanos / (double) textEvents / 1e6);
    result.addProperty("textMaximumMillis", textMaximum / 1e6);
    result.addProperty("pointerEvents", pointerEvents);
    result.addProperty(
        "pointerMeanMillis", pointerEvents == 0 ? 0 : pointerNanos / (double) pointerEvents / 1e6);
    result.addProperty("pointerMaximumMillis", pointerMaximum / 1e6);
    return result;
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
    measuredPanels(screen);
  }

  /** Read-only geometry witness over the actual measured native panel, not another layout. */
  static com.google.gson.JsonObject measuredPanels(ContainerEventHandler root) {
    var result = new com.google.gson.JsonObject();
    int rows = 0, fields = 0, inline = 0, stacked = 0, groups = 0;
    for (var child : root.children()) {
      if (!(child instanceof ContainerEventHandler container)) continue;
      if (!child.getClass().getSimpleName().equals("FormPanel")) {
        measuredPanels(container);
        continue;
      }
      var layouts = (List<?>) observed(child, "layout");
      var positions = (List<?>) observed(child, "positions");
      var outlines = (List<?>) observed(child, "outlines");
      var relationships = new java.util.HashMap<Object, List<Object>>();
      groups += outlines.size();
      var font = Minecraft.getInstance().font;
      for (var layout : layouts) {
        rows++;
        var row = observed(layout, "row");
        if (!(boolean) observed(row, "field")) continue;
        var relationship = observed(row, "relationship");
        if (relationship != null)
          relationships.computeIfAbsent(relationship, ignored -> new ArrayList<>()).add(layout);
        fields++;
        boolean isStacked = (boolean) observed(layout, "stacked");
        if (isStacked) stacked++;
        else inline++;
        int x = (int) observed(layout, "x"), y = (int) observed(layout, "y");
        int width = (int) observed(layout, "textWidth");
        int textY = y + (int) observed(layout, "textY");
        var label = (net.minecraft.network.chat.Component) observed(row, "label");
        var help = (net.minecraft.network.chat.Component) observed(row, "summary");
        int textBottom = textY;
        if (!label.getString().isEmpty())
          textBottom += font.split(label, width).size() * font.lineHeight;
        if (!help.getString().isEmpty())
          textBottom =
              textY
                  + (int) observed(layout, "summaryY")
                  + font.split(help, width).size() * font.lineHeight;
        var rowWidgets = (List<?>) observed(row, "controls");
        for (var at : positions) {
          var widget = (AbstractWidget) observed(at, "widget");
          if (!rowWidgets.contains(widget)) continue;
          int ax = (int) observed(at, "x"), ay = (int) observed(at, "y");
          if (ax < x + width
              && x < ax + widget.getWidth()
              && ay < textBottom
              && textY < ay + widget.getHeight())
            throw new AssertionError("Native field text overlaps control: " + label.getString());
        }
      }
      for (var members : relationships.values()) {
        if (members.size() < 2) continue;
        long horizontalLines = members.stream().map(at -> observed(at, "y")).distinct().count();
        long verticalLines = members.stream().map(at -> observed(at, "x")).distinct().count();
        if (horizontalLines != 1 && !(verticalLines == 1 && horizontalLines == members.size()))
          throw new AssertionError("Native relationship partially collapsed instead of stacking");
      }
      for (int i = 0; i < positions.size(); i++) {
        var first = positions.get(i);
        var a = (AbstractWidget) observed(first, "widget");
        int ax = (int) observed(first, "x"), ay = (int) observed(first, "y");
        for (int j = i + 1; j < positions.size(); j++) {
          var second = positions.get(j);
          var b = (AbstractWidget) observed(second, "widget");
          int bx = (int) observed(second, "x"), by = (int) observed(second, "y");
          if (ax < bx + b.getWidth()
              && bx < ax + a.getWidth()
              && ay < by + b.getHeight()
              && by < ay + a.getHeight())
            throw new AssertionError(
                "Native panel controls overlap: "
                    + a.getMessage().getString()
                    + " / "
                    + b.getMessage().getString());
        }
      }
    }
    result.addProperty("rows", rows);
    result.addProperty("fields", fields);
    result.addProperty("inlineFields", inline);
    result.addProperty("stackedFields", stacked);
    result.addProperty("groups", groups);
    result.addProperty("noTextControlOrControlOverlap", true);
    result.addProperty("wholeRelationshipsUseColumnsOrStack", true);
    return result;
  }

  /** Count real measured outlines enclosing this control, in their common content coordinates. */
  static int enclosingGroups(ContainerEventHandler root, AbstractWidget target) {
    for (var child : root.children()) {
      if (!(child instanceof ContainerEventHandler container)) continue;
      if (!child.getClass().getSimpleName().equals("FormPanel")) {
        int found = enclosingGroups(container, target);
        if (found > 0) return found;
        continue;
      }
      for (var position : (List<?>) observed(child, "positions")) {
        if (observed(position, "widget") != target) continue;
        int x = (int) observed(position, "x"), y = (int) observed(position, "y");
        int result = 0;
        for (var outline : (List<?>) observed(child, "outlines")) {
          int left = (int) observed(outline, "left"), width = (int) observed(outline, "width");
          int top = (int) observed(outline, "top"), bottom = (int) observed(outline, "bottom");
          if (x >= left
              && (long) x + target.getWidth() < (long) left + width
              && y >= top
              && (long) y + target.getHeight() <= bottom) result++;
        }
        return result;
      }
    }
    return 0;
  }

  static String relationshipFor(ContainerEventHandler root, AbstractWidget field) {
    for (var child : root.children()) {
      if (!(child instanceof ContainerEventHandler container)) continue;
      if (child.getClass().getSimpleName().equals("FormPanel")) {
        for (var layout : (List<?>) observed(child, "layout")) {
          var row = observed(layout, "row");
          if (((List<?>) observed(row, "controls")).contains(field))
            return (String) observed(row, "relationship");
        }
      } else {
        var found = relationshipFor(container, field);
        if (found != null) return found;
      }
    }
    return null;
  }

  static AbstractWidget informationFor(ContainerEventHandler root, AbstractWidget field) {
    for (var child : root.children()) {
      if (!(child instanceof ContainerEventHandler container)) continue;
      if (child.getClass().getSimpleName().equals("FormPanel")) {
        for (var layout : (List<?>) observed(child, "layout")) {
          var controls = (List<?>) observed(observed(layout, "row"), "controls");
          if (controls.contains(field))
            return controls.stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .filter(widget -> widget.getClass().getSimpleName().equals("InformationButton"))
                .findFirst()
                .orElseThrow();
        }
      } else {
        var found = informationFor(container, field);
        if (found != null) return found;
      }
    }
    return null;
  }

  static Object observed(Object instance, String field) {
    try {
      var declared = instance.getClass().getDeclaredField(field);
      declared.setAccessible(true);
      return declared.get(instance);
    } catch (ReflectiveOperationException failed) {
      throw new AssertionError("Native geometry observation is unavailable: " + field, failed);
    }
  }

  static List<String> measuredText(ContainerEventHandler root) {
    var text = new ArrayList<String>();
    for (var child : root.children()) {
      if (!(child instanceof ContainerEventHandler container)) continue;
      if (child.getClass().getSimpleName().equals("FormPanel")) {
        for (var layout : (List<?>) observed(child, "layout")) {
          var row = observed(layout, "row");
          text.add(((net.minecraft.network.chat.Component) observed(row, "label")).getString());
          text.add(((net.minecraft.network.chat.Component) observed(row, "summary")).getString());
        }
      } else text.addAll(measuredText(container));
    }
    return List.copyOf(text);
  }

  private PlatformWidgets() {}
}
