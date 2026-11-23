package design.inherent.reform.minecraft.client;

import com.mojang.blaze3d.Blaze3D;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Minecraft 26.3 native screen and explicit file-location operations. */
public final class NativeScreens {
  private NativeScreens() {}

  public static boolean clientPermission(
      net.minecraft.client.player.LocalPlayer player, int level) {
    return player
        .permissions()
        .hasPermission(
            new net.minecraft.server.permissions.Permission.HasCommandLevel(
                net.minecraft.server.permissions.PermissionLevel.byId(level)));
  }

  public static Screen current(Minecraft client) {
    return client.gui.screen();
  }

  public static void open(Minecraft client, Screen screen) {
    if (screen instanceof NativeFormScreen next
        && current(client) instanceof NativeFormScreen previous) {
      next.inheritFallbackTheme(previous.theme());
      next.inheritFallbackTasks(previous);
    }
    if (screen == null || current(client) != screen) client.gui.setScreen(screen);
  }

  public static void openPath(Path path) {
    Blaze3D.openPath(path);
  }
}
