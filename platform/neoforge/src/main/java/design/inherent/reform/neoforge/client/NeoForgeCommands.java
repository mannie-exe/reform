package design.inherent.reform.neoforge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.minecraft.client.ClientCommands;
import design.inherent.reform.minecraft.command.SettingsCommands;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Shared physical-client recipient/owner admission; document backends retain their own sessions.
 */
final class NeoForgeCommands {
  interface Owner<T> extends ClientCommands.Owner<T> {}

  static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal,
      DocumentId document,
      Owner<T> owner,
      Optional<ImportCatalog<T>> imports,
      Consumer<SettingsCommands.Reply> output) {
    return ClientCommands.branch(
        literal, document, owner, imports, NeoForgeClientRegistration.commandSource(), output);
  }

  static Consumer<RegisterClientCommandsEvent> registerRoot(
      String literal, LiteralArgumentBuilder<CommandSourceStack> branch) {
    Consumer<RegisterClientCommandsEvent> listener =
        event -> {
          if (event.getDispatcher().getRoot().getChild(literal) != null)
            throw new IllegalStateException("Client command root is already owned: " + literal);
          event.getDispatcher().register(branch);
        };
    NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, listener);
    return listener;
  }

  static void chat(SettingsCommands.Reply reply) {
    var client = Minecraft.getInstance();
    if (client.player != null)
      client.player.sendSystemMessage(
          reply
              .message()
              .copy()
              .withStyle(
                  reply.level() == SettingsCommands.Level.ERROR
                      ? ChatFormatting.RED
                      : reply.level() == SettingsCommands.Level.WARNING
                          ? ChatFormatting.YELLOW
                          : ChatFormatting.GRAY));
  }

  private NeoForgeCommands() {}
}
