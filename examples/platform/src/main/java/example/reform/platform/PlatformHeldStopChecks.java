package example.reform.platform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.minecraft.command.SearchRecipient;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.minecraft.configuration.ServerRegistration;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.storage.OwnedFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/** Observes actual native shutdown while preparation or a completed physical write is held. */
final class PlatformHeldStopChecks {
  private static PlatformHeldStopChecks active;

  static CompletionStage<PlatformHeldStopChecks> prepare(
      MinecraftServer server,
      ServerRegistration registration,
      UUID player,
      PlatformHeldPolicyChecks.Phase phase,
      boolean reopen) {
    return server
        .submit(
            () -> new PlatformHeldStopChecks(server, registration, player, phase, reopen).start())
        .thenCompose(stage -> stage);
  }

  static void stopped(MinecraftServer server) {
    if (active == null || active.server != server) return;
    if (active.policy.context().isOpen())
      throw new AssertionError("Held source was released before native owner closure");
    active.closedBeforeRelease = true;
    active.source.release();
  }

  private final MinecraftServer server;
  private final CommandSourceStack actor;
  private final PlatformHeldPolicyChecks.Phase phase;
  private final boolean reopen;
  private final PlatformHeldPolicyChecks.Store source;
  private final ServerSettings.Document<PlatformData.Values> policy;
  private final ArrayList<SettingsCommands.Reply> replies = new ArrayList<>();
  private ServerSettings.Editor<PlatformData.Values> editor;
  private OperationHandle<?> operation;
  private PlatformHeldPolicyChecks.Gate gate;
  private volatile boolean closedBeforeRelease;
  private String baseline;
  private int beforeWrites;

  private PlatformHeldStopChecks(
      MinecraftServer server,
      ServerRegistration registration,
      UUID player,
      PlatformHeldPolicyChecks.Phase phase,
      boolean reopen) {
    if (active != null) throw new AssertionError("Earlier held stop control was not settled");
    this.server = server;
    actor = server.getPlayerList().getPlayer(player).createCommandSourceStack();
    this.phase = phase;
    this.reopen = reopen;
    source = new PlatformHeldPolicyChecks.Store(server, "held_stop");
    policy = ServerSettings.forServer(server).serverPolicy(registration, source);
    active = this;
  }

  private int saved() {
    try {
      return JsonParser.parseString(Files.readString(source.binding().path()))
          .getAsJsonObject()
          .get("distance")
          .getAsInt();
    } catch (java.io.IOException failure) {
      throw new java.io.UncheckedIOException(failure);
    }
  }

  private CompletionStage<PlatformHeldStopChecks> start() {
    return policy
        .initialization()
        .orElseThrow()
        .thenComposeAsync(
            initialized -> {
              int expected = phase == PlatformHeldPolicyChecks.Phase.PREPARE && reopen ? 9 : 7;
              if (saved() != expected
                  || policy.runtime().orElseThrow().view().value().orElseThrow().distance()
                      != expected)
                throw new AssertionError("Held-stop saved/effective reopen baseline: " + saved());
              editor = policy.editor(actor);
              return DocumentActions.result(editor.actions().ensureLoaded(EntryPoint.INTERNAL));
            },
            server)
        .thenComposeAsync(
            ignored -> {
              if (saved() == 7) return CompletableFuture.completedStage(null);
              return DocumentActions.result(
                      editor.actions().edit(EntryPoint.INTERNAL, "distance", "7"))
                  .thenComposeAsync(
                      edited ->
                          DocumentActions.result(editor.actions().commit(EntryPoint.INTERNAL)),
                      server);
            },
            server)
        .thenComposeAsync(
            ignored -> {
              try {
                baseline = Files.readString(source.binding().path());
              } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
              }
              if (saved() != 7) throw new AssertionError("Held-stop reset did not save");
              return DocumentActions.result(
                  editor.actions().edit(EntryPoint.INTERNAL, "distance", "9"));
            },
            server)
        .thenComposeAsync(
            ignored -> {
              beforeWrites = source.writes;
              source.completedWrite = null;
              gate = source.arm(phase);
              var actions = editor.actions();
              var recipient = SearchRecipient.capture(actor, 2);
              var target = new SettingsCommands.Target<>(policy.context(), actions);
              var command = "platformheldstop";
              server
                  .getCommands()
                  .getDispatcher()
                  .register(
                      SettingsCommands.branch(
                          command,
                          policy.definition().id(),
                          new SettingsCommands.Binding<CommandSourceStack, PlatformData.Values>() {
                            @Override
                            public boolean available(CommandSourceStack sourceActor) {
                              return policy.context().isOpen()
                                  && actions.isOpen()
                                  && recipient.owns(sourceActor);
                            }

                            @Override
                            public SettingsCommands.Target<PlatformData.Values> target(
                                CommandSourceStack sourceActor) {
                              return target;
                            }

                            @Override
                            public Optional<SettingsCommands.Target<PlatformData.Values>> existing(
                                CommandSourceStack sourceActor) {
                              return available(sourceActor)
                                  ? Optional.of(target)
                                  : Optional.empty();
                            }

                            @Override
                            public void reply(
                                CommandSourceStack sourceActor, SettingsCommands.Reply reply) {
                              if (replies.size() >= 2)
                                throw new AssertionError("Unexpected held-stop disclosure");
                              replies.add(reply);
                              sourceActor.sendSuccess(reply::message, false);
                            }
                          },
                          8192));
              try {
                if (server.getCommands().getDispatcher().execute(command + " save", actor) != 1)
                  throw new AssertionError("Held-stop native command was not admitted");
              } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                throw new AssertionError(failure);
              }
              if (replies.size() != 1 || replies.getFirst().kind() != SettingsCommands.Kind.QUEUED)
                throw new AssertionError("Held-stop queued reply missing");
              var id = replies.getFirst().operationId().orElseThrow();
              operation = actions.operation(id).orElseThrow();
              return gate.entered
                  .orTimeout(10, TimeUnit.SECONDS)
                  .thenApplyAsync(
                      entered -> {
                        if (policy.runtime().orElseThrow().view().value().orElseThrow().distance()
                            != 7)
                          throw new AssertionError("Held storage work published before returning");
                        return this;
                      },
                      server);
            },
            server);
  }

  boolean settled() {
    return operation != null
        && operation.completion().toCompletableFuture().isDone()
        && policy.context().outstandingCalls() == 0;
  }

  JsonObject verify() {
    if (!settled()) throw new AssertionError("Held-stop work was not drained");
    var result = operation.completion().toCompletableFuture().join();
    if (!closedBeforeRelease || policy.context().isOpen() || !result.terminal())
      throw new AssertionError("Held-stop context/operation stayed active");
    if (replies.size() != 1 || replies.getFirst().kind() != SettingsCommands.Kind.QUEUED)
      throw new AssertionError("Stopped native recipient received terminal private feedback");
    boolean physical = phase == PlatformHeldPolicyChecks.Phase.RETURN;
    if (source.writes != beforeWrites + (physical ? 1 : 0) || saved() != (physical ? 9 : 7))
      throw new AssertionError("Held-stop physical persistence disagrees with admission phase");
    if (physical) {
      if (source.completedWrite == null
          || source.completedWrite.persistence().state() != OwnedFile.Persistence.SAVED)
        throw new AssertionError("Physical write has no actual SAVED storage receipt");
    } else {
      try {
        if (!Files.readString(source.binding().path()).equals(baseline))
          throw new AssertionError("Stopped preparation changed source bytes");
      } catch (java.io.IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    }
    if (!(result.result().orElseThrow() instanceof EditorActor.Commit<?> commit)
        || commit.editorReport().valid())
      throw new AssertionError("Stopped editor must retain an unavailable effect result");
    if (physical) {
      var receipt = commit.receipt().orElseThrow();
      if (receipt.persistence() != CommitReceipt.Persistence.SAVED
          || receipt.application() != CommitReceipt.Application.UNAVAILABLE
          || receipt.observation().isPresent())
        throw new AssertionError("Stopped owner lost saved/unavailable/redacted receipt");
    } else if (commit.receipt().isPresent()) {
      throw new AssertionError("Stopped preparation invented a persistence receipt");
    }
    var report = new JsonObject();
    report.addProperty("status", "PASS");
    report.addProperty("phase", phase.name());
    report.addProperty("closedBeforeRelease", closedBeforeRelease);
    report.addProperty("writes", source.writes - beforeWrites);
    report.addProperty("savedDistance", saved());
    report.addProperty("terminalState", result.state().name());
    report.addProperty("terminalResultPresent", result.result().isPresent());
    report.addProperty("editorReportValid", commit.editorReport().valid());
    report.addProperty("receiptPresent", commit.receipt().isPresent());
    commit
        .receipt()
        .ifPresent(
            receipt -> {
              report.addProperty("persistence", receipt.persistence().name());
              report.addProperty("application", receipt.application().name());
              report.addProperty("privateObservationPresent", receipt.observation().isPresent());
            });
    report.addProperty("queuedRepliesOnly", replies.size() == 1);
    report.addProperty("outstandingCalls", policy.context().outstandingCalls());
    active = null;
    return report;
  }
}
