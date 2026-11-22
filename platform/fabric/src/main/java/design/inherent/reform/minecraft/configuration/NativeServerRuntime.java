package design.inherent.reform.minecraft.configuration;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** One process-level bridge; Fabric listeners do not retain per-server/editor callbacks. */
final class NativeServerRuntime {
  private static boolean installed;

  static boolean isMemoryConnection(net.minecraft.server.level.ServerPlayer player) {
    return player.connection.connection.isMemoryConnection();
  }

  static synchronized void install() {
    if (installed) return;
    ServerLifecycleEvents.SERVER_STOPPING.register(ServerSettings::release);
    ServerLifecycleEvents.SERVER_STOPPED.register(ServerSettings::release);
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> ServerSettings.retireCommands(handler.player));
    ServerPlayerEvents.AFTER_RESPAWN.register(
        (oldPlayer, newPlayer, alive) -> ServerSettings.retireCommands(oldPlayer));
    installed = true;
  }

  private NativeServerRuntime() {}
}
