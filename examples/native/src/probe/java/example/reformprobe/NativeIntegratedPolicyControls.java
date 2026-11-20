package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.neoforge.client.ClientEditingWorkflow;
import design.inherent.reform.neoforge.client.ClientForm;
import design.inherent.reform.neoforge.client.ClientFormOptions;
import design.inherent.reform.neoforge.client.ConflictScreen;
import design.inherent.reform.neoforge.client.IntegratedSettings;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.command.SearchCommands;
import design.inherent.reform.neoforge.command.SearchRecipient;
import design.inherent.reform.neoforge.configuration.ServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.SearchReport;
import design.inherent.reform.operation.WorkBudget;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.presentation.FormPresentation;
import example.reform.ClientExample;
import example.reform.NativeExample;
import example.reform.ServerPolicyExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
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
  private ClientEditingWorkflow workspace;
  private int workspaceStage;
  private ServerSettings.Document<?> file, registered;
  private UUID player;
  private Screen gate;
  private SettingsScreen<?> form;
  private DocumentActions<?> fileActions, nativeActions;
  private final ArrayList<String> commandFeedback = new ArrayList<>();
  private String retiredOperation;
  private String capturedSearchLink;
  private boolean searchRowReceived;
  private LocalPlayer previousPlayer;
  private ClientPacketListener previousConnection;
  private CompletableFuture<?> pending;
  private Path nativePath;
  private int beforeSaveStage;
  private int beforeSaveWatches;
  private String beforeSaveBytes;
  private CommitReceipt<?> staleNativeReceipt;
  private String savedNativeBytes;
  private int watchStage;
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
    if (text.equals("[retained native search result]")) searchRowReceived = true;
    for (var part : message.getSiblings()) {
      var click = part.getStyle().getClickEvent();
      if (click != null
          && click.getAction() == ClickEvent.Action.RUN_COMMAND
          && click.getValue().startsWith("/reformplayer locate page at "))
        capturedSearchLink = click.getValue();
    }
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
        if (!workspaceControls()) return false;
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
        if (!refreshWatchedDraft()) return false;
        next("submit through native registered owner with its re-observed source");
      }
      case 16 -> {
        if (reopen) {
          if (!done()) return false;
          verifyNativePath((Path) pending.join());
        } else {
          if (form.editorView().lastReceipt().isEmpty()) return false;
          var receipt = form.editorView().lastReceipt().orElseThrow();
          if (receipt == staleNativeReceipt) return false;
          check(
              "registered policy really persists through FML (persistence="
                  + receipt.persistence()
                  + ", application="
                  + receipt.application()
                  + ", persistence issues="
                  + receipt.persistenceReport().issues()
                  + ", application issues="
                  + receipt.applicationReport().issues()
                  + ", actual reloads="
                  + example.reloads()
                  + ")",
              receipt.persistence() == CommitReceipt.Persistence.SAVED);
          var path = nativePath(receipt.observation().orElseThrow().provenance());
          verifyNativePath(path);
          nativePath = path;
          pending =
              server.submit(
                  () -> {
                    var callbacks = example.reloads();
                    if (callbacks.saves() != 1
                        || callbacks.saveThread() != Thread.currentThread()
                        || !server.isSameThread()
                        || !example
                            .nativeConfig()
                            .getFullPath()
                            .toAbsolutePath()
                            .normalize()
                            .equals(path))
                      throw new AssertionError(
                          "Expected one explicit native save reload on its server thread: "
                              + callbacks);
                  });
        }
        next("confirmed native source, checking callback and disconnect lifecycle");
      }
      case 17 -> {
        if (!reopen && !done()) return false;
        if (reopen) {
          step = 26;
          return false;
        }
        if (!watchNativeSource()) return false;
        check("explicit FML save reload is owned by actual integrated server thread", true);
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
        pending = server.submit(this::startSearch);
        next("receive an exact page link over the actual native play connection");
      }
      case 20 -> {
        if (!done() || capturedSearchLink == null) return false;
        check(
            "native match notice emits an identified page link",
            capturedSearchLink.split(" ").length == 7);
        pending = server.submit(() -> server.getPlayerList().getPlayer(player).kill());
        next("native fixture performs an ordinary same-connection respawn");
      }
      case 21 -> {
        if (!done() || !client.player.isDeadOrDying()) return false;
        client.player.respawn();
        next("native client requests respawn");
      }
      case 22 -> {
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
      case 23 -> {
        if (!received("Operation is not retained")) return false;
        check("native stale operation cannot follow the new player", true);
        command("get level");
        next("replacement player opens a new command draft from the actual source");
      }
      case 24 -> {
        if (!received("draft level = 10")) return false;
        check(
            "replacement command draft observes saved 10 and does not inherit unsaved 19",
            savedLevel() == 10 && effectiveLevel() == 10);
        client.getConnection().sendCommand(capturedSearchLink.substring(1));
        next("follow the exact native search page issued before the respawn");
      }
      case 25 -> {
        if (!searchRowReceived) return false;
        check("actual search recipient follows same-connection respawn and retains its page", true);
        open(true);
        next("reopen independent native policy for disconnect control");
      }
      case 26 -> {
        if (!ready()) return false;
        nativeActions = form.editingActions();
        check(
            "current TOML presentation remains associated with running server before disconnect",
            nativeActions.isOpen());
        next("disconnect active policy screen through native world shutdown");
        client.level.disconnect();
        client.disconnect(new TitleScreen());
      }
      case 27 -> {
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

  private <T> ClientForm policyForm(ServerSettings.Document<T> document, String title) {
    return IntegratedSettings.form(
        Component.literal(title),
        document.definition().id(),
        2,
        document.definition(),
        new ClientFormOptions<>(FormPresentation.EMPTY, Map.of()));
  }

  private boolean workspaceControls() throws Exception {
    var fileRoute = file.definition().id();
    var registeredRoute = registered.definition().id();
    switch (workspaceStage) {
      case 0 -> {
        workspace =
            ClientEditingWorkflow.open(
                null,
                Component.literal("Running world settings"),
                List.of(
                    policyForm(file, "Running JSON policy"),
                    policyForm(registered, "Running TOML policy")));
        check(
            "integrated workflow declares both owners without opening either source",
            workspace.documents().size() == 2
                && workspace.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        client.setScreen(workspace.screen());
        workspace.show(fileRoute);
        workspaceStage++;
      }
      case 1 -> {
        if (!ready()) return false;
        check(
            "integrated workflow reaches its actual shared file editor",
            form == workspace.editor(fileRoute).screen()
                && input(form, "Level").getValue().equals("6"));
        replace(form, input(form, "Level"), "7");
        workspaceStage++;
      }
      case 2 -> {
        if (!form.editorView().dirty() || !button(form, "Back to overview").active) return false;
        click(form, button(form, "Back to overview"));
        workspace.show(registeredRoute);
        workspaceStage++;
      }
      case 3 -> {
        if (!ready()) return false;
        check(
            "the registered member uses its own real integrated editor",
            form == workspace.editor(registeredRoute).screen() && !form.editorView().dirty());
        click(form, button(form, "Back to overview"));
        workspace.show(fileRoute);
        workspaceStage++;
      }
      case 4 -> {
        if (!ready()) return false;
        check(
            "returning to the integrated file retains its original draft and saved owner",
            form == workspace.editor(fileRoute).screen()
                && input(form, "Level").getValue().equals("7")
                && savedLevel() == 6
                && effectiveLevel() == 6);
        var original = workspace.editor(fileRoute);
        workspace.close();
        client.setScreen(null);
        check("closing the workspace retires the borrowed local presentation", !original.isOpen());
        workspaceStage++;
        return true;
      }
      default -> throw new IllegalStateException("Integrated workspace controls already completed");
    }
    return false;
  }

  private void startSearch() {
    var host = server.getPlayerList().getPlayer(player);
    var recipient = SearchRecipient.capture(host.createCommandSourceStack(), 2);
    var original = recipient.origin();
    host.teleportTo(original.x + 2, original.y, original.z);
    if (recipient.current().orElseThrow().getPosition().equals(original)
        || !recipient.origin().equals(original)
        || recipient.owns(server.createCommandSourceStack()))
      throw new AssertionError("Search origin or recipient changed with a same-world teleport");
    Runnable thread = file.context()::checkOwnerThread;
    var report = new SearchReport<String>(SearchReport.Limits.STANDARD, thread);
    var budget = new WorkBudget(2, thread);
    var job =
        new SearchCommands.Job<>(
            recipient,
            report,
            budget.group(2),
            Component::literal,
            () -> {
              throw new AssertionError("Unexpected fixture cancellation");
            });
    var bridge = new SearchCommands<String>("reformplayer locate", ignored -> Optional.of(job));
    server
        .getCommands()
        .getDispatcher()
        .register(Commands.literal("reformplayer").then(bridge.branch()));
    report.observe(
        new SearchReport.Progress(1, 1, 0, OptionalLong.empty()),
        1,
        List.of("[retained native search result]"));
    if (!bridge.notifyMatches(job, 1))
      throw new AssertionError("Current host did not receive search notice");
  }

  private boolean refreshWatchedDraft() throws java.io.IOException {
    switch (beforeSaveStage) {
      case 0 -> {
        if (!form.editorView().dirty() || !button(form, "Save and apply").active) return false;
        nativePath = example.nativeConfig().getFullPath().toAbsolutePath().normalize();
        beforeSaveBytes = Files.readString(nativePath);
        beforeSaveWatches = example.reloads().watches();
        replaceNativeSource(beforeSaveBytes);
        beforeSaveStage++;
        progress.accept("waiting for the real native watcher to replace an observed source");
      }
      case 1 -> {
        var events = example.reloads();
        if (events.watches() <= beforeSaveWatches || events.watchedLevel() != 4) return false;
        click(form, button(form, "Save and apply"));
        beforeSaveStage++;
      }
      case 2 -> {
        if (form.editorView().lastReceipt().isEmpty()) return false;
        staleNativeReceipt = form.editorView().lastReceipt().orElseThrow();
        check(
            "real FML reload refuses the old wrapper before any write and retains the draft",
            staleNativeReceipt.persistence() == CommitReceipt.Persistence.REFUSED
                && staleNativeReceipt.application() == CommitReceipt.Application.NOT_ATTEMPTED
                && staleNativeReceipt.persistenceReport().issues().stream()
                    .anyMatch(issue -> issue.code().equals("native.context_changed"))
                && example.reloads().saves() == 0
                && Files.readString(nativePath).equals(beforeSaveBytes)
                && form.editorView().dirty()
                && input(form, "Level").getValue().equals("11"));
        click(form, button(form, "Reload saved"));
        beforeSaveStage++;
      }
      case 3 -> {
        if (form.editorView().review().isEmpty()) return false;
        click(form, button(form, "Review conflict…"));
        beforeSaveStage++;
      }
      case 4 -> {
        if (!(client.screen instanceof ConflictScreen<?> conflict)
            || conflict.reviewedInput().isEmpty()) return false;
        var entries = conflict.reviewedInput().orElseThrow().entries();
        var level = FieldPath.ROOT.field("level");
        check(
            "review shows the retained edit and equivalent native baseline",
            entries.size() == 1
                && entries.getFirst().path().equals(level)
                && entries.getFirst().baseline().equals(entries.getFirst().observed())
                && !entries.getFirst().draft().equals(entries.getFirst().observed()));
        for (int option = 0; option < 4; option++) {
          var choice = conflict.choiceControl(level).orElseThrow();
          if (choice.getMessage().getString().equals("Your draft")) break;
          check(
              "the conflict choice receives a native click",
              conflict.mouseClicked(
                  choice.getX() + choice.getWidth() / 2.0,
                  choice.getY() + choice.getHeight() / 2.0,
                  0));
          conflict.mouseReleased(choice.getX() + 1, choice.getY() + 1, 0);
        }
        var resolve = button(conflict, "Resolve draft");
        check(
            "the current draft is chosen explicitly before resolution",
            conflict
                    .choiceControl(level)
                    .orElseThrow()
                    .getMessage()
                    .getString()
                    .equals("Your draft")
                && resolve.active);
        click(conflict, resolve);
        beforeSaveStage++;
      }
      case 5 -> {
        if (client.screen != form
            || form.editorView().review().isPresent()
            || !button(form, "Save and apply").active) return false;
        check(
            "explicit re-observation and review retain the typed change against the fresh source",
            form.editorView().dirty()
                && input(form, "Level").getValue().equals("11")
                && example.reloads().saves() == 0);
        click(form, button(form, "Save and apply"));
        return true;
      }
      default -> throw new IllegalStateException("Unexpected pre-save Watcher stage");
    }
    return false;
  }

  private boolean watchNativeSource() throws java.io.IOException {
    var observed = example.reloads();
    switch (watchStage) {
      case 0 -> {
        savedNativeBytes = Files.readString(nativePath);
        var changed = savedNativeBytes.replace("level = 11", "level = 12");
        if (changed.equals(savedNativeBytes))
          throw new AssertionError("Missing native saved value");
        replaceNativeSource(changed);
        watchStage = 1;
        progress.accept("waiting for the native Watcher to observe an external change");
      }
      case 1 -> {
        if (observed.watches() == 0 || observed.watchedLevel() != 12) return false;
        check(
            "native Watcher observes the actual external edit independently of the direct save",
            observed.saves() == 1
                && observed.watchThread() != null
                && observed.watchThread() != observed.saveThread());
        replaceNativeSource(savedNativeBytes);
        watchStage = 2;
      }
      case 2 -> {
        if (observed.watchedLevel() != 11) return false;
        check("restoring disk makes the Watcher reload without a new save", observed.saves() == 1);
        return true;
      }
      default -> throw new AssertionError("Unexpected native Watcher stage");
    }
    return false;
  }

  private void replaceNativeSource(String bytes) throws java.io.IOException {
    var pendingWrite =
        Files.createTempFile(nativePath.getParent(), "reform-native-fixture-", ".toml");
    try {
      Files.writeString(pendingWrite, bytes);
      Files.move(
          pendingWrite,
          nativePath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pendingWrite);
    }
  }

  JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.addProperty("stage", step);
    report.add("checks", checks);
    var events = example.reloads();
    var reloads = new JsonObject();
    reloads.addProperty("directSaves", events.saves());
    reloads.addProperty("fileWatcherReloads", events.watches());
    reloads.addProperty("otherReloads", events.other());
    report.add("nativeReloads", reloads);
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
