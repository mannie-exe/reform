package example.reform.platform;

import design.inherent.reform.neoforge.configuration.NeoForgeServerRegistration;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(PlatformServer.ID)
public final class NeoForgePlatform {
  public NeoForgePlatform(net.neoforged.bus.api.IEventBus bus, ModContainer container) {
    if (!NativeFixtureSide.isClient()
        && java.nio.file.Files.isRegularFile(java.nio.file.Path.of("registered-probe-mode.json"))) {
      new example.reformprobe.RegisteredOwnerProbe(
          bus,
          container,
          NativeFixtureSide.localConfigType(),
          PlatformRegisteredReferences::verify);
      return;
    }
    if (!NativeFixtureSide.isClient()) NeoForgeServerChecks.register(bus, container);
    if (NativeFixtureSide.isClient()) NeoForgePlatformClient.register(bus, container);
    NeoForge.EVENT_BUS.addListener(
        (RegisterCommandsEvent event) -> {
          if (NativeFixtureSide.isClient())
            event.getDispatcher().register(PlatformPlayerSearchChecks.tree());
          else
            event
                .getDispatcher()
                .register(
                    PlatformServer.commandTree()
                        .then(
                            design.inherent.reform.neoforge.configuration.ServerPolicyCommands
                                .registeredBranch(
                                    "toml",
                                    design.inherent.reform.definition.DocumentId.parse(
                                        PlatformServer.ID + ":native_server"))));
        });
    NeoForge.EVENT_BUS.addListener(
        (ServerStartedEvent event) -> {
          if (NativeFixtureSide.isClient()) {
            PlatformWorldChecks.serverStarted(
                event.getServer(), NeoForgeServerRegistration.of(container));
            return;
          }
          PlatformServer.start(
              event.getServer(),
              NeoForgeServerRegistration.of(container),
              net.neoforged.fml.ModList.get()
                  .getModContainerById("minecraft")
                  .orElseThrow()
                  .getModInfo()
                  .getVersion()
                  .toString(),
              net.neoforged.fml.ModList.get()
                  .getModContainerById("neoforge")
                  .orElseThrow()
                  .getModInfo()
                  .getVersion()
                  .toString());
        });
    NeoForge.EVENT_BUS.addListener(
        (ServerTickEvent.Post event) -> {
          if (!NativeFixtureSide.isClient()) PlatformServer.tick(event.getServer());
        });
    NeoForge.EVENT_BUS.addListener(
        (ServerStoppedEvent event) -> {
          if (NativeFixtureSide.isClient()) PlatformWorldChecks.serverStopped(event.getServer());
          else PlatformServer.stopped(event.getServer());
        });
  }
}
