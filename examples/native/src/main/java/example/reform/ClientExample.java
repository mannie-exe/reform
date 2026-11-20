package example.reform;

import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.ClientFormOptions;
import design.inherent.reform.neoforge.client.IntegratedSettings;
import design.inherent.reform.presentation.FormPresentation;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.gui.screens.Screen;
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

  public static Screen worldPolicy(Screen parent, boolean registered) {
    if (registered)
      return IntegratedSettings.screen(
          parent, Component.literal("Native server policy"), ServerPolicyExample.NATIVE);
    return IntegratedSettings.screen(
        parent,
        Component.literal("World file policy"),
        ServerPolicyExample.DEFINITION.id(),
        2,
        ServerPolicyExample.DEFINITION,
        new ClientFormOptions<>(FormPresentation.EMPTY, Map.of()));
  }

  private ClientExample() {}
}
