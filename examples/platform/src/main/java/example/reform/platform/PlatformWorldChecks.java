package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.IntegratedSettings;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.minecraft.configuration.ServerRegistration;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.storage.StorageBinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Actual integrated connections, command provenance and public current-world policy presentation.
 */
public final class PlatformWorldChecks {
  static final String WORLD = "reform-platform-world";
  private static final DocumentDefinition<PlatformData.Values> definition =
      PlatformData.definition("world");
  private static volatile ServerSettings.Document<PlatformData.Values> current;
  private static volatile MinecraftServer currentServer;
  private static ServerRegistration serverRegistration;
  private static volatile boolean stopped;
  private static Check active;

  static void register(
      ClientRegistration registration, ClientFileSettings<PlatformData.Values> settings) {
    registration.registerCommands(
        "platformclient",
        new ClientRegistration.CommandTree() {
          @Override
          public <S> LiteralArgumentBuilder<S> bind(ClientRegistration.CommandSource<S> source) {
            return settings.commandBranch(
                "platformclient",
                new ClientRegistration.CommandSource<S>() {
                  @Override
                  public boolean current(S actor) {
                    return source.current(actor);
                  }

                  @Override
                  public void requireLocalInvocation(S actor) {
                    source.requireLocalInvocation(actor);
                    if (active != null && active.firstSource == null)
                      active.firstSource = () -> source.current(actor);
                  }
                },
                reply -> {
                  if (active != null) active.reply(reply);
                });
          }
        });
  }

  public static void serverStarted(MinecraftServer server, ServerRegistration registration) {
    currentServer = server;
    serverRegistration = registration;
    PlatformHeldPolicyChecks.start(server, registration);
    current =
        ServerSettings.forServer(server)
            .serverPolicy(
                registration,
                definition,
                StorageBinding.relative(
                    server.getWorldPath(LevelResource.ROOT).toAbsolutePath(),
                    "serverconfig",
                    "platform-world.json"));
    stopped = false;
  }

  public static void serverStopped(MinecraftServer server) {
    PlatformHeldStopChecks.stopped(server);
    PlatformHeldPolicyChecks.stopped(server);
    PlatformPlayerSearchChecks.serverStopped(server);
    PlatformRecipientChecks.serverStopped(server);
    if (current != null && currentServer == server) {
      if (current.context().isOpen()) throw new AssertionError("Native stop retained world owner");
      stopped = true;
    }
  }

  public static void received(Component message) {
    PlatformPlayerSearchChecks.received(message);
    if (active != null && message.getString().equals("re:form fixture link")) {
      if (!Minecraft.getInstance().isSameThread())
        throw new AssertionError("Native message delivery left client thread");
      if (active.links.size() >= 4) throw new AssertionError("Unexpected native fixture links");
      active.links.add(message);
    }
  }

  static CompletionStage<JsonObject> run(
      ClientRegistration registration,
      ClientFileSettings<PlatformData.Values> settings,
      boolean reopen) {
    active = new Check(registration, settings, reopen);
    active.subscription = registration.onTick(active::tick);
    return active.result.minimalCompletionStage();
  }

  private static final class Check {
    private final Minecraft client = Minecraft.getInstance();
    private final ClientRegistration registration;
    private final ClientFileSettings<PlatformData.Values> settings;
    private final boolean reopen;
    private final int beforeClient;
    private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    private final List<Component> links = new ArrayList<>();
    private final List<SettingsCommands.Reply> replies = new ArrayList<>();
    private final JsonArray checks = new JsonArray();
    private final JsonArray heldStops = new JsonArray();
    private PlatformHeldStopChecks heldStop;
    private ClientRegistration.Subscription subscription;
    private ServerSettings.Document<PlatformData.Values> first;
    private ClientPacketListener firstConnection;
    private BooleanSupplier firstSource;
    private net.minecraft.client.player.LocalPlayer firstPlayer;
    private SettingsScreen<?> form;
    private DocumentActions<?> worldActions;
    private CompletableFuture<?> pending;
    private int stage, ticks, announced = -1;
    private volatile boolean wrongFeedbackThread;
    private int replacementPlayerId;

    private Check(
        ClientRegistration registration,
        ClientFileSettings<PlatformData.Values> settings,
        boolean reopen) {
      this.registration = registration;
      this.settings = settings;
      this.reopen = reopen;
      beforeClient = settings.runtime().orElseThrow().view().value().orElseThrow().distance();
    }

    private void check(String name, boolean value) {
      if (!value) throw new AssertionError(name);
      checks.add(name);
    }

    private void reply(SettingsCommands.Reply reply) {
      if (!client.isSameThread()) {
        wrongFeedbackThread = true;
        return;
      }
      if (replies.size() >= 32) throw new AssertionError("Unbounded command fixture feedback");
      replies.add(reply);
    }

    private SettingsCommands.Reply completed() {
      return replies.stream()
          .filter(reply -> reply.kind() == SettingsCommands.Kind.COMPLETED)
          .findFirst()
          .orElse(null);
    }

    private String completedDraftDistance() {
      var reply = completed();
      if (reply != null
          && reply.operation().isPresent()
          && reply.operation().orElseThrow().result().orElse(null)
              instanceof EditorActor.View<?> view)
        return Integer.toString(
            ((PlatformData.Values)
                    view.snapshot().orElseThrow().inspect().candidate().orElseThrow().value())
                .distance());
      return "none";
    }

    private void typed(String command) {
      replies.clear();
      NativeWorldProbe.typed(command);
    }

    private void serverLink(String command) {
      links.clear();
      replies.clear();
      var server = client.getSingleplayerServer();
      var player = client.player.getUUID();
      server.execute(
          () ->
              server
                  .getPlayerList()
                  .getPlayer(player)
                  .sendSystemMessage(NativeWorldProbe.link(command)));
    }

    private boolean joined() {
      return client.player != null
          && client.level != null
          && NativeScreens.current(client) == null
          && current != null
          && current.initialization().orElseThrow().toCompletableFuture().isDone();
    }

    private void tick() {
      try {
        if (Files.exists(Path.of("platform-cancel")))
          throw new AssertionError("Native world fixture cancelled");
        if (++ticks > 2400)
          throw new AssertionError("Native world fixture timed out at stage " + stage);
        if (wrongFeedbackThread)
          throw new AssertionError("Command feedback left client owner thread");
        if (stage != announced) {
          announced = stage;
          System.out.println("REFORM_WORLD_STAGE " + stage);
        }
        switch (stage) {
          case 0 -> {
            NativeWorldProbe.start();
            stage++;
          }
          case 1 -> {
            if (!joined()) return;
            check(
                "native world uses actual memory connection",
                client.getSingleplayerServer() != null
                    && client.getConnection().getConnection().isMemoryConnection());
            first = current;
            firstConnection = client.getConnection();
            firstPlayer = client.player;
            check(
                "native world initializes selected saved/default policy",
                first.runtime().orElseThrow().view().value().orElseThrow().distance()
                    == (reopen ? 11 : 7));
            serverLink("/platformclient set distance 22");
            stage++;
          }
          case 2 -> {
            if (links.isEmpty()) return;
            NativeWorldProbe.click(links.getFirst().getStyle());
            stage++;
          }
          case 3 -> {
            if (replies.stream()
                .noneMatch(
                    reply ->
                        reply.kind() == SettingsCommands.Kind.ERROR
                            || reply.kind() == SettingsCommands.Kind.COMPLETED)) return;
            check(
                "actual server-issued click refuses local draft access: "
                    + replies.stream()
                        .map(reply -> reply.kind() + ":" + reply.message().getString())
                        .toList()
                    + "; staged distance="
                    + completedDraftDistance(),
                replies.stream()
                    .anyMatch(
                        reply ->
                            reply.kind() == SettingsCommands.Kind.ERROR
                                && reply
                                    .message()
                                    .getString()
                                    .contains("Server-provided commands")));
            check(
                "server click refuses before operation admission",
                replies.stream()
                    .noneMatch(
                        reply ->
                            reply.kind() == SettingsCommands.Kind.QUEUED
                                || reply.kind() == SettingsCommands.Kind.COMPLETED));
            check(
                "refused server link leaves local effective data unchanged",
                settings.runtime().orElseThrow().view().value().orElseThrow().distance()
                    == beforeClient);
            typed("/platformclient set distance 12");
            stage++;
          }
          case 4 -> {
            if (completed() == null) return;
            var view =
                (EditorActor.View<?>) completed().operation().orElseThrow().result().orElseThrow();
            var value =
                (PlatformData.Values)
                    view.snapshot().orElseThrow().inspect().candidate().orElseThrow().value();
            check("native typed chat edits exact local draft", value.distance() == 12);
            check(
                "typed edit does not publish implicitly",
                settings.runtime().orElseThrow().view().value().orElseThrow().distance()
                    == beforeClient);
            typed("/platformclient save");
            stage++;
          }
          case 5 -> {
            if (completed() == null) return;
            var committed =
                (EditorActor.Commit<?>)
                    completed().operation().orElseThrow().result().orElseThrow();
            check(
                "native typed save has concrete saved receipt",
                committed.receipt().orElseThrow().persistence()
                    == (reopen
                        ? CommitReceipt.Persistence.UNCHANGED
                        : CommitReceipt.Persistence.SAVED));
            check(
                "late typed feedback and runtime remain usable outside chat frame",
                settings.runtime().orElseThrow().view().value().orElseThrow().distance() == 12);
            check(
                "captured native source is current in its actual session",
                firstSource != null && firstSource.getAsBoolean());
            check(
                "native host has actual policy permission",
                NativeScreens.clientPermission(client.player, 2));
            NativeScreens.open(
                client,
                IntegratedSettings.screen(
                    null, Component.literal("Current world policy"), definition.id()));
            stage++;
          }
          case 6 -> {
            if (!(NativeScreens.current(client) instanceof SettingsScreen<?> screen)
                || screen.editorView().availability() != EditorActor.Availability.READY
                || screen.editorView().snapshot().isEmpty()) return;
            form = screen;
            worldActions = form.editingActions();
            pending =
                DocumentActions.result(form.editingActions().edit(EntryPoint.GUI, "distance", "11"))
                    .toCompletableFuture();
            stage++;
          }
          case 7 -> {
            if (!pending.isDone()) return;
            pending.join();
            pending =
                DocumentActions.result(form.editingActions().commit(EntryPoint.GUI))
                    .toCompletableFuture();
            stage++;
          }
          case 8 -> {
            if (!pending.isDone()) return;
            var receipt = ((EditorActor.Commit<?>) pending.join()).receipt().orElseThrow();
            check(
                "integrated native policy saves through actual world owner",
                receipt.persistence()
                        == (reopen
                            ? CommitReceipt.Persistence.UNCHANGED
                            : CommitReceipt.Persistence.SAVED)
                    && first.runtime().orElseThrow().view().value().orElseThrow().distance() == 11);
            var player = client.player.getUUID();
            var server = currentServer;
            pending =
                PlatformHeldPolicyChecks.run(server, player)
                    .thenComposeAsync(
                        held ->
                            PlatformHeldPolicyChecks.replacement(server, player)
                                .thenApplyAsync(
                                    replaced -> {
                                      var combined = new ArrayList<String>(held);
                                      combined.addAll(replaced);
                                      replacementPlayerId =
                                          server.getPlayerList().getPlayer(player).getId();
                                      return List.copyOf(combined);
                                    },
                                    server),
                        client)
                    .toCompletableFuture();
            stage++;
          }
          case 9 -> {
            if (!pending.isDone()) return;
            var observations = (List<?>) pending.join();
            if (client.player == firstPlayer
                || client.player == null
                || client.player.getId() != replacementPlayerId) return;
            for (var observation : observations) checks.add((String) observation);
            check("held replacement reaches a new native client player", true);
            var player = client.player.getUUID();
            var server = currentServer;
            pending =
                PlatformPlayerSearchChecks.run(registration, server, player)
                    .thenCompose(
                        playerSearch ->
                            PlatformRecipientChecks.run(server, first, player)
                                .thenApply(
                                    recipient -> {
                                      var combined = new ArrayList<String>(playerSearch);
                                      combined.addAll(recipient);
                                      return List.copyOf(combined);
                                    }))
                    .toCompletableFuture();
            stage = 16;
          }
          case 16 -> {
            if (!pending.isDone()) return;
            for (var observation : (List<?>) pending.join()) checks.add((String) observation);
            check("native respawn replaces actual client player", client.player != firstPlayer);
            check(
                "native respawn preserves client connection",
                client.getConnection() == firstConnection);
            pending =
                PlatformHeldStopChecks.prepare(
                        currentServer,
                        serverRegistration,
                        client.player.getUUID(),
                        PlatformHeldPolicyChecks.Phase.PREPARE,
                        reopen)
                    .toCompletableFuture();
            stage = 14;
          }
          case 10 -> {
            if (!stopped || client.player != null || client.getSingleplayerServer() != null) return;
            if (!heldStop.settled()) return;
            heldStops.add(heldStop.verify());
            check(
                "stopped native server retires search recipient",
                PlatformRecipientChecks.stopped());
            check(
                "native disconnect retires world editor and scope",
                !worldActions.isOpen() && !first.context().isOpen());
            NativeWorldProbe.start();
            stage++;
          }
          case 11 -> {
            if (!joined()) return;
            check(
                "reconnect creates actual fresh native connection",
                client.getConnection() != firstConnection);
            check(
                "previous native source cannot follow reconnect through mutable getters",
                !firstSource.getAsBoolean());
            check(
                "reconnect creates fresh owner context",
                current != first && !current.context().id().equals(first.context().id()));
            check(
                "reconnect restores exact persisted world policy",
                current.runtime().orElseThrow().view().value().orElseThrow().distance() == 11);
            typed("/platformclient get distance");
            stage++;
          }
          case 12 -> {
            if (completed() == null) return;
            check(
                "new native connection admits local client command",
                completed().level() != SettingsCommands.Level.ERROR);
            pending =
                PlatformHeldStopChecks.prepare(
                        currentServer,
                        serverRegistration,
                        client.player.getUUID(),
                        PlatformHeldPolicyChecks.Phase.RETURN,
                        reopen)
                    .toCompletableFuture();
            stage = 15;
          }
          case 13 -> {
            if (!stopped || client.player != null || client.getSingleplayerServer() != null) return;
            if (!heldStop.settled()) return;
            heldStops.add(heldStop.verify());
            check("second native session closes its own context", !current.context().isOpen());
            var report = new JsonObject();
            report.addProperty("status", "PASS");
            report.addProperty("ticks", ticks);
            report.add("checks", checks);
            report.add("heldStops", heldStops);
            subscription.close();
            result.complete(report);
          }
          case 14, 15 -> {
            if (!pending.isDone()) return;
            heldStop = (PlatformHeldStopChecks) pending.join();
            NativeWorldProbe.leave();
            stage = stage == 14 ? 10 : 13;
          }
          default -> throw new AssertionError("Unknown native world stage");
        }
      } catch (Exception | AssertionError failed) {
        subscription.close();
        result.completeExceptionally(failed);
      }
    }
  }

  private PlatformWorldChecks() {}
}
