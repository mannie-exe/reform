package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.neoforge.client.ClientRegisteredSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import java.nio.file.Files;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Real registered CLIENT source, serializer and native callbacks; no replacement writer. */
final class NeoForgeRegisteredChecks {
  private static ClientRegisteredSettings registration;
  private static ModConfig config;
  private static ModConfigSpec.IntValue level;
  private static ModConfigSpec.ConfigValue<String> label;
  private static int reloads;
  private static boolean callbackThread;

  static void register(IEventBus bus, ModContainer container) {
    var builder = new ModConfigSpec.Builder();
    level = builder.comment("Native retained source comment").defineInRange("level", 4, 0, 32);
    label = builder.define("label", "native");
    config =
        ConfigTracker.INSTANCE.registerConfig(
            ModConfig.Type.CLIENT, builder.build(), container, "platform-native-client.toml");
    registration =
        ClientRegisteredSettings.registeredNeoForge(
            bus, container, DocumentId.parse(PlatformServer.ID + ":native_client"), config);
    if (registration.status().lifecycle() != ClientRegisteredSettings.Lifecycle.REGISTERED
        || registration.definition().isPresent())
      throw new AssertionError("Native registered metadata performed early binding");
    bus.addListener(
        (ModConfigEvent.Reloading event) -> {
          if (event.getConfig() == config) {
            reloads++;
            callbackThread =
                Minecraft.getInstance() != null && Minecraft.getInstance().isSameThread();
          }
        });
    PlatformClient.extraChecks(NeoForgeRegisteredChecks::run);
  }

  private static CompletionStage<JsonObject> run() {
    requireThread();
    var client = Minecraft.getInstance();
    if (registration.status().lifecycle() != ClientRegisteredSettings.Lifecycle.READY
        || config.getLoadedConfig() == null)
      throw new AssertionError("Native registered settings did not bind through client setup");
    var actions = registration.openActions();
    int before = level.get(), callbacks = reloads;
    if (before != 4 && before != 9) throw new AssertionError("Unexpected actual native baseline");
    return DocumentActions.result(actions.ensureLoaded(EntryPoint.GUI))
        .thenComposeAsync(
            view -> {
              requireThread();
              return DocumentActions.result(actions.edit(EntryPoint.GUI, "level", "900"));
            },
            client)
        .thenComposeAsync(
            draft -> {
              requireThread();
              return DocumentActions.result(actions.preflight(EntryPoint.GUI));
            },
            client)
        .thenComposeAsync(
            invalid -> {
              requireThread();
              if (invalid.report().valid()
                  || invalid.report().issues().stream()
                      .noneMatch(issue -> issue.code().equals("native.validation"))
                  || level.get() != before
                  || reloads != callbacks)
                throw new AssertionError(
                    "Invalid native draft escaped validation or altered the source");
              return DocumentActions.result(actions.edit(EntryPoint.GUI, "level", "9"));
            },
            client)
        .thenComposeAsync(
            valid -> {
              requireThread();
              return DocumentActions.result(actions.commit(EntryPoint.GUI));
            },
            client)
        .thenApplyAsync(
            committed -> {
              requireThread();
              var receipt = committed.receipt().orElseThrow();
              if (receipt.persistence()
                      != (before == 4
                          ? CommitReceipt.Persistence.SAVED
                          : CommitReceipt.Persistence.UNCHANGED)
                  || level.get() != 9
                  || !label.get().equals("native")
                  || reloads - callbacks != (before == 4 ? 1 : 0)
                  || before == 4 && !callbackThread)
                throw new AssertionError(
                    "Native setter/save/callback outcome disagreed with its receipt");
              try {
                if (!Files.readString(config.getFullPath())
                    .contains("Native retained source comment"))
                  throw new AssertionError("Native serializer lost its declared comment");
              } catch (java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
              }
              var report = new JsonObject();
              report.addProperty("status", "PASS");
              report.addProperty("persistence", receipt.persistence().toString());
              report.addProperty("nativeReloads", reloads - callbacks);
              report.addProperty("callbackOnClient", before == 9 || callbackThread);
              report.addProperty("actualNativeValue", level.get());
              report.addProperty("preservedOtherField", label.get());
              return report;
            },
            client)
        .whenCompleteAsync((ignored, failed) -> actions.close(), client);
  }

  private static void requireThread() {
    if (!Minecraft.getInstance().isSameThread())
      throw new AssertionError("Native callback left the client owner thread");
  }

  private NeoForgeRegisteredChecks() {}
}
