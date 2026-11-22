package design.inherent.reform.fabric.client;

import design.inherent.reform.minecraft.client.ClientRegistration;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** One Fabric process bridge with bounded registrations; no fabricated FML registry. */
public final class FabricClientRegistration implements ClientRegistration {
  private static final Map<Object, Runnable> startup = new LinkedHashMap<>();
  private static final Map<String, CommandTree> commands = new LinkedHashMap<>();
  private static boolean installed, started, stopped;
  private final ModContainer container;

  private FabricClientRegistration(ModContainer container) {
    var loader = FabricLoader.getInstance();
    if (loader.getEnvironmentType() != EnvType.CLIENT)
      throw new IllegalStateException("Client registration requires the physical client");
    this.container = Objects.requireNonNull(container);
    if (loader.getModContainer(container.getMetadata().getId()).orElse(null) != container)
      throw new IllegalArgumentException("Use the actual owning Fabric mod container");
    install();
  }

  public static FabricClientRegistration of(ModContainer container) {
    return new FabricClientRegistration(container);
  }

  private static synchronized void install() {
    if (installed) return;
    ClientLifecycleEvents.CLIENT_STARTED.register(
        client -> {
          final List<Runnable> callbacks;
          synchronized (FabricClientRegistration.class) {
            started = true;
            callbacks = List.copyOf(startup.values());
            startup.clear();
          }
          callbacks.forEach(Runnable::run);
        });
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        client -> {
          synchronized (FabricClientRegistration.class) {
            stopped = true;
            startup.clear();
            commands.clear();
          }
          FabricClientMenus.close();
        });
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, registry) -> {
          final Map<String, CommandTree> selected;
          synchronized (FabricClientRegistration.class) {
            selected = new LinkedHashMap<>(commands);
          }
          for (var entry : selected.entrySet()) {
            if (dispatcher.getRoot().getChild(entry.getKey()) != null)
              throw new IllegalStateException(
                  "Client command root is already owned: " + entry.getKey());
            dispatcher.register(entry.getValue().bind(commandSource()));
          }
        });
    installed = true;
  }

  @Override
  public String modId() {
    return container.getMetadata().getId();
  }

  @Override
  public Path configurationDirectory() {
    return FabricLoader.getInstance().getConfigDir().toAbsolutePath();
  }

  @Override
  public void onSetup(Runnable callback) {
    synchronized (FabricClientRegistration.class) {
      if (started || stopped || startup.size() >= 128)
        throw new IllegalStateException(
            "Register client settings before Fabric client startup, within its bounded document budget");
      startup.put(new Object(), Objects.requireNonNull(callback));
    }
  }

  @Override
  public void registerMenu(Function<Screen, Screen> factory) {
    FabricClientMenus.register(modId(), factory);
  }

  @Override
  public Subscription registerCommands(String literal, CommandTree tree) {
    Objects.requireNonNull(tree);
    if (!literal.matches("[a-z0-9_.-]+"))
      throw new IllegalArgumentException("Use a native command literal");
    synchronized (FabricClientRegistration.class) {
      if (stopped || commands.size() >= 128)
        throw new IllegalStateException("Client command registration is closed or full");
      if (commands.putIfAbsent(literal, tree) != null)
        throw new IllegalArgumentException("Client command root is already owned: " + literal);
    }
    return () -> {
      synchronized (FabricClientRegistration.class) {
        commands.remove(literal, tree);
      }
    };
  }

  public static CommandSource<FabricClientCommandSource> commandSource() {
    return SOURCE;
  }

  private static final CommandSource<FabricClientCommandSource> SOURCE =
      new CommandSource<>() {
        @Override
        public boolean current(FabricClientCommandSource source) {
          var client = Minecraft.getInstance();
          return client.isSameThread()
              && client.getConnection() != null
              && client.player != null
              && (Object) source
                  instanceof net.minecraft.client.multiplayer.ClientSuggestionProvider nativeSource
              && nativeSource.connection == client.getConnection()
              && source.getClient() == client
              && source.getPlayer() == client.player
              && NativeFabricCommandSources.level(source) == client.level;
        }

        @Override
        public void requireLocalInvocation(FabricClientCommandSource source) {
          if (!current(source))
            throw new design.inherent.reform.owner.PolicyRefusal(
                "owner.unavailable", "The native client command source is no longer current");
          NativeFabricCommandSources.requireLocal(source);
        }
      };
}
