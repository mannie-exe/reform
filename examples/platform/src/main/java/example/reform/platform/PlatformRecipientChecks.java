package example.reform.platform;

import design.inherent.reform.minecraft.command.NativeCommandSupport;
import design.inherent.reform.minecraft.command.SearchRecipient;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Actual native permission changes and player replacement; no synthetic source identity. */
final class PlatformRecipientChecks {
  private static Run active;
  private static volatile boolean stopped;

  static CompletionStage<List<String>> run(
      MinecraftServer server,
      ServerSettings.Document<PlatformData.Values> document,
      UUID playerId) {
    var run = new Run(server, document, playerId);
    active = run;
    stopped = false;
    server.execute(() -> run.guard(run::begin));
    return run.result.minimalCompletionStage();
  }

  static void serverStopped(MinecraftServer server) {
    if (active == null || active.server != server) return;
    if (active.recipient == null || active.recipient.current().isPresent())
      throw new AssertionError("Stopped native server retained a search recipient");
    stopped = true;
    active = null;
  }

  static boolean stopped() {
    return stopped;
  }

  private static final class Run {
    private final MinecraftServer server;
    private final ServerSettings.Document<PlatformData.Values> document;
    private final UUID playerId;
    private final ArrayList<String> checks = new ArrayList<>();
    private final CompletableFuture<List<String>> result = new CompletableFuture<>();
    private SearchRecipient recipient;
    private ServerSettings.Editor<PlatformData.Values> editor, replacementEditor;
    private Runnable restorePermission;

    private Run(
        MinecraftServer server,
        ServerSettings.Document<PlatformData.Values> document,
        UUID playerId) {
      this.server = server;
      this.document = document;
      this.playerId = playerId;
    }

    private void check(String name, boolean passed) {
      if (!passed) throw new AssertionError(name);
      checks.add(name);
    }

    private void guard(Runnable action) {
      try {
        if (!server.isSameThread()) throw new AssertionError("Recipient check left native owner");
        action.run();
      } catch (Exception | AssertionError failed) {
        if (restorePermission != null) restorePermission.run();
        if (editor != null) editor.close();
        if (replacementEditor != null) replacementEditor.close();
        result.completeExceptionally(failed);
      }
    }

    private void begin() {
      var player = server.getPlayerList().getPlayer(playerId);
      var source = player.createCommandSourceStack();
      check(
          "native host begins with real command permission",
          NativeCommandSupport.allowed(source, 2));
      recipient = SearchRecipient.capture(source, 2);
      editor = document.editor(source);
      check(
          "native recipient initially resolves exact player",
          recipient.current().orElseThrow().getPlayer() == player);
      restorePermission = NativeWorldProbe.revokePermission(player);
      check(
          "native permission actually changes",
          !NativeCommandSupport.allowed(player.createCommandSourceStack(), 2));
      check("retained recipient refuses revoked authority", recipient.current().isEmpty());
      denied(editor, "retained editor refuses revoked authority", () -> replace(player));
    }

    private void denied(
        ServerSettings.Editor<PlatformData.Values> target, String name, Runnable after) {
      final OperationHandle<?> operation;
      try {
        operation = target.actions().describe(EntryPoint.INTERNAL);
      } catch (PolicyRefusal refused) {
        check(
            name,
            refused.report().issues().stream()
                .anyMatch(issue -> issue.code().equals("owner.permission")));
        after.run();
        return;
      }
      operation
          .completion()
          .whenComplete(
              (outcome, failed) ->
                  server.execute(
                      () ->
                          guard(
                              () -> {
                                if (failed != null)
                                  throw new AssertionError("Missing structured refusal", failed);
                                check(
                                    name,
                                    outcome.result().isEmpty()
                                        && outcome.report().issues().stream()
                                            .anyMatch(
                                                issue -> issue.code().equals("owner.permission")));
                                after.run();
                              })));
    }

    private void replace(ServerPlayer original) {
      restorePermission.run();
      restorePermission = null;
      check(
          "restored permission re-admits current recipient",
          recipient.current().orElseThrow().getPlayer() == original);
      var source = original.createCommandSourceStack();
      var connection = original.connection;
      var replacement = server.getPlayerList().respawn(original, true, Entity.RemovalReason.KILLED);
      replacement.connection.player = replacement;
      check("native respawn replaces actual server player", replacement != original);
      check("native respawn retains actual play connection", replacement.connection == connection);
      check(
          "recipient resolves actual same-connection replacement",
          recipient.current().orElseThrow().getPlayer() == replacement);
      check("old player source cannot own replacement recipient", !recipient.owns(source));
      denied(
          editor,
          "old player editor refuses after native respawn",
          () -> {
            editor.close();
            editor = null;
            replacementEditor = document.editor(replacement.createCommandSourceStack());
            replacementEditor
                .actions()
                .describe(EntryPoint.INTERNAL)
                .completion()
                .whenComplete(
                    (outcome, failed) ->
                        server.execute(
                            () ->
                                guard(
                                    () -> {
                                      if (failed != null)
                                        throw new AssertionError(
                                            "New native editor failed", failed);
                                      check(
                                          "replacement player opens a usable native editor",
                                          outcome.result().isPresent() && outcome.report().valid());
                                      replacementEditor.close();
                                      replacementEditor = null;
                                      result.complete(List.copyOf(checks));
                                    })));
          });
    }
  }

  private PlatformRecipientChecks() {}
}
