package example.reform.platform;

import design.inherent.reform.fabric.configuration.FabricServerRegistration;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricPlatform implements ModInitializer {
  @Override
  public void onInitialize() {
    var registration =
        FabricServerRegistration.of(
            FabricLoader.getInstance().getModContainer(PlatformServer.ID).orElseThrow());
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, registries, environment) -> {
          if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            dispatcher.register(PlatformPlayerSearchChecks.tree());
          else dispatcher.register(PlatformServer.commandTree());
        });
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            PlatformWorldChecks.serverStarted(server, registration);
            return;
          }
          PlatformServer.start(
              server,
              registration,
              FabricLoader.getInstance()
                  .getModContainer("minecraft")
                  .orElseThrow()
                  .getMetadata()
                  .getVersion()
                  .getFriendlyString(),
              FabricLoader.getInstance()
                  .getModContainer("fabricloader")
                  .orElseThrow()
                  .getMetadata()
                  .getVersion()
                  .getFriendlyString());
        });
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT)
            PlatformServer.tick(server);
        });
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT)
            PlatformWorldChecks.serverStopped(server);
          else PlatformServer.stopped(server);
        });
  }
}
