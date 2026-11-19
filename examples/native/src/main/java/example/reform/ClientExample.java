package example.reform;

import design.inherent.reform.neoforge.client.ClientFileSettings;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Ordinary public factory use; no consumer-owned executor, storage writer or editing session. */
public final class ClientExample {
  private static ClientFileSettings<NativeExample.Preferences> preferences;

  static void register(IEventBus bus, ModContainer container) {
    StructuredExample.register(bus, container);
    ReferenceExample.register(bus, container);
    NumericExample.register(bus, container);
    DependencyExample.register(bus, container);
    CollectionExample.register(bus, container);
    ImportExample.register(bus, container);
    ConflictExample.register(bus, container);
    KeyExample.register(bus, container);
    CatalogExample.register(bus, container);
    FileFormatsExample.register(bus, container);
    StartupExample.register(bus, container);
    RegisteredExample.register(bus, container);
    WorldDefaultsExample.register(bus, container);
    preferences = ClientFileSettings.clientJson(bus, container, NativeExample.DEFINITION);
    preferences.registerMenu(Component.literal("Native example settings"));
    preferences.registerCommands("reformprefs");
  }

  public static ClientFileSettings<NativeExample.Preferences> settings() {
    return Objects.requireNonNull(preferences, "Client example has not registered");
  }

  private ClientExample() {}
}
