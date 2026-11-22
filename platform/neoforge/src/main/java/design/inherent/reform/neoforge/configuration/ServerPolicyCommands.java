package design.inherent.reform.neoforge.configuration;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import net.minecraft.commands.CommandSourceStack;

/** NeoForge native-config convenience; ordinary typed branches use the shared command service. */
public final class ServerPolicyCommands {
  public static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal, DocumentDefinition<T> definition) {
    return design.inherent.reform.minecraft.configuration.ServerPolicyCommands.branch(
        literal, definition);
  }

  public static <T> LiteralArgumentBuilder<CommandSourceStack> branch(
      String literal, DocumentDefinition<T> definition, int level) {
    return design.inherent.reform.minecraft.configuration.ServerPolicyCommands.branch(
        literal, definition, level);
  }

  public static LiteralArgumentBuilder<CommandSourceStack> registeredBranch(
      String literal, DocumentId document) {
    return registeredBranch(literal, document, 2);
  }

  public static LiteralArgumentBuilder<CommandSourceStack> registeredBranch(
      String literal, DocumentId document, int level) {
    return design.inherent.reform.minecraft.configuration.ServerPolicyCommands.branch(
        literal, document, level, server -> NeoForgeServerSettings.admitted(server, document));
  }

  private ServerPolicyCommands() {}
}
