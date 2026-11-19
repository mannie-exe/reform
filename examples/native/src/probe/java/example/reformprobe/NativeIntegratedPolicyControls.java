package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.neoforge.client.IntegratedSettings;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.configuration.ServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.owner.SourceInfo;
import example.reform.ClientExample;
import example.reform.NativeExample;
import example.reform.ServerPolicyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/** Uses an actual integrated server and native memory connection in a disposable game directory. */
final class NativeIntegratedPolicyControls {
  private static final String WORLD = "reform-policy";
  private final Minecraft client = Minecraft.getInstance();
  private final ServerPolicyExample example = NativeExample.serverPolicies();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final Path sources =
      client.gameDirectory.toPath().resolve("saves").resolve(WORLD).resolve("serverconfig");
  private final CountDownLatch release = new CountDownLatch(1);
  private final CountDownLatch holding = new CountDownLatch(1);
  private IntegratedServer server;
  private ServerSettings.Document<?> file, registered;
  private UUID player;
  private Screen gate;
  private SettingsScreen<?> form;
  private DocumentActions<?> fileActions, nativeActions;
  private final ArrayList<String> commandFeedback = new ArrayList<>();
  private String retiredOperation;
  private LocalPlayer previousPlayer;
  private ClientPacketListener previousConnection;
  private CompletableFuture<?> pending;
  private int step;
  private boolean complete;

  NativeIntegratedPolicyControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    step++;
    progress.accept(label);
  }

  void receive(Component message) {
    var text = message.getString();
    if (commandFeedback.size() < 32
        && (text.startsWith(ServerPolicyExample.DEFINITION.id().toString())
            || text.contains("Operation is not retained"))) commandFeedback.add(text);
  }

  private void command(String action) {
    commandFeedback.clear();
    client.getConnection().sendCommand("reformpolicy file " + action);
  }

  private boolean received(String part) {
    return commandFeedback.stream().anyMatch(reply -> reply.contains(part));
  }

  private void open(boolean toml) {
    gate = ClientExample.worldPolicy(null, toml);
    client.setScreen(gate);
    form = null;
  }

  private boolean ready() {
    if (!(client.screen instanceof SettingsScreen<?> opened)) return false;
    form = opened;
    return opened.editorView().availability() == EditorActor.Availability.READY
        && button(opened, "Review changes…").active;
  }

  private boolean done() {
    if (!pending.isDone()) return false;
    pending.join();
    return true;
  }

  private int effectiveLevel() {
    return ((ServerPolicyExample.Policy) file.runtime().orElseThrow().view().value().orElseThrow())
        .level();
  }

  private int savedLevel() throws Exception {
    return JsonParser.parseString(Files.readString(sources.resolve(ServerPolicyExample.FILE)))
        .getAsJsonObject()
        .getAsJsonObject("values")
        .get("level")
        .getAsInt();
  }

  private Path nativePath(SourceInfo source) {
    return Path.of(source.location().orElseThrow()).toAbsolutePath().normalize();
  }

  private void verifyNativePath(Path path) throws Exception {
    var expected =
        client
            .gameDirectory
            .toPath()
            .toAbsolutePath()
            .normalize()
            .resolve("config")
            .resolve(ServerPolicyExample.NATIVE_FILE);
    check(
        "native owner retains loader-selected installation source in the absence of a world override",
        path.equals(expected) && Files.notExists(sources.resolve(ServerPolicyExample.NATIVE_FILE)));
    check(
        "native TOML contains the intended saved value",
        Files.readString(path).contains("level = 11"));
  }

  private void startWorld() {
    if (reopen) {
      client.createWorldOpenFlows().openWorld(WORLD, () -> client.setScreen(new TitleScreen()));
      return;
    }
    var rules = new GameRules();
    rules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0, null);
    client
        .createWorldOpenFlows()
        .createFreshLevel(
            WORLD,
            new LevelSettings(
                "Re:form policy fixture",
                GameType.CREATIVE,
                false,
                Difficulty.PEACEFUL,
                false,
                rules,
                WorldDataConfiguration.DEFAULT),
            new WorldOptions(42L, false, false),
            registries ->
                registries
                    .registryOrThrow(Registries.WORLD_PRESET)
                    .getHolderOrThrow(WorldPresets.FLAT)
                    .value()
                    .createWorldDimensions(),
            client.screen);
  }

  boolean tick() throws Exception {
    switch (step) {
      case 0 -> {
        var title = client.screen;
        var unavailable = ClientExample.worldPolicy(title, false);
        client.setScreen(unavailable);
        check(
            "title screen has no current policy",
            unavailable.getNarrationMessage().getString().contains("Open a local world"));
        unavailable.onClose();
        check("unavailable entry retains native return destination", client.screen == title);
        next("opening ordinary native flat world");
        startWorld();
      }
      case 1 -> {
        if (client.player == null || client.level == null || client.screen != null) return false;
        server = client.getSingleplayerServer();
        check(
            "connected through actual native memory transport",
            server != null && client.getConnection().getConnection().isMemoryConnection());
        player = client.player.getUUID();
        file =
            ServerSettings.admittedDocument(server, ServerPolicyExample.DEFINITION.id())
                .orElseThrow();
        registered =
            ServerSettings.admittedDocument(server, ServerPolicyExample.NATIVE).orElseThrow();
        var missing =
            IntegratedSettings.screen(
                null,
                Component.literal("Missing policy"),
                DocumentId.parse(NativeExample.ID + ":missing_policy"));
        client.setScreen(missing);
        check(
            "missing registration has a visible explanation",
            missing.getNarrationMessage().getString().contains("no registered policy"));
        missing.onClose();
        if (reopen) {
          pending =
              server.submit(
                  () ->
                      server
                          .getPlayerList()
                          .op(server.getPlayerList().getPlayer(player).getGameProfile()));
          step = 8;
          progress.accept("fresh JVM grants native operator permission for the existing world");
          return false;
        }
        check("fixture starts with no native command permission", !client.player.hasPermissions(2));
        // Test-only divergent client mirror. The server must still refuse opening the source.
        client.player.setPermissionLevel(2);
        open(false);
        next("deliberately stale client permission requests actual server admission");
      }
      case 2 -> {
        if (!gate.getNarrationMessage().getString().contains("Current permission is required"))
          return false;
        check("client permission mirror cannot open server policy", client.screen == gate);
        gate.onClose();
        pending =
            server.submit(
                () -> {
                  var host = server.getPlayerList().getPlayer(player);
                  if (!server.isSameThread() || host == null || host.hasPermissions(2))
                    throw new AssertionError("Expected the actual unprivileged native host");
                  try (var refused =
                      file.localPresentation(server.createCommandSourceStack(), 0, () -> true)) {
                    throw new AssertionError(
                        "Server console impersonated the integrated host: " + refused.isOpen());
                  } catch (PolicyRefusal expected) {
                    if (expected.report().issues().stream()
                        .noneMatch(i -> i.code().equals("owner.permission"))) throw expected;
                  }
                  try (var allowed =
                      file.localPresentation(host.createCommandSourceStack(), 0, () -> true)) {
                    if (!allowed.isOpen())
                      throw new AssertionError("Explicit native level zero must be available");
                  }
                  server.getPlayerList().op(host.getGameProfile());
                });
        next("native controls refuse console substitution and admit declared level-zero access");
      }
      case 3 -> {
        if (!done() || !client.player.hasPermissions(2)) return false;
        pending =
            server.submit(
                () -> {
                  holding.countDown();
                  try {
                    if (!release.await(10, TimeUnit.SECONDS))
                      throw new AssertionError("Holding native executor timed out");
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                  }
                });
        next("holding the actual server executor while presentation opens are abandoned");
      }
      case 4 -> {
        if (holding.getCount() != 0) return false;
        try {
          for (int i = 0; i < 72; i++) {
            open(false);
            gate.onClose();
          }
          check(
              "installed server document bounds queued presentations at sixty-four",
              file.context().outstandingCalls() == 64);
        } finally {
          release.countDown();
        }
        next("retired loader views cannot acquire native sessions when their queue drains");
      }
      case 5 -> {
        if (!done() || file.context().outstandingCalls() != 0) return false;
        pending =
            file.context()
                .onOwner(
                    () -> {
                      var leases = new ArrayList<OwnerContext.Access>();
                      try {
                        for (int i = 0; i < 64; i++)
                          leases.add(file.context().access(() -> true, () -> false));
                        return leases.size();
                      } finally {
                        leases.forEach(OwnerContext.Access::close);
                      }
                    })
                .toCompletableFuture();
        next("observable native admission capacity after abandoned queue recovery");
      }
      case 6 -> {
        if (!done()) return false;
        check("abandoned admissions left no native access leases", pending.join().equals(64));
        open(false);
        next("ordinary public factory opens current world file policy");
      }
      case 7 -> {
        if (!ready()) return false;
        fileActions = form.editingActions();
        var baseline = file.context().id();
        var effectiveContext = file.runtime().orElseThrow().view().context();
        check(
            "GUI and owner's native context have distinct identity",
            !fileActions.context().equals(baseline));
        check(
            "file form reflects actual saved and effective world value",
            input(form, "Level").getValue().equals("6")
                && savedLevel() == 6
                && effectiveLevel() == 6);
        var review = fileActions.review(EntryPoint.GUI);
        pending =
            DocumentActions.result(review)
                .thenAccept(
                    result -> {
                      if (!result.target().context().equals(fileActions.context())
                          || result
                              .effective()
                              .filter(effective -> effective.context().equals(effectiveContext))
                              .isEmpty())
                        throw new AssertionError("Review erased GUI or runtime handle identity");
                    })
                .toCompletableFuture();
        geometry(form);
        capture.accept("form-integrated-file.png", () -> replace(form, input(form, "Level"), "10"));
        next("world policy review exposes actual source and native effective context");
      }
      case 8 -> {
        if (reopen) {
          if (gate == null) {
            if (!done() || !client.player.hasPermissions(2)) return false;
            open(false);
          }
          if (!ready()) return false;
          fileActions = form.editingActions();
          check(
              "fresh JVM reads saved policy and publishes it for this world",
              input(form, "Level").getValue().equals("10")
                  && savedLevel() == 10
                  && effectiveLevel() == 10);
          form.onClose();
          open(true);
          step = 13;
          progress.accept("fresh JVM opens actual native file policy");
          return false;
        }
        if (!done() || !form.editorView().dirty() || !button(form, "Save and apply").active)
          return false;
        check(
            "draft edit has no saved or running effect",
            savedLevel() == 6 && effectiveLevel() == 6);
        click(form, button(form, "Save and apply"));
        next("native GUI submits file change with current server authority");
      }
      case 9 -> {
        if (form.editorView().lastReceipt().isEmpty()) return false;
        var receipt = form.editorView().lastReceipt().orElseThrow();
        check(
            "saved file receipt confirms independent persistence and application",
            receipt.persistence() == CommitReceipt.Persistence.SAVED
                && receipt.application() == CommitReceipt.Application.APPLIED
                && savedLevel() == 10
                && effectiveLevel() == 10
                && !form.editorView().dirty());
        check(
            "save readback retains issuing server owner",
            receipt.observation().orElseThrow().context().equals(file.context().id()));
        replace(form, input(form, "Level"), "14");
        // Hold a stale positive client mirror while changing the real native op list.
        pending =
            server.submit(
                () -> {
                  var host = server.getPlayerList().getPlayer(player);
                  server.getPlayerList().getOps().remove(host.getGameProfile());
                  if (host.hasPermissions(2))
                    throw new AssertionError("Actual permission was not removed");
                });
        next("actual host permission revoked while a draft remains open");
      }
      case 10 -> {
        if (!done() || !form.editorView().dirty() || !button(form, "Save and apply").active)
          return false;
        check("client still has stale positive permission mirror", client.player.hasPermissions(2));
        click(form, button(form, "Save and apply"));
        next("attempt to submit stale permission through normal GUI save");
      }
      case 11 -> {
        if (form.editorView().operationReport().issues().stream()
            .noneMatch(i -> i.code().equals("owner.permission"))) return false;
        check(
            "actual server refuses later save and keeps the local draft",
            form.editorView().dirty()
                && form.editorView().pending().isEmpty()
                && savedLevel() == 10
                && effectiveLevel() == 10);
        pending =
            server.submit(
                () ->
                    server
                        .getPlayerList()
                        .sendPlayerPermissionLevel(server.getPlayerList().getPlayer(player)));
        next("native permission packet retires the stale presentation");
      }
      case 12 -> {
        if (!done() || client.player.hasPermissions(2) || fileActions.isOpen()) return false;
        check(
            "permission refresh retires local context, retains real owner",
            file.context().isOpen()
                && fileActions.view().snapshot().isEmpty()
                && effectiveLevel() == 10);
        var revoked = fileActions;
        try {
          revoked.save(EntryPoint.GUI);
          throw new AssertionError("Closed view received a new operation");
        } catch (PolicyRefusal expected) {
          check(
              "retired action cannot be replayed",
              expected.report().issues().getFirst().code().equals("owner.closed"));
        }
        pending =
            server.submit(
                () ->
                    server
                        .getPlayerList()
                        .op(server.getPlayerList().getPlayer(player).getGameProfile()));
        client.setScreen(null);
        next("restore permission for the separate native TOML owner");
      }
      case 13 -> {
        if (!reopen) {
          if (!done() || !client.player.hasPermissions(2)) return false;
          open(true);
          next("open native policy after actual permission recovery");
          return false;
        }
        if (!ready()) return false;
        nativeActions = form.editingActions();
        check(
            "fresh native loader and GUI both retain TOML value",
            input(form, "Level").getValue().equals("11"));
        pending =
            DocumentActions.result(nativeActions.explain(EntryPoint.GUI))
                .thenApply(result -> nativePath(result.provenance()))
                .toCompletableFuture();
        step = 16;
        return false;
      }
      case 14 -> {
        if (!ready()) return false;
        nativeActions = form.editingActions();
        check(
            "registered native source has independent owner and starting value",
            !nativeActions.context().equals(fileActions.context())
                && registered.runtime().isEmpty()
                && input(form, "Level").getValue().equals("4"));
        capture.accept("form-integrated-toml.png", () -> replace(form, input(form, "Level"), "11"));
        next("native TOML presentation uses the same generated settings form");
      }
      case 15 -> {
        if (!form.editorView().dirty() || !button(form, "Save and apply").active) return false;
        click(form, button(form, "Save and apply"));
        next("submit through native registered owner");
      }
      case 16 -> {
        if (reopen) {
          if (!done()) return false;
          verifyNativePath((Path) pending.join());
        } else {
          if (form.editorView().lastReceipt().isEmpty()) return false;
          var receipt = form.editorView().lastReceipt().orElseThrow();
          check(
              "registered policy really persists through FML",
              receipt.persistence() == CommitReceipt.Persistence.SAVED);
          var path = nativePath(receipt.observation().orElseThrow().provenance());
          verifyNativePath(path);
          pending =
              server.submit(
                  () -> {
                    if (example.reloads() != 1
                        || example.reloadThread() != Thread.currentThread()
                        || !server.isSameThread()
                        || !example
                            .nativeConfig()
                            .getFullPath()
                            .toAbsolutePath()
                            .normalize()
                            .equals(path))
                      throw new AssertionError(
                          "Native registered reload did not run exactly once on the integrated server thread");
                  });
        }
        next("confirmed native source, checking callback and disconnect lifecycle");
      }
      case 17 -> {
        if (!reopen && !done()) return false;
        if (reopen) {
          step = 24;
          return false;
        }
        check("FML callback is owned by actual integrated server thread", true);
        command("set level 19");
        next("actual host sends a vanilla command over the memory connection");
      }
      case 18 -> {
        if (!received("dirty=true")) return false;
        var prefix = ServerPolicyExample.DEFINITION.id() + ": queued ";
        retiredOperation =
            UUID.fromString(
                    commandFeedback.stream()
                        .filter(reply -> reply.startsWith(prefix))
                        .findFirst()
                        .orElseThrow()
                        .substring(prefix.length()))
                .toString();
        check(
            "native player command stages its own draft without persistence",
            savedLevel() == 10 && effectiveLevel() == 10);
        command("status " + retiredOperation);
        next("inspect the exact retained operation via actual player chat");
      }
      case 19 -> {
        if (!received("dirty=true")) return false;
        check(
            "native operator receives retained status through real system chat",
            !received("queued"));
        previousPlayer = client.player;
        previousConnection = client.getConnection();
        pending = server.submit(() -> server.getPlayerList().getPlayer(player).kill());
        next("native fixture performs an ordinary same-connection respawn");
      }
      case 20 -> {
        if (!done() || !client.player.isDeadOrDying()) return false;
        client.player.respawn();
        next("native client requests respawn");
      }
      case 21 -> {
        if (client.player == previousPlayer
            || !client.player.hasPermissions(2)
            || nativeActions.isOpen()) return false;
        check(
            "respawn retires previous forms while retaining actual server and play connection",
            server == client.getSingleplayerServer()
                && previousConnection == client.getConnection()
                && registered.context().isOpen()
                && file.context().isOpen());
        command("status " + retiredOperation);
        next("status request from replacement player uses the old operation identity");
      }
      case 22 -> {
        if (!received("Operation is not retained")) return false;
        check("native stale operation cannot follow the new player", true);
        command("get level");
        next("replacement player opens a new command draft from the actual source");
      }
      case 23 -> {
        if (!received("draft level = 10")) return false;
        check(
            "replacement command draft observes saved 10 and does not inherit unsaved 19",
            savedLevel() == 10 && effectiveLevel() == 10);
        open(true);
        next("reopen independent native policy for disconnect control");
      }
      case 24 -> {
        if (!ready()) return false;
        nativeActions = form.editingActions();
        check(
            "current TOML presentation remains associated with running server before disconnect",
            nativeActions.isOpen());
        next("disconnect active policy screen through native world shutdown");
        client.level.disconnect();
        client.disconnect(new TitleScreen());
      }
      case 25 -> {
        if (client.getSingleplayerServer() != null || client.player != null || !server.isShutdown())
          return false;
        check(
            "native disconnect closes active form and actual owners",
            !fileActions.isOpen()
                && !nativeActions.isOpen()
                && !registered.context().isOpen()
                && file.runtime().orElseThrow().view().availability()
                    == SettingsHandle.Availability.CLOSED);
        check(
            "closed server policy has no registration to fall back to",
            ServerSettings.admittedDocument(server, ServerPolicyExample.NATIVE).isEmpty());
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unexpected integrated policy stage " + step);
    }
    return false;
  }

  JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.addProperty("stage", step);
    report.add("checks", checks);
    report.addProperty(
        "contextsClosed",
        complete
            && !fileActions.isOpen()
            && !nativeActions.isOpen()
            && !file.context().isOpen()
            && !registered.context().isOpen());
    return report;
  }
}
