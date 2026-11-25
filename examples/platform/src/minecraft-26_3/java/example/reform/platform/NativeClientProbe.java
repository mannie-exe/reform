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
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLVideo;

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
    input.addFormatter(
        (text, offset) ->
            net.minecraft.network.chat.Component.literal(text)
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withBold(true))
                .getVisualOrderText());
    return input;
  }

  static boolean normalizesSmallTextAlpha() {
    return false;
  }

  static java.util.concurrent.Executor ioPool() {
    return net.minecraft.util.Util.ioPool();
  }

  static boolean ready(Minecraft client) {
    return client.gui.overlay() == null;
  }

  static boolean character(Screen screen, char value) {
    return screen.charTyped(new CharacterEvent(value));
  }

  static KeyMapping mapping(String name) {
    return new KeyMapping(name, InputConstants.KEY_G, KeyMapping.Category.MISC);
  }

  static KeyMapping mapping(String name, String key) {
    return new KeyMapping(name, InputConstants.getKey(key).getValue(), KeyMapping.Category.MISC);
  }

  static void size(Minecraft client, int[] width, int[] height) {
    try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
      var nativeWidth = stack.mallocInt(1);
      var nativeHeight = stack.mallocInt(1);
      if (!SDLVideo.SDL_GetWindowSize(client.getWindow().handle(), nativeWidth, nativeHeight))
        throw new AssertionError("Native SDL window size unavailable");
      width[0] = nativeWidth.get(0);
      height[0] = nativeHeight.get(0);
    }
  }

  static boolean click(Screen screen, AbstractWidget widget) {
    var event =
        new MouseButtonEvent(
            widget.getX() + widget.getWidth() / 2.0,
            widget.getY() + widget.getHeight() / 2.0,
            new MouseButtonInfo(1, 0));
    boolean consumed = screen.mouseClicked(event, false);
    screen.mouseReleased(event);
    return consumed;
  }

  static void pressRetired(net.minecraft.client.gui.components.Button button) {
    button.onPress(
        new net.minecraft.client.input.KeyEvent(
            InputConstants.getKey("key.keyboard.enter").getValue(), 0, 0));
  }

  static boolean key(Screen screen, String name, boolean pressed) {
    var event = keyEvent(name);
    return pressed ? screen.keyPressed(event) : screen.keyReleased(event);
  }

  static String narration(
      java.util.function.Consumer<net.minecraft.client.gui.narration.NarrationElementOutput>
          source) {
    var collector = new net.minecraft.client.gui.narration.ScreenNarrationCollector();
    collector.update(source, net.minecraft.client.gui.narration.NarrationTrigger.KEYBOARD);
    return collector.collectNarrationText(true);
  }

  private static KeyEvent keyEvent(String name) {
    int scan = InputConstants.getKey(name).getValue();
    return new KeyEvent(scan, SDLKeyboard.SDL_GetKeyFromScancode(scan, (short) 0, false), 0);
  }

  private static MouseButtonEvent pointer(double x, double y) {
    return new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0));
  }

  static boolean pointerDown(Screen screen, double x, double y) {
    return screen.mouseClicked(pointer(x, y), false);
  }

  static void pointerUp(Screen screen, double x, double y) {
    screen.mouseReleased(pointer(x, y));
  }

  static boolean drag(Screen screen, double x, double y, double dx, double dy) {
    return screen.mouseDragged(pointer(x, y), dx, dy);
  }

  static void press(AbstractButton button) {
    button.onPress(keyEvent("key.keyboard.space"));
  }

  static void clickSlider(AbstractSliderButton slider) {
    slider.onClick(pointer(slider.getX() + 8, slider.getY() + 8), false);
  }

  static void resize(Minecraft client, int width, int height) {
    SDLVideo.SDL_SetWindowSize(client.getWindow().handle(), width, height);
  }

  /** Logical screen reflow only; this does not resize the OS window. */
  static void resizeScreen(Screen screen, int width, int height) {
    screen.resize(width, height);
  }

  static void reflow(Minecraft client) {
    client.resizeGui();
  }

  static void mouse(Screen screen, int button) {
    var event = new MouseButtonEvent(2, 2, new MouseButtonInfo(button, 0));
    screen.mouseClicked(event, false);
    screen.mouseReleased(event);
  }

  static CompletableFuture<Void> capture(Minecraft client, Path file) {
    var result = new CompletableFuture<Void>();
    Screenshot.takeScreenshot(
        client.gameRenderer.mainRenderTarget(),
        image -> {
          try (image) {
            image.writeToFile(file);
            result.complete(null);
          } catch (Exception failed) {
            result.completeExceptionally(failed);
          }
        });
    return result;
  }

  private NativeClientProbe() {}
}
