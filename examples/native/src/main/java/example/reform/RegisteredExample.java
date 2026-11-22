package example.reform;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.ClientRegisteredSettings;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.storage.JsonStore;
import java.util.Objects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Actual native registration references; no private serializer, worker, actor or duplicate values.
 */
public final class RegisteredExample {
  public static final String CLIENT_FILE = "legacy registered-client.toml";
  public static final String COMMON_FILE = "legacy-defaults.toml";
  private static ClientRegisteredSettings client, common;
  private static ModConfig clientConfig, commonConfig;
  private static ModConfigSpec.IntValue live, world, game, defaults;
  private static int clientReloads, commonReloads;
  private static Thread lastClientReload;
  private static int savedWorldCache, savedGameCache;
  private static final java.util.concurrent.ArrayBlockingQueue<String> EVENTS =
      new java.util.concurrent.ArrayBlockingQueue<>(32);
  private static final java.util.concurrent.atomic.AtomicInteger droppedEvents =
      new java.util.concurrent.atomic.AtomicInteger();
  private static boolean metadataOnly;
  private static int metadataRefusals;

  static void register(IEventBus bus, ModContainer container) {
    var builder = new ModConfigSpec.Builder();
    live = builder.defineInRange("live", 4, 0, 32);
    world = builder.worldRestart().defineInRange("world", 1, 0, 32);
    game = builder.gameRestart().defineInRange("game", 1, 0, 32);
    clientConfig =
        ConfigTracker.INSTANCE.registerConfig(
            ModConfig.Type.CLIENT, builder.build(), container, CLIENT_FILE);
    client =
        ClientRegisteredSettings.registeredNeoForge(
            bus, container, DocumentId.parse(NativeExample.ID + ":native_client"), clientConfig);
    builder = new ModConfigSpec.Builder();
    defaults = builder.defineInRange("level", 3, 0, 32);
    commonConfig =
        ConfigTracker.INSTANCE.registerConfig(
            ModConfig.Type.COMMON, builder.build(), container, COMMON_FILE);
    common =
        ClientRegisteredSettings.registeredNeoForge(
            bus, container, DocumentId.parse(NativeExample.ID + ":native_defaults"), commonConfig);
    metadataOnly =
        client.status().lifecycle() == ClientRegisteredSettings.Lifecycle.REGISTERED
            && common.status().lifecycle() == ClientRegisteredSettings.Lifecycle.REGISTERED
            && client.definition().isEmpty()
            && common.definition().isEmpty();
    reject(
        () ->
            ClientRegisteredSettings.registeredNeoForge(
                bus,
                container,
                DocumentId.parse(NativeExample.ID + ":native_alias"),
                clientConfig));
    reject(
        () ->
            ClientRegisteredSettings.registeredNeoForge(
                bus, container, DocumentId.parse("foreign:native_alias"), clientConfig));
    reject(
        () ->
            ClientRegisteredSettings.registeredNeoForge(
                bus,
                container,
                DocumentId.parse(NativeExample.ID + ":server_alias"),
                NativeExample.serverPolicies().nativeConfig()));
    reject(
        () ->
            ClientFileSettings.existingJson(
                NeoForgeClientRegistration.of(bus, container),
                NativeExample.DEFINITION,
                JsonStore.Format.EXISTING_BARE,
                COMMON_FILE));
    client.registerCommands("reformnative");
    bus.addListener(RegisteredExample::reloading);
    bus.addListener(RegisteredExample::loading);
    bus.addListener(RegisteredExample::unloading);
  }

  private static void reloading(ModConfigEvent.Reloading event) {
    trace(event);
    if (!net.minecraft.client.Minecraft.getInstance().isSameThread()) return;
    if (event.getConfig() == clientConfig) {
      clientReloads++;
      lastClientReload = Thread.currentThread();
      savedWorldCache = world.get();
      savedGameCache = game.get();
    } else if (event.getConfig() == commonConfig) commonReloads++;
  }

  private static void loading(ModConfigEvent.Loading event) {
    trace(event);
  }

  private static void unloading(ModConfigEvent.Unloading event) {
    trace(event);
  }

  private static void trace(ModConfigEvent event) {
    if (event.getConfig() != clientConfig && event.getConfig() != commonConfig) return;
    if (!EVENTS.offer(
        event.getConfig().getType()
            + ":"
            + event.getClass().getSimpleName()
            + ":"
            + Thread.currentThread().getName()
            + ":"
            + System.identityHashCode(event.getConfig().getLoadedConfig())))
      droppedEvents.incrementAndGet();
  }

  private static void reject(Runnable action) {
    try {
      action.run();
    } catch (IllegalArgumentException expected) {
      metadataRefusals++;
    }
  }

  public static ClientRegisteredSettings client() {
    return Objects.requireNonNull(client);
  }

  public static ClientRegisteredSettings common() {
    return Objects.requireNonNull(common);
  }

  public static ModConfig clientConfig() {
    return Objects.requireNonNull(clientConfig);
  }

  public static ModConfig commonConfig() {
    return Objects.requireNonNull(commonConfig);
  }

  public static int live() {
    return live.get();
  }

  public static int world() {
    return world.get();
  }

  public static int game() {
    return game.get();
  }

  public static int rawWorld() {
    return world.getRaw();
  }

  public static int rawGame() {
    return game.getRaw();
  }

  public static int defaults() {
    return defaults.get();
  }

  public static int clientReloads() {
    return clientReloads;
  }

  public static int commonReloads() {
    return commonReloads;
  }

  public static Thread lastClientReload() {
    return lastClientReload;
  }

  public static boolean metadataOnly() {
    return metadataOnly;
  }

  public static int metadataRefusals() {
    return metadataRefusals;
  }

  public static int savedWorldCache() {
    return savedWorldCache;
  }

  public static int savedGameCache() {
    return savedGameCache;
  }

  public static java.util.List<String> events() {
    return java.util.List.copyOf(EVENTS);
  }

  public static boolean completeEvents() {
    return droppedEvents.get() == 0;
  }

  private RegisteredExample() {}
}
