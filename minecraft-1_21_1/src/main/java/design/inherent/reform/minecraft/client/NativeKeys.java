package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.KeyBindingOptions.Modifier;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Minecraft 1.21.1 GLFW keysym/scancode and zero-based mouse admission. */
public final class NativeKeys {
  public static boolean validInput(int key, int scan) {
    return key == GLFW.GLFW_KEY_UNKNOWN
        ? scan >= 0 && scan <= 65535
        : key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST;
  }

  public static InputConstants.Key input(int key, int scan) {
    return InputConstants.getKey(key, scan);
  }

  public static boolean ignoreRelease(int key, int scan) {
    return Minecraft.ON_OSX && scan == 63;
  }

  public static boolean validMouse(int button) {
    return button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST;
  }

  public static InputConstants.Key parse(String name) {
    if (name.startsWith("scancode.") && !numeric(name.substring(9), 0, 65535)
        || name.startsWith("key.mouse.")
            && name.substring(10).matches("[+-]?[0-9]+")
            && !numeric(name.substring(10), 1, GLFW.GLFW_MOUSE_BUTTON_LAST + 1)
        || name.startsWith("key.keyboard.")
            && name.substring(13).matches("[+-]?[0-9]+")
            && !(name.length() == 14 && name.charAt(13) >= '0' && name.charAt(13) <= '9')
            && !name.substring(13).equals("-1")
            && !numeric(name.substring(13), GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LAST))
      throw new IllegalArgumentException(
          "Native numeric key exceeds the Minecraft 1.21.1 input range");
    var key = InputConstants.getKey(name);
    int code = key.getValue();
    boolean valid =
        switch (key.getType()) {
          case KEYSYM ->
              code == GLFW.GLFW_KEY_UNKNOWN
                  || code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST;
          case SCANCODE -> code >= 0 && code <= 65535;
          case MOUSE -> validMouse(code);
        };
    if (!valid) throw new IllegalArgumentException("Unsupported native input code");
    return key;
  }

  public static Modifier physicalModifier(InputConstants.Key key) {
    int code = key.getValue();
    if (key.getType() != InputConstants.Type.KEYSYM) return Modifier.NONE;
    if (code == InputConstants.KEY_LSHIFT || code == InputConstants.KEY_RSHIFT)
      return Modifier.SHIFT;
    if (code == InputConstants.KEY_LCONTROL || code == InputConstants.KEY_RCONTROL)
      return Modifier.CONTROL;
    if (code == InputConstants.KEY_LALT || code == InputConstants.KEY_RALT) return Modifier.ALT;
    if (code == InputConstants.KEY_LWIN || code == InputConstants.KEY_RWIN)
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
