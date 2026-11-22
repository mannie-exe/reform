package design.inherent.reform.minecraft.command;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;

/** An admitted search origin and its actual operator recipient. */
public final class SearchRecipient {
  private final MinecraftServer server;
  private final ServerLevel level;
  private final Vec3 origin;
  private final int permission;
  private final UUID playerId;
  private final ServerGamePacketListenerImpl connection;
  private final CommandSourceStack console;

  private SearchRecipient(CommandSourceStack source, int permission) {
    this.server = source.getServer();
    requireThread();
    this.level = source.getLevel();
    this.origin = source.getPosition();
    this.permission = permission;
    var player = source.getPlayer();
    this.playerId = player == null ? null : player.getUUID();
    this.connection = player == null ? null : player.connection;
    this.console = player == null ? source : null;
  }

  public static SearchRecipient capture(CommandSourceStack source, int permission) {
    if (permission < 0 || permission > 4)
      throw new IllegalArgumentException("Native permission levels must be between zero and four");
    var recipient = new SearchRecipient(Objects.requireNonNull(source), permission);
    if (!recipient.owns(source))
      throw new PolicyRefusal(
          "owner.permission", "Use the current native search operator and level");
    return recipient;
  }

  public ServerLevel level() {
    return level;
  }

  public Vec3 origin() {
    return origin;
  }

  /** Resolve the replacement player after a native respawn on the same play connection. */
  public Optional<CommandSourceStack> current() {
    requireThread();
    if (level == null
        || !server.isRunning()
        || server.isStopped()
        || server.getLevel(level.dimension()) != level) return Optional.empty();
    if (console != null)
      return console.source == server
              && console.getEntity() == null
              && NativeCommandSupport.allowed(console, permission)
              && NativeCommandSupport.allowed(server.createCommandSourceStack(), permission)
          ? Optional.of(console)
          : Optional.empty();
    var player = server.getPlayerList().getPlayer(playerId);
    if (player == null
        || player.level() != level
        || connection == null
        || player.connection != connection
        || !connection.isAcceptingMessages()) return Optional.empty();
    var current = player.createCommandSourceStack();
    return NativeCommandSupport.allowed(current, permission)
        ? Optional.of(current)
        : Optional.empty();
  }

  /** The invocation can restrict the current actor's permission but cannot elevate it. */
  public boolean owns(CommandSourceStack requester) {
    requireThread();
    if (requester.getServer() != server
        || requester.getLevel() != level
        || !NativeCommandSupport.allowed(requester, permission)) return false;
    return current()
        .filter(
            actual ->
                actual.source == requester.source && actual.getEntity() == requester.getEntity())
        .isPresent();
  }

  private void requireThread() {
    if (!server.isSameThread())
      throw new IllegalStateException("Resolve search recipients on their native server thread");
  }
}
