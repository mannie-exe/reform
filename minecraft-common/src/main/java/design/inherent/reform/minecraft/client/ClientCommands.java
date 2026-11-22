package design.inherent.reform.minecraft.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

/** Shared command tree and owner lifecycle; native source types come from the loader binding. */
public final class ClientCommands {
  public interface Owner<T> {
    boolean ready();

    SettingsCommands.Target<T> target();

    Optional<SettingsCommands.Target<T>> existing();
  }

  public static <S, T> LiteralArgumentBuilder<S> branch(
      String literal,
      DocumentId document,
      Owner<T> owner,
      Optional<ImportCatalog<T>> imports,
      ClientRegistration.CommandSource<S> nativeSource,
      Consumer<SettingsCommands.Reply> output) {
    return SettingsCommands.branch(
        literal,
        document,
        new SettingsCommands.Binding<S, T>() {
          @Override
          public boolean available(S source) {
            return nativeSource.current(source) && owner.ready();
          }

          @Override
          public void requireInvocation(S source) {
            nativeSource.requireLocalInvocation(source);
          }

          @Override
          public SettingsCommands.Target<T> target(S source) {
            ClientSettingsRuntime.requireClientThread();
            if (!available(source))
              throw new PolicyRefusal("owner.unavailable", "Client settings are not ready");
            return owner.target();
          }

          @Override
          public Optional<SettingsCommands.Target<T>> existing(S source) {
            return available(source) ? owner.existing() : Optional.empty();
          }

          @Override
          public Optional<ImportCatalog<T>> imports(S source) {
            return available(source) ? imports : Optional.empty();
          }

          @Override
          public void reply(S source, SettingsCommands.Reply reply) {
            output.accept(reply);
          }
        },
        8192);
  }

  public static void chat(SettingsCommands.Reply reply) {
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
