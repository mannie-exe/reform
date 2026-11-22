package example.reform.platform;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientForm;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.neoforge.client.ClientRegisteredSettings;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Actual native CLIENT and COMMON/LOCAL registrations, serializers and cached values. */
public final class PlatformWorkflowSources {
  private static ClientRegisteredSettings client, defaults;
  private static ModConfig clientConfig, defaultsConfig;
  private static ModConfigSpec.IntValue live, level;
  private static int defaultReloads, defaultClientReloads;

  static void register(ClientRegistration registration) {
    // The loader entry supplies its actual bus/container below, before native setup.
  }

  static void register(IEventBus bus, ModContainer container) {
    var builder = new ModConfigSpec.Builder();
    live = builder.defineInRange("live", 4, 0, 32);
    builder.worldRestart().defineInRange("world", 1, 0, 32);
    builder.gameRestart().defineInRange("game", 1, 0, 32);
    clientConfig =
        ConfigTracker.INSTANCE.registerConfig(
            ModConfig.Type.CLIENT, builder.build(), container, "platform-workflow-client.toml");
    client =
        ClientRegisteredSettings.registeredNeoForge(
            bus, container, DocumentId.parse(PlatformServer.ID + ":workflow_client"), clientConfig);
    builder = new ModConfigSpec.Builder();
    level = builder.defineInRange("level", 3, 0, 32);
    defaultsConfig =
        ConfigTracker.INSTANCE.registerConfig(
            NativeFixtureSide.localConfigType(),
            builder.build(),
            container,
            "platform-workflow-defaults.toml");
    defaults =
        ClientRegisteredSettings.registeredNeoForge(
            bus,
            container,
            DocumentId.parse(PlatformServer.ID + ":workflow_defaults"),
            defaultsConfig);
    bus.addListener(
        (ModConfigEvent.Reloading event) -> {
          if (event.getConfig() == defaultsConfig) {
            defaultReloads++;
            if (net.minecraft.client.Minecraft.getInstance().isSameThread()) defaultClientReloads++;
          }
        });
  }

  static boolean ready() {
    return client != null
        && defaults != null
        && client.status().lifecycle() == ClientRegisteredSettings.Lifecycle.READY
        && defaults.status().lifecycle() == ClientRegisteredSettings.Lifecycle.READY;
  }

  static ClientForm client() {
    return client.form(Component.literal("Native client settings"));
  }

  static ClientForm defaults() {
    return defaults.form(Component.literal("Installation defaults"));
  }

  static Path clientPath() {
    return clientConfig.getFullPath();
  }

  static Path defaultsPath() {
    return defaultsConfig.getFullPath();
  }

  static int live() {
    return live.get();
  }

  static int level() {
    return level.get();
  }

  static String sourceKind() {
    return "Native " + clientConfig.getType() + "/" + defaultsConfig.getType() + " registrations";
  }

  static String saveLabel() {
    return "Save native settings";
  }

  static boolean defaultSaveObserved() {
    return level() == 10 && defaultReloads > 0 && defaultClientReloads > 0;
  }

  static com.google.gson.JsonObject sourceReport() {
    var report = new com.google.gson.JsonObject();
    report.addProperty("kind", sourceKind());
    report.addProperty("clientSource", clientPath().getFileName().toString());
    report.addProperty("defaultSource", defaultsPath().getFileName().toString());
    report.addProperty("defaultReloads", defaultReloads);
    report.addProperty("defaultClientReloads", defaultClientReloads);
    return report;
  }

  private PlatformWorkflowSources() {}
}
