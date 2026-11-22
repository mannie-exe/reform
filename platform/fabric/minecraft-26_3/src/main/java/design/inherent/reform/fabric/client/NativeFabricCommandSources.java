package design.inherent.reform.fabric.client;

import design.inherent.reform.minecraft.internal.ClientCommandInvocation;
import design.inherent.reform.owner.PolicyRefusal;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

final class NativeFabricCommandSources {
  static net.minecraft.client.multiplayer.ClientLevel level(FabricClientCommandSource source) {
    return source.getLevel();
  }

  static void requireLocal(FabricClientCommandSource source) {
    if (!source.attended())
      throw new PolicyRefusal(
          "client.command_origin",
          "Type this client settings command locally. Server-provided commands cannot inspect or change local settings.");
    // FAPI can dispatch an unattended link with its full, attended source after parsing.
    // That capability flag alone does not establish the invocation's native origin.
    ClientCommandInvocation.requireLocal();
  }

  private NativeFabricCommandSources() {}
}
