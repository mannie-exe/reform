package design.inherent.reform.neoforge.client;

import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.internal.ClientCommandInvocation;
import design.inherent.reform.owner.PolicyRefusal;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Explicit NeoForge construction binding; Connector class presence never selects a loader. */
public final class NeoForgeClientRegistration implements ClientRegistration {
  private final IEventBus modBus;
  private final ModContainer container;

  private NeoForgeClientRegistration(IEventBus modBus, ModContainer container) {
    if (!NativeNeoForgeEnvironment.isClient())
      throw new IllegalStateException("Client registration requires the physical client");
    this.modBus = Objects.requireNonNull(modBus);
    this.container = Objects.requireNonNull(container);
  }

  public static NeoForgeClientRegistration of(IEventBus modBus, ModContainer container) {
    return new NeoForgeClientRegistration(modBus, container);
  }

  @Override
  public String modId() {
    return container.getModId();
  }

  @Override
  public Path configurationDirectory() {
    return FMLPaths.CONFIGDIR.get().toAbsolutePath();
  }

  @Override
  public void onSetup(Runnable callback) {
    Objects.requireNonNull(callback);
    modBus.addListener(
        (FMLClientSetupEvent event) ->
            event.enqueueWork(
                () -> {
                  var client = Minecraft.getInstance();
                  if (client == null)
                    throw new IllegalStateException(
                        "Native client setup ran before Minecraft was available");
                  // FML 12's deferred queue runs on its own worker while Minecraft waits for it.
                  // Do not wait for this handoff: that would deadlock the native client executor.
                  client.execute(callback);
                }));
  }

  @Override
  public void registerMenu(Function<Screen, Screen> factory) {
    Objects.requireNonNull(factory);
    if (container.getCustomExtension(IConfigScreenFactory.class).isPresent())
      throw new IllegalStateException("This mod already declares a configuration screen");
    container.registerExtensionPoint(
        IConfigScreenFactory.class, (ignored, parent) -> factory.apply(parent));
  }

  @Override
  public Subscription registerCommands(String literal, CommandTree tree) {
    Objects.requireNonNull(tree);
    if (!literal.matches("[a-z0-9_.-]+"))
      throw new IllegalArgumentException("Use a native command literal");
    Consumer<RegisterClientCommandsEvent> listener =
        event -> {
          if (event.getDispatcher().getRoot().getChild(literal) != null)
            throw new IllegalStateException("Client command root is already owned: " + literal);
          event.getDispatcher().register(tree.bind(commandSource()));
        };
    NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, listener);
    return () -> NeoForge.EVENT_BUS.unregister(listener);
  }

  public static CommandSource<CommandSourceStack> commandSource() {
    return SOURCE;
  }

  private static final CommandSource<CommandSourceStack> SOURCE =
      new CommandSource<>() {
        @Override
        public boolean current(CommandSourceStack source) {
          var client = Minecraft.getInstance();
          return source instanceof ClientCommandSourceStack
              && client.isSameThread()
              && client.player != null
              && client.getConnection() != null
              && source.getEntity() == client.player;
        }

        @Override
        public void requireLocalInvocation(CommandSourceStack source) {
          if (!current(source))
            throw new PolicyRefusal(
                "owner.unavailable", "The native client command source is no longer current");
          ClientCommandInvocation.requireLocal();
        }
      };
}
