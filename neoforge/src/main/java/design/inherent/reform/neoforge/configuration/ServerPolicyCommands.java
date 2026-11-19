package design.inherent.reform.neoforge.configuration;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Explicit server command branches over actual, separately registered server policies. */
public final class ServerPolicyCommands {
  public static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal, DocumentDefinition<T> definition) {
    return branch(literal, definition, 2);
  }

  /** Bind the exact declared definition under the current server at invocation. */
  public static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal, DocumentDefinition<T> definition, int level) {
    Objects.requireNonNull(definition);
    return branch(
        literal,
        definition.id(),
        level,
        server -> ServerSettings.admittedDocument(server, definition));
  }

  public static LiteralArgumentBuilder<CommandSourceStack> registeredBranch(
      String literal, DocumentId document) {
    return registeredBranch(literal, document, 2);
  }

  /** Bind the live native registration, whose generated definition belongs to that server. */
  public static LiteralArgumentBuilder<CommandSourceStack> registeredBranch(
      String literal, DocumentId document, int level) {
    return branch(
        literal,
        Objects.requireNonNull(document),
        level,
        server -> ServerSettings.admittedRegistered(server, document));
  }

  private static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal,
      DocumentId id,
      int level,
      Function<MinecraftServer, Optional<ServerSettings.Document<T>>> registered) {
    if (level < 0 || level > 4)
      throw new IllegalArgumentException("Native permission levels must be between zero and four");
    return SettingsCommands.branch(literal, id, new Binding<>(registered, level), 4096);
  }

  private record Binding<T>(
      Function<MinecraftServer, Optional<ServerSettings.Document<T>>> registered, int level)
      implements SettingsCommands.Binding<CommandSourceStack, T> {
    @Override
    public boolean available(CommandSourceStack source) {
      return document(source).isPresent();
    }

    @Override
    public SettingsCommands.Target<T> target(CommandSourceStack source) {
      return editor(source, true)
          .orElseThrow(
              () ->
                  new PolicyRefusal(
                      "owner.permission", "Current server policy permission is required"));
    }

    @Override
    public Optional<SettingsCommands.Target<T>> existing(CommandSourceStack source) {
      return editor(source, false);
    }

    private Optional<ServerSettings.Document<T>> document(CommandSourceStack source) {
      if (!source.getServer().isSameThread() || !source.hasPermission(level))
        return Optional.empty();
      return registered
          .apply(source.getServer())
          .filter(doc -> doc.commandAvailable(source, level));
    }

    private Optional<SettingsCommands.Target<T>> editor(CommandSourceStack source, boolean create) {
      return document(source)
          .flatMap(
              doc ->
                  doc.commandEditor(source, level, create)
                      .map(
                          editor ->
                              new SettingsCommands.Target<>(doc.context(), editor.actions())));
    }

    @Override
    public void reply(CommandSourceStack source, SettingsCommands.Reply reply) {
      if (reply.level() == SettingsCommands.Level.ERROR) source.sendFailure(reply.message());
      else source.sendSuccess(reply::message, false);
    }
  }

  private ServerPolicyCommands() {}
}
