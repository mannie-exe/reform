package design.inherent.reform.minecraft.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import java.nio.file.Path;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;

/** Actual loader construction/lifecycle services; source ownership remains with each document. */
public interface ClientRegistration {
  @FunctionalInterface
  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  interface CommandSource<S> {
    /** Current physical-client thread, connection, player and native source, with no IO. */
    boolean current(S source);

    /** Checked at initial command admission, never reused as an asynchronous context guard. */
    void requireLocalInvocation(S source);
  }

  interface CommandTree {
    <S> LiteralArgumentBuilder<S> bind(CommandSource<S> source);
  }

  String modId();

  Path configurationDirectory();

  /** Register before the actual loader's client setup/start callback; never run inline. */
  void onSetup(Runnable callback);

  /**
   * Runtime-only, on the native client thread. Construction-time callers schedule this with
   * onSetup. Subscriptions share one native bridge and retire independently.
   */
  default Subscription onTick(Runnable callback) {
    return ClientSettingsRuntime.onTick(callback);
  }

  void registerMenu(Function<Screen, Screen> factory);

  Subscription registerCommands(String literal, CommandTree tree);
}
