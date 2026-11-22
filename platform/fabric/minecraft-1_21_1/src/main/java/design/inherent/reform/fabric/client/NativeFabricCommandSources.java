package design.inherent.reform.fabric.client;

import design.inherent.reform.minecraft.internal.ClientCommandInvocation;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class NativeFabricCommandSources {
  static net.minecraft.client.multiplayer.ClientLevel level(FabricClientCommandSource source) {
    return source.getWorld();
  }

  static void requireLocal(FabricClientCommandSource source) {
    ClientCommandInvocation.requireLocal();
  }

  private NativeFabricCommandSources() {}
}
