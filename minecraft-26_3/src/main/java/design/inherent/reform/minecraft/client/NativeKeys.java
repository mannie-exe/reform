package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.KeyBindingOptions.Modifier;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLScancode;

/** Minecraft 26.3 uses SDL physical keyboard scancodes and one-based mouse buttons. */
public final class NativeKeys {
  public static boolean validInput(int key, int nativeCode) {
    return key > 0 && key < SDLScancode.SDL_SCANCODE_COUNT;
  }

  public static InputConstants.Key input(int key, int nativeCode) {
    return InputConstants.getKey(new KeyEvent(key, nativeCode, 0));
  }

  public static boolean ignoreRelease(int key, int nativeCode) {
    return false;
  }

  public static boolean validMouse(int button) {
    return button >= 1 && button <= 32;
  }

  public static InputConstants.Key parse(String name) {
    if (name.startsWith("key.keyboard.")
            && name.substring(13).matches("[+-]?[0-9]+")
            && !(name.length() == 14 && name.charAt(13) >= '0' && name.charAt(13) <= '9')
            && !numeric(name.substring(13), 1, SDLScancode.SDL_SCANCODE_COUNT - 1)
        || name.startsWith("key.mouse.")
            && name.substring(10).matches("[+-]?[0-9]+")
            && !numeric(name.substring(10), 1, 32))
      throw new IllegalArgumentException(
          "Native numeric key exceeds the Minecraft 26.3 input range");
    var key = InputConstants.getKey(name);
    boolean valid =
        switch (key.getType()) {
          case KEYBOARD -> key.equals(InputConstants.UNKNOWN) || validInput(key.getValue(), 0);
          case MOUSE -> validMouse(key.getValue());
        };
    if (!valid) throw new IllegalArgumentException("Unsupported native input code");
    return key;
  }

  public static Modifier physicalModifier(InputConstants.Key key) {
    int code = key.getValue();
    if (key.getType() != InputConstants.Type.KEYBOARD) return Modifier.NONE;
    if (code == InputConstants.KEY_LSHIFT || code == InputConstants.KEY_RSHIFT)
      return Modifier.SHIFT;
    if (code == InputConstants.KEY_LCONTROL || code == InputConstants.KEY_RCONTROL)
      return Modifier.CONTROL;
    if (code == InputConstants.KEY_LALT || code == InputConstants.KEY_RALT) return Modifier.ALT;
    if (code == InputConstants.KEY_LGUI || code == InputConstants.KEY_RGUI)
      return Modifier.CONTROL_OR_COMMAND;
    return Modifier.NONE;
  }

  private static boolean numeric(String value, int minimum, int maximum) {
    if (value.isEmpty() || value.length() > 8 || !value.matches("[0-9]+")) return false;
    try {
      int code = Integer.parseInt(value);
      return code >= minimum && code <= maximum;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private NativeKeys() {}
}
