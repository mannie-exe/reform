package example.reform.platform;

import design.inherent.reform.fabric.client.FabricClientRegistration;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricPlatformClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    var loader = FabricLoader.getInstance();
    if (loader.isModLoaded("modmenu"))
      PlatformClient.nativeMenu(
          loader
              .getModContainer("modmenu")
              .orElseThrow()
              .getMetadata()
              .getVersion()
              .getFriendlyString(),
          NativeModMenuChecks::screen);
    ClientReceiveMessageEvents.GAME.register(
        (message, overlay) -> PlatformWorldChecks.received(message));
    var registration =
        FabricClientRegistration.of(loader.getModContainer(PlatformServer.ID).orElseThrow());
    PlatformKeyChecks.register(registration).forEach(NativeFabricKeys::register);
    PlatformClient.register(
        registration,
        loader
            .getModContainer("minecraft")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString(),
        loader
            .getModContainer("fabricloader")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString());
    PlatformWorkflowExample.registeredMappings().forEach(NativeFabricKeys::register);
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PlatformClient.stopped());
  }
}
