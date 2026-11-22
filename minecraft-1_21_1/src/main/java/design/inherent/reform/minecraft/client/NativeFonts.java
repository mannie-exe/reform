package design.inherent.reform.minecraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Style;

/** Font styling without changing the player's global font preference. */
final class NativeFonts {
  static Style uniform(Style style) {
    return style.withFont(Minecraft.UNIFORM_FONT);
  }

  private NativeFonts() {}
}
