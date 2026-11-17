package example.reform;

import design.inherent.reform.neoforge.client.ClientJsonSettings;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Ordinary public factory use; no consumer-owned executor, storage writer or editing session. */
public final class ClientExample {
  private static ClientJsonSettings<NativeExample.Preferences> preferences;

  static void register(IEventBus bus, ModContainer container) {
    preferences = ClientJsonSettings.clientJson(bus, container, NativeExample.DEFINITION);
    preferences.registerMenu(Component.literal("Native example settings"));
    preferences.registerCommands("reformprefs");
  }

  public static ClientJsonSettings<NativeExample.Preferences> settings() {
    return Objects.requireNonNull(preferences, "Client example has not registered");
  }

  private ClientExample() {}
}
