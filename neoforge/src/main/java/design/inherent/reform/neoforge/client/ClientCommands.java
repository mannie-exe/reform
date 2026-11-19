package design.inherent.reform.neoforge.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.neoforge.command.ImportCatalog;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Shared physical-client recipient/owner admission; document backends retain their own sessions.
 */
final class ClientCommands {
  interface Owner<T> {
    boolean ready();

    SettingsCommands.Target<T> target();

    Optional<SettingsCommands.Target<T>> existing();
  }

  static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal,
      DocumentId document,
      Owner<T> owner,
      Optional<ImportCatalog<T>> imports,
      Consumer<SettingsCommands.Reply> output) {
    return SettingsCommands.branch(
        literal,
        document,
        new SettingsCommands.Binding<CommandSourceStack, T>() {
          @Override
          public boolean available(CommandSourceStack source) {
            return source instanceof ClientCommandSourceStack
                && Minecraft.getInstance().isSameThread()
                && source.getEntity() == Minecraft.getInstance().player
                && owner.ready();
          }

          @Override
          public SettingsCommands.Target<T> target(CommandSourceStack source) {
            ClientSettingsRuntime.requireClientThread();
            if (!available(source))
              throw new PolicyRefusal("owner.unavailable", "Client settings are not ready");
            return owner.target();
          }

          @Override
          public Optional<SettingsCommands.Target<T>> existing(CommandSourceStack source) {
            return available(source) ? owner.existing() : Optional.empty();
          }

          @Override
          public Optional<ImportCatalog<T>> imports(CommandSourceStack source) {
            return available(source) ? imports : Optional.empty();
          }

          @Override
          public void reply(CommandSourceStack source, SettingsCommands.Reply reply) {
            output.accept(reply);
          }
        },
        8192);
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

  private ClientCommands() {}
}
