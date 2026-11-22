package example.reform.platform;

import com.google.gson.JsonParser;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.minecraft.command.NativeCommandSupport;
import design.inherent.reform.minecraft.command.SearchRecipient;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.minecraft.configuration.ServerRegistration;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.storage.TextStore;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

/** Bounded native authority changes before admission and during an admitted file write. */
final class PlatformHeldPolicyChecks {
  private enum Change {
    PERMISSION,
    REPLACEMENT
  }

  enum Phase {
    PREPARE,
    WRITE,
    RETURN
  }

  static final class Gate {
    private final Phase phase;
    final CompletableFuture<Void> entered = new CompletableFuture<>();
    final CountDownLatch release = new CountDownLatch(1);

    private Gate(Phase phase) {
      this.phase = phase;
    }
  }

  static final class Store extends TextStore<PlatformData.Values> {
    private final MinecraftServer server;
    private final AtomicReference<Gate> armed = new AtomicReference<>();
    private final AtomicReference<Gate> running = new AtomicReference<>();
    int writes;
    volatile Write<PlatformData.Values> completedWrite;

    private Store(MinecraftServer server) {
      this(server, "held_policy");
    }

    Store(MinecraftServer server, String name) {
      super(
          PlatformData.definition(name),
          new OwnedFile(
              StorageBinding.relative(
                  server.getWorldPath(LevelResource.ROOT).toAbsolutePath(),
                  "serverconfig",
                  name + ".json"),
              262144),
          new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
          DraftLimits.CONFIGURATION,
          "native-fixture-json",
          "Explicit owned bare JSON fixture");
      this.server = server;
    }

    Gate arm(Phase phase) {
      var gate = new Gate(phase);
      if (!armed.compareAndSet(null, gate)) throw new AssertionError("Source gate already armed");
      return gate;
    }

    private void hold(Phase phase) {
      var gate = armed.get();
      if (gate == null || gate.phase != phase || !armed.compareAndSet(gate, null)) return;
      if (server.isSameThread())
        throw new AssertionError("File preparation/write used native owner thread");
      running.set(gate);
      gate.entered.complete(null);
      try {
        if (!gate.release.await(10, TimeUnit.SECONDS))
          throw new AssertionError("Native gate was not released");
      } catch (InterruptedException failed) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Native gate interrupted", failed);
      } finally {
        running.compareAndSet(gate, null);
      }
    }

    void release() {
      var pending = armed.getAndSet(null);
      if (pending != null) pending.release.countDown();
      pending = running.get();
      if (pending != null) pending.release.countDown();
    }

    @Override
    public PreparedWrite<PlatformData.Values> prepareWrite(
        Read<PlatformData.Values> baseline, DraftTree draft) {
      var prepared = super.prepareWrite(baseline, draft);
      hold(Phase.PREPARE);
      return prepared;
    }

    @Override
    public Write<PlatformData.Values> write(
        PreparedWrite<PlatformData.Values> prepared, Runnable lifetime) {
      hold(Phase.WRITE);
      writes++;
      var written = super.write(prepared, lifetime);
      completedWrite = written;
      hold(Phase.RETURN);
      return written;
    }

    @Override
    protected DraftTree decode(String text) {
      try {
        return DraftTree.fromParsed(
            definition.type(), JsonInput.read(text, jsonLimits), SourcePath.ROOT, draftLimits);
      } catch (java.io.IOException failed) {
        throw new java.io.UncheckedIOException(failed);
      }
    }

    @Override
    protected String encode(
        Read<PlatformData.Values> baseline, Candidate<PlatformData.Values> candidate) {
      return candidate.authoredJson() + "\n";
    }

    @Override
    protected void validateEncoded(String text) throws java.io.IOException {
      JsonInput.read(text, jsonLimits);
    }
  }

  private static MinecraftServer currentServer;
  private static Store store;
  private static ServerSettings.Document<PlatformData.Values> document;

  static void start(MinecraftServer server, ServerRegistration registration) {
    currentServer = server;
    store = new Store(server);
    document = ServerSettings.forServer(server).serverPolicy(registration, store);
  }

  static void stopped(MinecraftServer server) {
    if (currentServer == server) {
      store.release();
      currentServer = null;
      store = null;
      document = null;
    }
  }

  static CompletionStage<List<String>> run(MinecraftServer server, UUID player) {
    return run(server, player, Change.PERMISSION);
  }

  static CompletionStage<List<String>> replacement(MinecraftServer server, UUID player) {
    return run(server, player, Change.REPLACEMENT);
  }

  private static CompletionStage<List<String>> run(
      MinecraftServer server, UUID player, Change change) {
    return server.submit(() -> new Run(server, player, change).start()).thenCompose(stage -> stage);
  }

  private static final class Run {
    private record Delivery(boolean allowed, OperationSnapshot<?> operation) {}

    private final MinecraftServer server;
    private ServerPlayer player;
    private final Change change;
    private final Store source;
    private final ServerSettings.Document<PlatformData.Values> policy;
    private final List<String> checks = new ArrayList<>();
    private ServerSettings.Editor<PlatformData.Values> editor;
    private Runnable restore;
    private String original;

    private Run(MinecraftServer server, UUID player, Change change) {
      if (currentServer != server) throw new AssertionError("Wrong held native owner");
      this.server = server;
      this.player = server.getPlayerList().getPlayer(player);
      this.change = change;
      source = store;
      policy = document;
    }

    private void check(String text, boolean passed) {
      if (!server.isSameThread()) throw new AssertionError("Held policy check left native owner");
      if (!passed) throw new AssertionError(text);
      checks.add(text);
    }

    private int saved() {
      try {
        return JsonParser.parseString(Files.readString(source.binding().path()))
            .getAsJsonObject()
            .get("distance")
            .getAsInt();
      } catch (java.io.IOException failed) {
        throw new java.io.UncheckedIOException(failed);
      }
    }

    private int effective() {
      return policy.runtime().orElseThrow().view().value().orElseThrow().distance();
    }

    private CompletionStage<List<String>> start() {
      return policy
          .initialization()
          .orElseThrow()
          .thenComposeAsync(
              ignored -> {
                try {
                  original = Files.readString(source.binding().path());
                } catch (java.io.IOException failed) {
                  throw new java.io.UncheckedIOException(failed);
                }
                check(
                    "held native fixture starts from saved/effective baseline",
                    saved() == 7 && effective() == 7);
                return one(Phase.PREPARE);
              },
              server)
          .thenComposeAsync(ignored -> one(Phase.WRITE), server)
          .thenComposeAsync(ignored -> recover(), server)
          .thenApplyAsync(ignored -> List.copyOf(checks), server)
          .whenCompleteAsync(
              (value, failed) -> {
                source.release();
                if (restore != null) {
                  restore.run();
                  restore = null;
                }
                if (editor != null) editor.close();
              },
              server);
    }

    private CompletionStage<Void> one(Phase phase) {
      if (editor != null) editor.close();
      editor = policy.editor(player.createCommandSourceStack());
      var actions = editor.actions();
      return DocumentActions.result(actions.ensureLoaded(EntryPoint.INTERNAL))
          .thenComposeAsync(
              ignored -> DocumentActions.result(actions.edit(EntryPoint.INTERNAL, "distance", "9")),
              server)
          .thenComposeAsync(
              ignored -> {
                int writes = source.writes;
                var gate = source.arm(phase);
                var recipient = SearchRecipient.capture(player.createCommandSourceStack(), 2);
                var target = new SettingsCommands.Target<>(policy.context(), actions);
                var queued = new AtomicReference<UUID>();
                var replies = new ArrayList<SettingsCommands.Reply>();
                var delivery = new CompletableFuture<Delivery>();
                var command =
                    "platformheld"
                        + change.name().toLowerCase(java.util.Locale.ROOT)
                        + phase.name().toLowerCase(java.util.Locale.ROOT);
                server
                    .getCommands()
                    .getDispatcher()
                    .register(
                        SettingsCommands.branch(
                            command,
                            policy.definition().id(),
                            new SettingsCommands.Binding<
                                CommandSourceStack, PlatformData.Values>() {
                              @Override
                              public boolean available(CommandSourceStack actor) {
                                boolean allowed = actions.isOpen() && recipient.owns(actor);
                                // Observe the real bridge's terminal delivery guard without
                                // changing it.
                                if (queued.get() != null)
                                  actions.operations().stream()
                                      .filter(
                                          snapshot ->
                                              snapshot.id().equals(queued.get())
                                                  && snapshot.terminal())
                                      .findFirst()
                                      .ifPresent(
                                          snapshot ->
                                              delivery.complete(new Delivery(allowed, snapshot)));
                                return allowed;
                              }

                              @Override
                              public SettingsCommands.Target<PlatformData.Values> target(
                                  CommandSourceStack actor) {
                                return target;
                              }

                              @Override
                              public Optional<SettingsCommands.Target<PlatformData.Values>>
                                  existing(CommandSourceStack actor) {
                                return available(actor) ? Optional.of(target) : Optional.empty();
                              }

                              @Override
                              public void reply(
                                  CommandSourceStack actor, SettingsCommands.Reply reply) {
                                if (replies.size() >= 4)
                                  throw new AssertionError("Unbounded held command feedback");
                                replies.add(reply);
                                if (reply.kind() == SettingsCommands.Kind.QUEUED)
                                  queued.set(reply.operationId().orElseThrow());
                                actor.sendSuccess(reply::message, false);
                              }
                            },
                            8192));
                try {
                  check(
                      "native Brigadier admits held save at " + phase,
                      server
                              .getCommands()
                              .getDispatcher()
                              .execute(command + " save", player.createCommandSourceStack())
                          == 1);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failed) {
                  throw new AssertionError("Held native command was not registered", failed);
                }
                return gate.entered
                    .orTimeout(10, TimeUnit.SECONDS)
                    .thenComposeAsync(
                        entered -> {
                          try {
                            if (change == Change.PERMISSION) {
                              restore = NativeWorldProbe.revokePermission(player);
                              check(
                                  "actual native permission is revoked during " + phase,
                                  !NativeCommandSupport.allowed(
                                      player.createCommandSourceStack(), 2));
                            } else {
                              var previous = player;
                              var previousSource = previous.createCommandSourceStack();
                              player =
                                  server
                                      .getPlayerList()
                                      .respawn(previous, true, Entity.RemovalReason.KILLED);
                              // Native handleClientCommand updates the listener after PlayerList
                              // returns. Calling only PlayerList leaves packet commands on the old
                              // player even though the list and client have their replacements.
                              player.connection.player = player;
                              check(
                                  "held operation sees actual same-connection player replacement at "
                                      + phase,
                                  player != previous
                                      && player.connection == previous.connection
                                      && player.connection.player == player);
                              check(
                                  "replacement retains rights but old source cannot own it at "
                                      + phase,
                                  NativeCommandSupport.allowed(player.createCommandSourceStack(), 2)
                                      && recipient.current().orElseThrow().getPlayer() == player
                                      && !recipient.owns(previousSource));
                            }
                          } finally {
                            gate.release.countDown();
                          }
                          return delivery.orTimeout(10, TimeUnit.SECONDS);
                        },
                        server)
                    .thenApplyAsync(
                        outcome -> {
                          check(
                              "held native command checks current authority and suppresses late disclosure at "
                                  + phase,
                              !outcome.allowed()
                                  && outcome.operation().terminal()
                                  && replies.size() == 1
                                  && replies.getFirst().kind() == SettingsCommands.Kind.QUEUED);
                          if (phase == Phase.PREPARE) {
                            check(
                                "authority lost before submission prevents physical write: "
                                    + change,
                                source.writes == writes && saved() == 7 && effective() == 7);
                          } else {
                            var receipt = actions.view().lastReceipt().orElseThrow();
                            check(
                                "admitted write retains truthful saved/refused-application receipt",
                                receipt.persistence() == CommitReceipt.Persistence.SAVED
                                    && receipt.application() == CommitReceipt.Application.REFUSED);
                            check(
                                "stale actor receipt contains no private saved observation: "
                                    + change,
                                receipt.observation().isEmpty());
                            check(
                                "admitted write preserves saved/effective distinction",
                                source.writes == writes + 1 && saved() == 9 && effective() == 7);
                          }
                          if (restore != null) {
                            restore.run();
                            restore = null;
                          }
                          return (Void) null;
                        },
                        // Queue from another thread after the native delivery guard returns.
                        CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS, server));
              },
              server);
    }

    private CompletionStage<Void> recover() {
      editor.close();
      editor = policy.editor(player.createCommandSourceStack());
      var actions = editor.actions();
      return DocumentActions.result(actions.ensureLoaded(EntryPoint.INTERNAL))
          .thenComposeAsync(
              ignored -> DocumentActions.result(actions.applySaved(EntryPoint.INTERNAL)), server)
          .thenComposeAsync(
              applied -> {
                var receipt = applied.receipt().orElseThrow();
                check(
                    "restored native actor explicitly applies saved values without another write",
                    receipt.persistence() == CommitReceipt.Persistence.NOT_ATTEMPTED
                        && receipt.application() == CommitReceipt.Application.APPLIED
                        && saved() == 9
                        && effective() == 9);
                return DocumentActions.result(actions.edit(EntryPoint.INTERNAL, "distance", "7"));
              },
              server)
          .thenComposeAsync(
              ignored -> DocumentActions.result(actions.commit(EntryPoint.INTERNAL)), server)
          .thenApplyAsync(
              restored -> {
                check(
                    "native recovery restores saved/effective fixture baseline",
                    saved() == 7 && effective() == 7);
                try {
                  check(
                      "native recovery restores exact source bytes",
                      Files.readString(source.binding().path()).equals(original));
                } catch (java.io.IOException failed) {
                  throw new java.io.UncheckedIOException(failed);
                }
                return null;
              },
              server);
    }
  }

  private PlatformHeldPolicyChecks() {}
}
