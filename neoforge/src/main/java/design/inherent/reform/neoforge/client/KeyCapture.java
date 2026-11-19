package design.inherent.reform.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Local input state only; capture never constructs or mutates a globally registered mapping. */
final class KeyCapture {
  private InputConstants.Key primary = InputConstants.UNKNOWN;
  private InputConstants.Key modifier = InputConstants.UNKNOWN;
  private boolean primaryHeld, modifierHeld;

  void press(int keyCode, int scanCode) {
    if (keyCode != GLFW.GLFW_KEY_UNKNOWN
            && (keyCode < GLFW.GLFW_KEY_SPACE || keyCode > GLFW.GLFW_KEY_LAST)
        || keyCode == GLFW.GLFW_KEY_UNKNOWN && (scanCode < 0 || scanCode > 65535)) return;
    var key = InputConstants.getKey(keyCode, scanCode);
    // A repeated modifier event must not replace an already captured primary key.
    if (modifier.equals(key)) {
      modifierHeld = true;
      return;
    }
    if (modifier.equals(InputConstants.UNKNOWN) && KeyModifier.isKeyCodeModifier(key)) {
      modifier = key;
      modifierHeld = true;
    } else {
      primary = key;
      primaryHeld = true;
    }
  }

  Optional<KeyBindingOptions.Binding> release(int keyCode, int scanCode) {
    if (Minecraft.ON_OSX && scanCode == 63) return Optional.empty();
    if (keyCode == GLFW.GLFW_KEY_ESCAPE)
      return Optional.of(
          new KeyBindingOptions.Binding(InputConstants.UNKNOWN.getName(), KeyModifier.NONE));
    if (keyCode != GLFW.GLFW_KEY_UNKNOWN
            && (keyCode < GLFW.GLFW_KEY_SPACE || keyCode > GLFW.GLFW_KEY_LAST)
        || keyCode == GLFW.GLFW_KEY_UNKNOWN && (scanCode < 0 || scanCode > 65535))
      return Optional.empty();
    var key = InputConstants.getKey(keyCode, scanCode);
    boolean captured = primaryHeld && primary.equals(key) || modifierHeld && modifier.equals(key);
    if (!captured) return Optional.empty();
    if (primary.equals(key)) primaryHeld = false;
    if (modifier.equals(key)) modifierHeld = false;
    if (primaryHeld || modifierHeld) return Optional.empty();
    var chosen = primary.equals(InputConstants.UNKNOWN) ? modifier : primary;
    var selectedModifier =
        primary.equals(InputConstants.UNKNOWN)
            ? KeyModifier.NONE
            : KeyModifier.getKeyModifier(modifier);
    if (selectedModifier.matches(chosen)) selectedModifier = KeyModifier.NONE;
    return Optional.of(new KeyBindingOptions.Binding(chosen.getName(), selectedModifier));
  }

  Optional<KeyBindingOptions.Binding> mouse(int button) {
    if (button < 0 || button > GLFW.GLFW_MOUSE_BUTTON_LAST) return Optional.empty();
    return Optional.of(
        new KeyBindingOptions.Binding(
            InputConstants.Type.MOUSE.getOrCreate(button).getName(),
            modifierHeld ? KeyModifier.getKeyModifier(modifier) : KeyModifier.NONE));
  }
}
