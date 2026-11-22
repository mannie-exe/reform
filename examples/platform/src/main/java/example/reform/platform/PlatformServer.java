package example.reform.platform;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.minecraft.configuration.ServerPolicyCommands;
import design.inherent.reform.minecraft.configuration.ServerRegistration;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.minecraft.reference.NativeReferences;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.OwnerObservation;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.PreparedEdit;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.storage.StorageBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** A separately resolved, bounded native consumer. It never includes re:form source roots. */
public final class PlatformServer {
  public static final String ID = "reform_platform_example";

  private static final DocumentDefinition<PlatformData.Values> DEFINITION =
      PlatformData.definition("settings");
  private static final JsonObject report = new JsonObject();
  private static final java.util.List<String> passed = new java.util.ArrayList<>();
  private static ServerSettings.Document<PlatformData.Values> document;
  private static OwnerContext.Access access;
  private static DocumentSession<PlatformData.Values> session;
  private static CompletableFuture<?> pending;
  private static PlatformServerCommandChecks commandChecks;
  private static final Path OUTPUT = Path.of("platform-report.json");
  private static int stage, ticks, checks;
  private static boolean reopen, finished;
  private static java.util.function.Function<
          MinecraftServer, java.util.concurrent.CompletionStage<JsonObject>>
      extra = ignored -> CompletableFuture.completedStage(new JsonObject());

  public static void extraChecks(
      java.util.function.Function<MinecraftServer, java.util.concurrent.CompletionStage<JsonObject>>
          checks) {
    extra = java.util.Objects.requireNonNull(checks);
  }

  public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
      commandTree() {
    return Commands.literal("reformplatform").then(ServerPolicyCommands.branch("file", DEFINITION));
  }

  public static void start(
      MinecraftServer server, ServerRegistration registration, String game, String loader) {
    try {
      var input =
          JsonParser.parseString(Files.readString(Path.of("platform-input.json")))
              .getAsJsonObject();
      reopen = input.get("reopen").getAsBoolean();
      report.addProperty("status", "RUNNING");
      if (input.has("connector") && input.get("connector").getAsBoolean())
        report.add("connector", NativeConnectorProbe.verify());
      report.addProperty("java", Runtime.version().toString());
      report.addProperty("javaVendor", System.getProperty("java.vendor"));
      report.addProperty("minecraft", game);
      report.addProperty("loader", loader);
      report.addProperty(
          "loadedLibrary",
          ServerSettings.class.getProtectionDomain().getCodeSource().getLocation().toString());
      report.addProperty("librarySha256", input.get("librarySha256").getAsString());
      check(
          "library is loaded from a packaged JAR",
          report.get("loadedLibrary").getAsString().contains(".jar"));
      var scope = ServerSettings.forServer(server);
      document =
          scope.serverPolicy(
              registration,
              DEFINITION,
              StorageBinding.relative(
                  server.getWorldPath(LevelResource.ROOT).toAbsolutePath(),
                  "serverconfig",
                  "reform-platform.json"));
      check("same native server retains its scope", scope == ServerSettings.forServer(server));
      try {
        document.editor(server.createCommandSourceStack().withSource(CommandSource.NULL));
        throw new AssertionError("foreign feedback source admitted");
      } catch (PolicyRefusal expected) {
        check("foreign feedback source is refused", true);
      }
      var editor = document.editor(server.createCommandSourceStack());
      editor.close();
      access = document.context().access(server::isRunning, server::isRunning);
      session = document.session(access);
      pending = document.initialization().orElseThrow().toCompletableFuture();
    } catch (Throwable failure) {
      fail(server, failure);
    }
  }

  public static void tick(MinecraftServer server) {
    if (finished || document == null) return;
    try {
      if (++ticks > 2400) throw new AssertionError("platform fixture exceeded its tick budget");
      if (commandChecks != null) commandChecks.tick();
      if (!pending.isDone()) return;
      var result = pending.join();
      switch (stage++) {
        case 0 -> {
          var value = document.runtime().orElseThrow().view().value().orElseThrow();
          check(
              "native initialization reads the correct saved/default value",
              value.distance() == (reopen ? 9 : 7));
          var stamp =
              new ReferenceStamp(
                  document.context().id(), DEFINITION.id(), UUID.randomUUID(), "native");
          int loaded = server.overworld().getChunkSource().getLoadedChunksCount();
          var capture =
              NativeReferences.captureRegistry(
                  document.context(),
                  stamp,
                  BuiltInRegistries.BLOCK,
                  true,
                  NativeReferences.Limits.STANDARD);
          check(
              "registry capture does not load chunks",
              loaded == server.overworld().getChunkSource().getLoadedChunksCount());
          pending =
              document
                  .context()
                  .onWorker(
                      () -> {
                        var index = capture.build();
                        check(
                            "worker native ID resolution preserves default namespace",
                            index
                                .resolve("stone")
                                .value()
                                .orElseThrow()
                                .equals(List.of("minecraft:stone")));
                        check(
                            "unknown native ID is distinct",
                            index.resolve("minecraft:reform_missing").status()
                                == ReferenceResolution.Status.UNKNOWN_REFERENCE);
                        check(
                            "malformed native ID refuses",
                            index.resolve("bad namespace:item").status()
                                == ReferenceResolution.Status.MALFORMED);
                        var tag = index.resolve("#minecraft:mineable/pickaxe");
                        check(
                            "native named tag retains actual membership",
                            tag.status() == ReferenceResolution.Status.RESOLVED
                                && !tag.value().orElseThrow().isEmpty());
                        return null;
                      })
                  .toCompletableFuture();
        }
        case 1 -> pending = session.read().toCompletableFuture();
        case 2 -> {
          @SuppressWarnings("unchecked")
          var observation = (OwnerObservation<PlatformData.Values>) result;
          pending =
              session
                  .prepare(
                      observation,
                      DraftTree.from(
                          DEFINITION.type(),
                          new PlatformData.Values(9, "platform"),
                          DraftLimits.CONFIGURATION))
                  .toCompletableFuture();
        }
        case 3 -> {
          @SuppressWarnings("unchecked")
          var prepared = (PreparedEdit<PlatformData.Values>) result;
          pending = session.commit(prepared).toCompletableFuture();
        }
        case 4 -> {
          var receipt = (CommitReceipt<?>) result;
          check(
              "native source persistence outcome is exact",
              receipt.persistence()
                  == (reopen
                      ? CommitReceipt.Persistence.UNCHANGED
                      : CommitReceipt.Persistence.SAVED));
          check(
              "runtime publication agrees with saved value",
              document.runtime().orElseThrow().view().value().orElseThrow().distance() == 9);
          session.close();
          access.close();
          pending = extra.apply(server).toCompletableFuture();
        }
        case 5 -> {
          report.add("nativeOwner", (JsonObject) result);
          commandChecks =
              new PlatformServerCommandChecks(
                  server,
                  document,
                  server
                      .getWorldPath(LevelResource.ROOT)
                      .resolve("serverconfig/reform-platform.json"));
          pending = commandChecks.result().toCompletableFuture();
        }
        case 6 -> {
          report.add("nativeCommands", (JsonObject) result);
          pending =
              PlatformServerSearchChecks.run(server, document.context()).toCompletableFuture();
        }
        case 7 -> {
          @SuppressWarnings("unchecked")
          var searchChecks = (List<String>) result;
          var search = new JsonObject();
          search.addProperty("status", "PASS");
          search.add("checks", new com.google.gson.Gson().toJsonTree(searchChecks));
          report.add("nativeSearch", search);
          finished = true;
          report.addProperty("status", "PASS");
          server.halt(false);
        }
        default -> throw new AssertionError("unexpected platform stage");
      }
    } catch (Throwable failure) {
      fail(server, failure);
    }
  }

  private static void check(String description, boolean valid) {
    if (!valid) throw new AssertionError(description);
    checks++;
    passed.add(description);
  }

  private static void fail(MinecraftServer server, Throwable failure) {
    finished = true;
    report.addProperty("status", "FAIL");
    var text = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(text));
    report.addProperty("failure", text.toString());
    server.halt(false);
  }

  public static void stopped(MinecraftServer server) {
    try {
      if (commandChecks != null) commandChecks.close();
      if (document != null) {
        check("native stop closes owner context", !document.context().isOpen());
        check(
            "native stop removes metadata",
            ServerSettings.admittedDocument(server, DEFINITION.id()).isEmpty());
      }
      report.addProperty("checks", checks);
      report.add("controls", new com.google.gson.Gson().toJsonTree(passed));
      report.addProperty("ticks", ticks);
      report.addProperty("shutdownClosed", document != null && !document.context().isOpen());
      Files.writeString(OUTPUT, new GsonBuilder().setPrettyPrinting().create().toJson(report));
    } catch (Exception | AssertionError failure) {
      throw new IllegalStateException("Could not retain platform fixture result", failure);
    }
  }

  private PlatformServer() {}
}
