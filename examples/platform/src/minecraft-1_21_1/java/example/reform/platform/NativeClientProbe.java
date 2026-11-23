package example.reform.platform;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

final class NativeClientProbe {
  static net.minecraft.client.gui.components.EditBox rawTextAlphaInput(
      net.minecraft.client.gui.Font font, int x, int y) {
    var input =
        new net.minecraft.client.gui.components.EditBox(
            font,
            x,
            y,
            160,
            20,
            net.minecraft.network.chat.Component.literal("Raw native alpha reference"));
    input.setValue("Example");
    input.setTextColor(0x03ffffff);
    input.setFormatter(
        (text, offset) ->
            net.minecraft.network.chat.Component.literal(text)
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true))
                .getVisualOrderText());
    return input;
  }

  static boolean normalizesSmallTextAlpha() {
    return true;
  }

  static java.util.concurrent.Executor ioPool() {
    return net.minecraft.Util.ioPool();
  }

  static boolean ready(Minecraft client) {
    return client.getOverlay() == null;
  }

  static boolean character(Screen screen, char value) {
    return screen.charTyped(value, 0);
  }

  static KeyMapping mapping(String name) {
    return new KeyMapping(name, InputConstants.KEY_G, "key.categories.misc");
  }

  static KeyMapping mapping(String name, String key) {
    return new KeyMapping(name, InputConstants.getKey(key).getValue(), "key.categories.misc");
  }

  static void size(Minecraft client, int[] width, int[] height) {
    GLFW.glfwGetWindowSize(client.getWindow().getWindow(), width, height);
  }

  static boolean click(Screen screen, AbstractWidget widget) {
    var x = widget.getX() + widget.getWidth() / 2.0;
    var y = widget.getY() + widget.getHeight() / 2.0;
    boolean consumed = screen.mouseClicked(x, y, 0);
    screen.mouseReleased(x, y, 0);
    return consumed;
  }

  static void pressRetired(net.minecraft.client.gui.components.Button button) {
    button.onPress();
  }

  static String narration(
      java.util.function.Consumer<net.minecraft.client.gui.narration.NarrationElementOutput>
          source) {
    var collector = new net.minecraft.client.gui.narration.ScreenNarrationCollector();
    collector.update(source);
    return collector.collectNarrationText(true);
  }

  static boolean key(Screen screen, String name, boolean pressed) {
    int code = InputConstants.getKey(name).getValue();
    return pressed ? screen.keyPressed(code, 0, 0) : screen.keyReleased(code, 0, 0);
  }

  static boolean pointerDown(Screen screen, double x, double y) {
    return screen.mouseClicked(x, y, 0);
  }

  static void pointerUp(Screen screen, double x, double y) {
    screen.mouseReleased(x, y, 0);
  }

  static boolean drag(Screen screen, double x, double y, double dx, double dy) {
    return screen.mouseDragged(x, y, 0, dx, dy);
  }

  static void press(AbstractButton button) {
    button.onPress();
  }

  static void clickSlider(AbstractSliderButton slider) {
    slider.onClick(slider.getX() + 8, slider.getY() + 8);
  }

  static void resize(Minecraft client, int width, int height) {
    GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width, height);
  }

  static void reflow(Minecraft client) {
    client.resizeDisplay();
  }

  static void mouse(Screen screen, int button) {
    screen.mouseClicked(2, 2, button);
    screen.mouseReleased(2, 2, button);
  }

  static CompletableFuture<Void> capture(Minecraft client, Path file) {
    var result = new CompletableFuture<Void>();
    try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
      image.writeToFile(file);
      result.complete(null);
    } catch (Exception failed) {
      result.completeExceptionally(failed);
    }
    return result;
  }

  private NativeClientProbe() {}
}
