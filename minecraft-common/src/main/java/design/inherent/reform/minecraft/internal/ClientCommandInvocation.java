package design.inherent.reform.minecraft.internal;

import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.owner.PolicyRefusal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/** Internal native-chat invocation frame; it is not retained as a later operation capability. */
public final class ClientCommandInvocation {
  private record Frame(ClientPacketListener connection, LocalPlayer player, Screen screen) {}

  private static final ThreadLocal<Frame> current = new ThreadLocal<>();

  public static void fromNativeChat(Runnable action) {
    requireClientThread();
    var client = Minecraft.getInstance();
    var previous = current.get();
    current.set(new Frame(client.getConnection(), client.player, NativeScreens.current(client)));
    try {
      action.run();
    } finally {
      if (previous == null) current.remove();
      else current.set(previous);
    }
  }

  public static void requireLocal() {
    requireClientThread();
    var frame = current.get();
    var client = Minecraft.getInstance();
    if (frame == null
        || frame.connection() == null
        || frame.player() == null
        || frame.connection() != client.getConnection()
        || frame.player() != client.player
        || frame.screen() != NativeScreens.current(client))
      throw new PolicyRefusal(
          "client.command_origin",
          "Type this client settings command locally. Server-provided commands cannot inspect or change local settings.");
  }

  private static void requireClientThread() {
    NativeClientThread.require();
  }

  private ClientCommandInvocation() {}
}
