package example.reform.platform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.neoforge.configuration.NeoForgeServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Actual world-loaded SERVER source and callback; portable policy remains independently exercised.
 */
final class NeoForgeServerChecks {
  private static ModConfig config;
  private static ModConfigSpec.IntValue level;
  private static ModConfigSpec.ConfigValue<String> label;
  private static ModContainer container;
  private static MinecraftServer active;
  private static int reloads;
  private static boolean callbackThread;

  static void register(IEventBus bus, ModContainer owner) {
    container = owner;
    var builder = new ModConfigSpec.Builder();
    level = builder.comment("Native server source comment").defineInRange("level", 4, 0, 32);
    label = builder.define("label", "native");
    config =
        ConfigTracker.INSTANCE.registerConfig(
            NativeFixtureSide.serverConfigType(),
            builder.build(),
            container,
            "platform-native-server.toml");
    if (config.getLoadedConfig() != null)
      throw new AssertionError("SERVER config loaded during mod metadata registration");
    bus.addListener(
        (ModConfigEvent.Reloading event) -> {
          if (event.getConfig() == config) {
            reloads++;
            callbackThread = active != null && active.isSameThread();
          }
        });
    PlatformServer.extraChecks(NeoForgeServerChecks::run);
  }

  private static CompletionStage<JsonObject> run(MinecraftServer server) {
    if (!server.isSameThread() || config.getLoadedConfig() == null)
      throw new AssertionError("Native SERVER config was not loaded on server startup");
    active = server;
    boolean override;
    try {
      override =
          JsonParser.parseString(Files.readString(Path.of("platform-input.json")))
              .getAsJsonObject()
              .get("syncedWorldOverride")
              .getAsBoolean();
    } catch (java.io.IOException failed) {
      throw new java.io.UncheckedIOException(failed);
    }
    if (override
        && !config
            .getFullPath()
            .toAbsolutePath()
            .normalize()
            .equals(
                Path.of("world/syncedconfig/platform-native-server.toml")
                    .toAbsolutePath()
                    .normalize()))
      throw new AssertionError("Native SYNCED owner did not select actual world override");
    var document =
        NeoForgeServerSettings.registered(
            ServerSettings.forServer(server),
            container,
            config,
            DocumentId.parse(PlatformServer.ID + ":native_server"));
    var editor = document.editor(server.createCommandSourceStack());
    var actions = editor.actions();
    int before = level.get(), callbacks = reloads;
    if (before != 4 && before != 9) throw new AssertionError("Unexpected native server baseline");
    return DocumentActions.result(actions.ensureLoaded(EntryPoint.COMMAND))
        .thenComposeAsync(
            ignored -> {
              requireThread(server);
              return DocumentActions.result(actions.edit(EntryPoint.COMMAND, "level", "900"));
            },
            server)
        .thenComposeAsync(
            ignored -> DocumentActions.result(actions.preflight(EntryPoint.COMMAND)), server)
        .thenComposeAsync(
            invalid -> {
              requireThread(server);
              if (invalid.report().valid()
                  || invalid.report().issues().stream()
                      .noneMatch(issue -> issue.code().equals("native.validation"))
                  || level.get() != before
                  || callbacks != reloads)
                throw new AssertionError(
                    "Native SERVER invalid preflight disagreed: before="
                        + before
                        + ", current="
                        + level.get()
                        + ", callbacks="
                        + callbacks
                        + "->"
                        + reloads
                        + ", validation="
                        + invalid.report());
              return DocumentActions.result(actions.edit(EntryPoint.COMMAND, "level", "9"));
            },
            server)
        .thenComposeAsync(
            ignored -> DocumentActions.result(actions.commit(EntryPoint.COMMAND)), server)
        .thenApplyAsync(
            committed -> {
              requireThread(server);
              var receipt = committed.receipt().orElseThrow();
              if (receipt.persistence()
                      != (before == 4
                          ? CommitReceipt.Persistence.SAVED
                          : CommitReceipt.Persistence.UNCHANGED)
                  || level.get() != 9
                  || !label.get().equals("native")
                  || reloads - callbacks != (before == 4 ? 1 : 0)
                  || before == 4 && !callbackThread)
                throw new AssertionError("Native SERVER save/callback/receipt disagreed");
              try {
                if (!Files.readString(config.getFullPath())
                    .contains("Native server source comment"))
                  throw new AssertionError("Native SERVER serializer lost declared comment");
                if (override
                    && !Files.readString(Path.of("config/platform-native-server.toml"))
                        .equals("level = 21\nlabel = \"installation\"\n"))
                  throw new AssertionError("World override save changed installation defaults");
              } catch (java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
              }
              var report = new JsonObject();
              report.addProperty("status", "PASS");
              report.addProperty("persistence", receipt.persistence().toString());
              report.addProperty("nativeReloads", reloads - callbacks);
              report.addProperty("callbackOnServer", before == 9 || callbackThread);
              report.addProperty("actualNativeValue", level.get());
              report.addProperty("preservedOtherField", label.get());
              report.addProperty("nativeType", config.getType().name());
              report.addProperty("worldOverride", override);
              report.addProperty(
                  "nativePath",
                  Path.of("")
                      .toAbsolutePath()
                      .normalize()
                      .relativize(config.getFullPath().toAbsolutePath().normalize())
                      .toString());
              return report;
            },
            server)
        .whenCompleteAsync((ignored, failed) -> editor.close(), server);
  }

  private static void requireThread(MinecraftServer server) {
    if (!server.isSameThread())
      throw new AssertionError("Native server operation left owner thread");
  }

  private NeoForgeServerChecks() {}
}
