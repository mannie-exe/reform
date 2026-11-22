package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.neoforge.configuration.ServerPolicyCommands;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.LiveApplication;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * An ordinary addon: explicit world sources, native serializer and library-owned server lifetime.
 */
public final class ServerPolicyExample {
  public record Policy(int level) {}

  public record Reloads(
      int saves, int watches, int other, Thread saveThread, Thread watchThread, int watchedLevel) {}

  private enum ReloadOrigin {
    SAVE,
    WATCH,
    OTHER
  }

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
  private volatile Policy effective;
  private volatile ModConfig config;
  private final AtomicReference<Reloads> reloads =
      new AtomicReference<>(new Reloads(0, 0, 0, null, null, 0));

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
    NeoForge.EVENT_BUS.addListener(this::commands);
  }

  private void commands(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("reformpolicy")
                .then(ServerPolicyCommands.branch("file", DEFINITION))
                .then(ServerPolicyCommands.registeredBranch("toml", NATIVE)));
  }

  private void loading(ModConfigEvent.Loading event) {
    if (event.getConfig().getSpec() == spec) config = event.getConfig();
  }

  private void reloading(ModConfigEvent.Reloading event) {
    if (event.getConfig() == config) {
      var thread = Thread.currentThread();
      var origin =
          StackWalker.getInstance()
              .walk(
                  frames ->
                      frames
                          .map(
                              frame ->
                                  switch (frame.getClassName() + "." + frame.getMethodName()) {
                                    case "net.neoforged.fml.config.LoadedConfig.save" ->
                                        ReloadOrigin.SAVE;
                                    case "net.neoforged.fml.config.ConfigWatcher.run" ->
                                        ReloadOrigin.WATCH;
                                    default -> ReloadOrigin.OTHER;
                                  })
                          .filter(value -> value != ReloadOrigin.OTHER)
                          .findFirst()
                          .orElse(ReloadOrigin.OTHER));
      int level = event.getConfig().getLoadedConfig().config().getInt("level");
      reloads.updateAndGet(
          before ->
              switch (origin) {
                case SAVE ->
                    new Reloads(
                        before.saves() + 1,
                        before.watches(),
                        before.other(),
                        thread,
                        before.watchThread(),
                        before.watchedLevel());
                case WATCH ->
                    new Reloads(
                        before.saves(),
                        before.watches() + 1,
                        before.other(),
                        before.saveThread(),
                        thread,
                        level);
                case OTHER ->
                    new Reloads(
                        before.saves(),
                        before.watches(),
                        before.other() + 1,
                        before.saveThread(),
                        before.watchThread(),
                        before.watchedLevel());
              });
    }
  }

  private void unloading(ModConfigEvent.Unloading event) {
    if (event.getConfig() == config) config = null;
  }

  private void starting(ServerStartingEvent event) {
    var server = event.getServer();
    var settings = ServerSettings.forServer(server);
    effective = null;
    var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath();
    var source =
        new JsonStore<>(
            DEFINITION,
            new OwnedFile(StorageBinding.relative(root, "serverconfig", FILE), 262144),
            JsonStore.Format.NEW_ENVELOPE,
            new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
            DraftLimits.CONFIGURATION);
    settings.serverPolicy(
        design.inherent.reform.neoforge.configuration.NeoForgeServerRegistration.of(container),
        source,
        ApplicationEffects.OWNER_PUBLICATION,
        new LiveApplication<>(
            proposed -> proposed.equals(effective),
            proposed -> {
              if (!server.isSameThread())
                throw new IllegalStateException("Expected native server effect executor");
              effective = proposed;
            }));
    design.inherent.reform.neoforge.configuration.NeoForgeServerSettings.registered(
        settings, container, Objects.requireNonNull(config), NATIVE);
  }

  public ServerSettings.Document<?> policy(MinecraftServer server) {
    return ServerSettings.forServer(server).document(DEFINITION.id()).orElseThrow();
  }

  public Policy effectivePolicy() {
    return effective;
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

  public Reloads reloads() {
    return reloads.get();
  }
}
