package design.inherent.reform.minecraft.client;

import java.nio.file.Path;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 1.21.1 native screen and explicit file-location operations. */
public final class NativeScreens {
  private NativeScreens() {}

  public static boolean clientPermission(
      net.minecraft.client.player.LocalPlayer player, int level) {
    return player.hasPermissions(level);
  }

  public static Screen current(Minecraft client) {
    return client.screen;
  }

  public static void open(Minecraft client, Screen screen) {
    if (screen instanceof NativeFormScreen next
        && current(client) instanceof NativeFormScreen previous)
      next.inheritFallbackTheme(previous.theme());
    if (screen == null || current(client) != screen) client.setScreen(screen);
  }

  public static void openPath(Path path) {
    Util.getPlatform().openPath(path);
  }
}
