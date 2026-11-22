package example.reform.platform;

import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

final class NeoForgePlatformClient {
  static void register(IEventBus bus, ModContainer container) {
    NeoForgeRegisteredChecks.register(bus, container);
    NeoForge.EVENT_BUS.addListener(
        (ClientChatReceivedEvent event) -> PlatformWorldChecks.received(event.getMessage()));
    var registration = NeoForgeClientRegistration.of(bus, container);
    var mappings = new java.util.ArrayList<>(PlatformKeyChecks.register(registration));
    PlatformClient.register(
        registration,
        ModList.get()
            .getModContainerById("minecraft")
            .orElseThrow()
            .getModInfo()
            .getVersion()
            .toString(),
        ModList.get()
            .getModContainerById("neoforge")
            .orElseThrow()
            .getModInfo()
            .getVersion()
            .toString());
    if (!PlatformWorkflowExample.registeredMappings().isEmpty())
      PlatformWorkflowSources.register(bus, container);
    mappings.addAll(PlatformWorkflowExample.registeredMappings());
    bus.addListener((RegisterKeyMappingsEvent event) -> mappings.forEach(event::register));
    NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> PlatformClient.stopped());
  }

  private NeoForgePlatformClient() {}
}
