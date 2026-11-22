package design.inherent.reform.minecraft.configuration;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** One process-level lifecycle bridge; scopes and command editors retire separately. */
final class NativeServerRuntime {
  private static boolean installed;

  static boolean isMemoryConnection(ServerPlayer player) {
    return player.connection.getConnection().isMemoryConnection();
  }

  static synchronized void install() {
    if (installed) return;
    NeoForge.EVENT_BUS.addListener(
        (ServerStoppingEvent event) -> ServerSettings.release(event.getServer()));
    NeoForge.EVENT_BUS.addListener(
        (ServerStoppedEvent event) -> ServerSettings.release(event.getServer()));
    NeoForge.EVENT_BUS.addListener(
        (PlayerEvent.PlayerLoggedOutEvent event) -> {
          if (event.getEntity() instanceof ServerPlayer player)
            ServerSettings.retireCommands(player);
        });
    NeoForge.EVENT_BUS.addListener(
        (PlayerEvent.Clone event) -> {
          if (event.getOriginal() instanceof ServerPlayer player)
            ServerSettings.retireCommands(player);
        });
    installed = true;
  }

  private NativeServerRuntime() {}
}
