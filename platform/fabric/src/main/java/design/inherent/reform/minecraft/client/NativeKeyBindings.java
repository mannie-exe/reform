package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.KeyBindingOptions.Modifier;
import design.inherent.reform.owner.PolicyRefusal;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Fabric edits real native primary mappings; it does not invent NeoForge chord dispatch. */
final class NativeKeyBindings {
  private enum Context {
    NATIVE
  }

  static Set<Modifier> modifiers() {
    return Set.of(Modifier.NONE);
  }

  static InputConstants.Key key(KeyMapping mapping) {
    return mapping.key;
  }

  static Modifier modifier(KeyMapping mapping) {
    return Modifier.NONE;
  }

  static Modifier defaultModifier(KeyMapping mapping) {
    return Modifier.NONE;
  }

  static Object context(KeyMapping mapping) {
    return Context.NATIVE;
  }

  static Path file(Options options) {
    return options.optionsFile.toPath();
  }

  static Modifier modifierFor(InputConstants.Key key) {
    return NativeKeys.physicalModifier(key);
  }

  static boolean matches(Modifier modifier, InputConstants.Key key) {
    return modifier != Modifier.NONE && NativeKeys.physicalModifier(key) == modifier;
  }

  static Component label(Modifier modifier, InputConstants.Key key) {
    requirePrimary(modifier);
    return key.getDisplayName();
  }

  static void set(KeyMapping mapping, Modifier modifier, InputConstants.Key key) {
    requirePrimary(modifier);
    mapping.setKey(key);
  }

  static Modifier savedModifier(String[] raw) {
    if (raw.length != 1)
      throw new PolicyRefusal(
          "keys.source",
          "Native options contain an unsupported modifier or extra value; the source was retained");
    return Modifier.NONE;
  }

  static boolean currentConflict(KeyMapping first, KeyMapping second) {
    return first.same(second);
  }

  static boolean projectedConflict(
      InputConstants.Key first,
      Modifier firstModifier,
      Object firstContext,
      InputConstants.Key second,
      Modifier secondModifier,
      Object secondContext) {
    requirePrimary(firstModifier);
    requirePrimary(secondModifier);
    return first.equals(second);
  }

  private static void requirePrimary(Modifier modifier) {
    if (modifier != Modifier.NONE)
      throw new PolicyRefusal(
          "keys.unsupported_modifier",
          "This loader cannot save native modifier chords; the binding was retained");
  }

  private NativeKeyBindings() {}
}
