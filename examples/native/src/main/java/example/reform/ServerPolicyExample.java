package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.neoforge.configuration.ServerSettings;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * An ordinary addon: explicit world sources, native serializer and library-owned server lifetime.
 */
public final class ServerPolicyExample {
  public record Policy(int level) {}

  public static final DocumentDefinition<Policy> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":server_policy"),
          1,
          RecordTypes.record(Policy.class)
              .field("level", FieldKey.of("level", ValueTypes.integer(1, 32)))
              .build(),
          () -> new Policy(6));
  public static final DocumentId NATIVE = DocumentId.parse(NativeExample.ID + ":server_native");
  public static final String FILE = "reform-server-policy.json";
  public static final String NATIVE_FILE = "reform-server-native.toml";
  private final ModContainer container;
  private final ModConfigSpec spec;
  private ModConfig config;
  private int reloads;

  public ServerPolicyExample(IEventBus bus, ModContainer container) {
    this.container = container;
    var builder = new ModConfigSpec.Builder();
    builder
        .comment("Native server policy; no replacement JSON serializer.")
        .defineInRange("level", 4, 1, 32);
    spec = builder.build();
    config =
        ConfigTracker.INSTANCE.registerConfig(ModConfig.Type.SERVER, spec, container, NATIVE_FILE);
    bus.addListener(this::loading);
    bus.addListener(this::reloading);
    bus.addListener(this::unloading);
    NeoForge.EVENT_BUS.addListener(this::starting);
  }

  private void loading(ModConfigEvent.Loading event) {
    if (event.getConfig().getSpec() == spec) config = event.getConfig();
  }

  private void reloading(ModConfigEvent.Reloading event) {
    if (event.getConfig() == config) reloads++;
  }

  private void unloading(ModConfigEvent.Unloading event) {
    if (event.getConfig() == config) config = null;
  }

  private void starting(ServerStartingEvent event) {
    var server = event.getServer();
    var settings = ServerSettings.forServer(server);
    settings.serverPolicy(
        container,
        DEFINITION,
        StorageBinding.relative(
            server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "serverconfig", FILE));
    settings.registeredNeoForge(container, Objects.requireNonNull(config), NATIVE);
  }

  public ServerSettings.Document<?> policy(MinecraftServer server) {
    return ServerSettings.forServer(server).document(DEFINITION.id()).orElseThrow();
  }

  public ServerSettings.Document<?> nativePolicy(MinecraftServer server) {
    return ServerSettings.forServer(server).document(NATIVE).orElseThrow();
  }

  public ModConfig nativeConfig() {
    return Objects.requireNonNull(config);
  }

  public ModContainer container() {
    return container;
  }

  public int reloads() {
    return reloads;
  }
}
