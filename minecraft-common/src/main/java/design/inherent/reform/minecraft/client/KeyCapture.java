package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.KeyBindingOptions.Modifier;
import java.util.Optional;

/** Local input state only; capture never constructs or mutates a globally registered mapping. */
final class KeyCapture {
  private InputConstants.Key primary = InputConstants.UNKNOWN;
  private InputConstants.Key modifier = InputConstants.UNKNOWN;
  private boolean primaryHeld, modifierHeld;

  void press(int keyCode, int scanCode) {
    if (!NativeKeys.validInput(keyCode, scanCode)) return;
    var key = NativeKeys.input(keyCode, scanCode);
    // A repeated modifier event must not replace an already captured primary key.
    if (modifier.equals(key)) {
      modifierHeld = true;
      return;
    }
    if (modifier.equals(InputConstants.UNKNOWN)
        && NativeKeyBindings.modifierFor(key) != Modifier.NONE) {
      modifier = key;
      modifierHeld = true;
    } else {
      primary = key;
      primaryHeld = true;
    }
  }

  Optional<KeyBindingOptions.Binding> release(int keyCode, int scanCode) {
    if (NativeKeys.ignoreRelease(keyCode, scanCode)) return Optional.empty();
    if (keyCode == InputConstants.KEY_ESCAPE)
      return Optional.of(
          new KeyBindingOptions.Binding(InputConstants.UNKNOWN.getName(), Modifier.NONE));
    if (!NativeKeys.validInput(keyCode, scanCode)) return Optional.empty();
    var key = NativeKeys.input(keyCode, scanCode);
    boolean captured = primaryHeld && primary.equals(key) || modifierHeld && modifier.equals(key);
    if (!captured) return Optional.empty();
    if (primary.equals(key)) primaryHeld = false;
    if (modifier.equals(key)) modifierHeld = false;
    if (primaryHeld || modifierHeld) return Optional.empty();
    var chosen = primary.equals(InputConstants.UNKNOWN) ? modifier : primary;
    var selectedModifier =
        primary.equals(InputConstants.UNKNOWN)
            ? Modifier.NONE
            : NativeKeyBindings.modifierFor(modifier);
    if (selectedModifier.matches(chosen)) selectedModifier = Modifier.NONE;
    return Optional.of(new KeyBindingOptions.Binding(chosen.getName(), selectedModifier));
  }

  Optional<KeyBindingOptions.Binding> mouse(int button) {
    if (!NativeKeys.validMouse(button)) return Optional.empty();
    return Optional.of(
        new KeyBindingOptions.Binding(
            InputConstants.Type.MOUSE.getOrCreate(button).getName(),
            modifierHeld ? NativeKeyBindings.modifierFor(modifier) : Modifier.NONE));
  }
}
