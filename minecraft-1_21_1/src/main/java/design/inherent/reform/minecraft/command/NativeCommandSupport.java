package design.inherent.reform.minecraft.command;

import com.mojang.brigadier.arguments.ArgumentType;
import design.inherent.reform.definition.DocumentId;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Native version seams; command admission and effect ownership remain with their bindings. */
public final class NativeCommandSupport {
  private NativeCommandSupport() {}

  static ArgumentType<DocumentId> identifier() {
    var nativeArgument = ResourceLocationArgument.id();
    return reader -> DocumentId.parse(nativeArgument.parse(reader).toString());
  }

  static ClickEvent copy(String value) {
    return new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
  }

  static ClickEvent run(String command) {
    return new ClickEvent(ClickEvent.Action.RUN_COMMAND, command);
  }

  public static boolean allowed(CommandSourceStack source, int level) {
    if (level < 0 || level > 4)
      throw new IllegalArgumentException("Invalid native permission level");
    return source.hasPermission(level);
  }

  public static CommandSource playerSource(ServerPlayer player) {
    return player;
  }

  public static boolean isSingleplayerOwner(MinecraftServer server, ServerPlayer player) {
    return server.isSingleplayerOwner(player.getGameProfile());
  }
}
