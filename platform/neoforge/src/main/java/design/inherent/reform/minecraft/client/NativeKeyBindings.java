package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.minecraft.client.KeyBindingOptions.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

/** Retain actual NeoForge modifiers, opaque context identity and native callback semantics. */
final class NativeKeyBindings {
  private static final Set<Modifier> MODIFIERS =
      Arrays.stream(KeyModifier.values())
          .map(value -> Modifier.valueOf(value.name()))
          .collect(Collectors.toUnmodifiableSet());

  static Set<Modifier> modifiers() {
    return MODIFIERS;
  }

  static InputConstants.Key key(KeyMapping mapping) {
    return mapping.getKey();
  }

  static Modifier modifier(KeyMapping mapping) {
    return Modifier.valueOf(mapping.getKeyModifier().name());
  }

  static Modifier defaultModifier(KeyMapping mapping) {
    return Modifier.valueOf(mapping.getDefaultKeyModifier().name());
  }

  static Object context(KeyMapping mapping) {
    return mapping.getKeyConflictContext();
  }

  static Path file(Options options) {
    return options.getFile().toPath();
  }

  static Modifier modifierFor(InputConstants.Key key) {
    return Modifier.valueOf(KeyModifier.getKeyModifier(key).name());
  }

  static boolean matches(Modifier modifier, InputConstants.Key key) {
    return MODIFIERS.contains(modifier) && KeyModifier.valueOf(modifier.name()).matches(key);
  }

  static Component label(Modifier modifier, InputConstants.Key key) {
    return KeyModifier.valueOf(modifier.name()).getCombinedName(key, key::getDisplayName);
  }

  static void set(KeyMapping mapping, Modifier modifier, InputConstants.Key key) {
    mapping.setKeyModifierAndCode(KeyModifier.valueOf(modifier.name()), key);
  }

  static Modifier savedModifier(String[] raw) {
    return Modifier.valueOf(
        (raw.length > 1 ? KeyModifier.valueFromString(raw[1]) : KeyModifier.NONE).name());
  }

  static boolean currentConflict(KeyMapping first, KeyMapping second) {
    return first.same(second) || second.hasKeyModifierConflict(first);
  }

  static boolean projectedConflict(
      InputConstants.Key first,
      Modifier firstModifier,
      Object firstContext,
      InputConstants.Key second,
      Modifier secondModifier,
      Object secondContext) {
    var a = (IKeyConflictContext) firstContext;
    var b = (IKeyConflictContext) secondContext;
    if (a.conflicts(b) || b.conflicts(a)) {
      if (matches(firstModifier, second) || matches(secondModifier, first)) return true;
      if (first.equals(second))
        return firstModifier == secondModifier
            || a.conflicts(KeyConflictContext.IN_GAME)
                && (firstModifier == Modifier.NONE || secondModifier == Modifier.NONE);
    }
    return first.equals(second);
  }

  private NativeKeyBindings() {}
}
