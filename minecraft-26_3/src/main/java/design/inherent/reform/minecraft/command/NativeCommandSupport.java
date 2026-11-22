package design.inherent.reform.minecraft.command;

import com.mojang.brigadier.arguments.ArgumentType;
import design.inherent.reform.definition.DocumentId;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/** Native version seams; the current native permission set is consulted for every request. */
public final class NativeCommandSupport {
  private NativeCommandSupport() {}

  static ArgumentType<DocumentId> identifier() {
    var nativeArgument = IdentifierArgument.id();
    return reader -> DocumentId.parse(nativeArgument.parse(reader).toString());
  }

  static ClickEvent copy(String value) {
    return new ClickEvent.CopyToClipboard(value);
  }

  static ClickEvent run(String command) {
    return new ClickEvent.RunCommand(command);
  }

  public static boolean allowed(CommandSourceStack source, int level) {
    if (level < 0 || level > 4)
      throw new IllegalArgumentException("Invalid native permission level");
    return source
        .permissions()
        .hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(level)));
  }

  public static CommandSource playerSource(ServerPlayer player) {
    return player.commandSource();
  }

  public static boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
    return server.isSingleplayerOwner(player.nameAndId());
  }
}
