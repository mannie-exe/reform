package design.inherent.reform.minecraft.client;

import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

/** Font styling without changing the player's global font preference. */
final class NativeFonts {
  private static final FontDescription UNIFORM =
      new FontDescription.Resource(Identifier.withDefaultNamespace("uniform"));

  static Style uniform(Style style) {
    return style.withFont(UNIFORM);
  }

  private NativeFonts() {}
}
