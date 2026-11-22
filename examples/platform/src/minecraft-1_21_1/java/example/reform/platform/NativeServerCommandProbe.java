package example.reform.platform;

import net.minecraft.commands.CommandSourceStack;

/** Retains the real console identity while removing its actual native command permission. */
final class NativeServerCommandProbe {
  static CommandSourceStack lowered(CommandSourceStack source) {
    return source.withPermission(0);
  }

  private NativeServerCommandProbe() {}
}
