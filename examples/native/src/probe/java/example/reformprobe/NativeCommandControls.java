package example.reformprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import example.reform.ClientExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Actual native client source/event/Brigadier controls, using a capture sink at the main menu. */
final class NativeCommandControls {
  private final Minecraft client = Minecraft.getInstance();
  private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
  private final CommandSourceStack source =
      new ClientCommandSourceStack(
          CommandSource.NULL,
          Vec3.ZERO,
          Vec2.ZERO,
          0,
          "native-probe",
          Component.literal("native-probe"),
          null);
  private final JsonArray checks = new JsonArray();
  private CompletableFuture<SettingsCommands.Reply> waiting;
  private UUID savedOperation;
  private String original;

  static CompletionStage<JsonObject> run(Path path) {
    return new NativeCommandControls().runControls(path);
  }

  private void check(String name, boolean valid) {
    if (!valid) throw new AssertionError(name);
    checks.add(name);
  }

  private CompletionStage<SettingsCommands.Reply> command(String input) {
    if (!client.isSameThread())
      throw new IllegalStateException("Command must run on native client executor");
    var result = new CompletableFuture<SettingsCommands.Reply>();
    waiting = result;
    try {
      check("accepted " + input, dispatcher.execute(input, source) == 1);
    } catch (Throwable failed) {
      result.completeExceptionally(failed);
    }
    return result.minimalCompletionStage();
  }

  private void reply(SettingsCommands.Reply reply) {
    if (!client.isSameThread()) throw new AssertionError("Feedback escaped native client executor");
    check("bounded " + reply.kind(), reply.message().getString().length() <= 8192);
    if (reply.kind() != SettingsCommands.Kind.QUEUED && waiting != null) {
      var current = waiting;
      waiting = null;
      current.complete(reply);
    }
  }

  private Object result(SettingsCommands.Reply reply) {
    var snapshot = reply.operation().orElseThrow();
    check(
        "completed " + snapshot.operation(), snapshot.state() == OperationSnapshot.State.COMPLETED);
    check(
        "exact document target",
        snapshot.target().orElseThrow().equals(example.reform.NativeExample.DEFINITION.id()));
    return snapshot.result().orElseThrow();
  }

  private CompletionStage<JsonObject> runControls(Path path) {
    try {
      original = Files.readString(path);
      NeoForge.EVENT_BUS.post(
          new RegisterClientCommandsEvent(
              dispatcher,
              CommandBuildContext.simple(RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS)));
      check(
          "ordinary addon registered its native client command event",
          dispatcher.getRoot().getChild("reformprefs") != null);
      dispatcher.register(ClientExample.settings().commandBranch("probe", this::reply));
    } catch (Throwable failed) {
      return CompletableFuture.failedFuture(failed);
    }
    return command("probe describe")
        .thenCompose(
            reply -> {
              check(
                  "public native field description",
                  result(reply) instanceof DocumentActions.Description description
                      && description.fields().size() == 3);
              return command("probe get distance");
            })
        .thenCompose(
            reply -> {
              check(
                  "native command observes GUI-saved source",
                  result(reply) instanceof EditorActor.Inspection<?> observed
                      && ((design.inherent.reform.edit.DraftNode.Scalar)
                              observed
                                  .snapshot()
                                  .draft()
                                  .at(
                                      design.inherent.reform.definition.FieldPath.ROOT.field(
                                          "distance")))
                          .raw()
                          .equals("128"));
              try {
                dispatcher.execute("probe set distance \"unterminated", source);
                throw new AssertionError("Malformed native string was accepted");
              } catch (CommandSyntaxException expected) {
                checks.add("native string parsing rejects malformed input before services");
              }
              return dispatcher.getCompletionSuggestions(dispatcher.parse("probe set d", source));
            })
        .thenCompose(
            suggestions -> {
              check(
                  "declared native field suggestions",
                  suggestions.getList().stream().anyMatch(s -> s.getText().equals("distance"))
                      && suggestions.getList().stream().allMatch(s -> s.getText().startsWith("d")));
              return command("probe set distance -");
            })
        .thenCompose(
            reply -> {
              check(
                  "command keeps invalid raw input",
                  result(reply) instanceof EditorActor.View<?> view && !view.validation().valid());
              return command("probe validate");
            })
        .thenCompose(
            reply -> {
              check(
                  "shared command validation reports invalid draft",
                  result(reply) instanceof EditorActor.Validation validation
                      && !validation.report().valid());
              return command("probe save");
            })
        .thenCompose(
            reply -> {
              check(
                  "invalid command save refused",
                  reply.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED
                      && !reply.operation().orElseThrow().report().valid()
                      && reply.level() == SettingsCommands.Level.ERROR);
              try {
                check("invalid command wrote no source", Files.readString(path).equals(original));
              } catch (java.io.IOException failed) {
                return CompletableFuture.failedFuture(failed);
              }
              return command("probe set distance 192");
            })
        .thenCompose(
            reply -> {
              check(
                  "valid command remains staged",
                  result(reply) instanceof EditorActor.View<?> view && view.dirty());
              return command("probe validate");
            })
        .thenCompose(
            reply -> {
              check(
                  "actual owner validation through native command",
                  result(reply) instanceof EditorActor.Validation validation
                      && validation.report().valid());
              return command("probe compare");
            })
        .thenCompose(
            reply -> {
              check(
                  "typed command comparison",
                  result(reply) instanceof DocumentActions.Comparison comparison
                      && comparison.changes().size() == 1
                      && comparison.authoredChanges());
              return command("probe export");
            })
        .thenCompose(
            reply -> {
              var exported = (DocumentActions.ExportArtifact) result(reply);
              check(
                  "bounded value export has actual content",
                  exported.text().contains("\"distance\":192")
                      && exported.representation().equals("document-value"));
              check(
                  "native explicit clipboard link retains exact value JSON",
                  reply.message().getSiblings().stream()
                      .anyMatch(
                          component ->
                              component.getStyle().getClickEvent() != null
                                  && component.getStyle().getClickEvent().getAction()
                                      == net.minecraft.network.chat.ClickEvent.Action
                                          .COPY_TO_CLIPBOARD
                                  && component
                                      .getStyle()
                                      .getClickEvent()
                                      .getValue()
                                      .equals(exported.text())));
              return command("probe explain");
            })
        .thenCompose(
            reply -> {
              check(
                  "source and effective values remain distinct",
                  result(reply) instanceof EditorActor.Inspection<?> observed
                      && observed.provenance().kind().equals("json")
                      && observed.effective().isPresent()
                      && ((example.reform.NativeExample.Preferences)
                                  observed.effective().orElseThrow().value().orElseThrow())
                              .distance()
                          == 128);
              return command("probe save");
            })
        .thenCompose(
            reply -> {
              var saved = (EditorActor.Save<?>) result(reply);
              var receipt = saved.receipt().orElseThrow();
              check(
                  "native command saves and publishes actual values",
                  receipt.persistence() == CommitReceipt.Persistence.SAVED
                      && receipt.application() == CommitReceipt.Application.APPLIED
                      && ClientExample.settings()
                              .runtime()
                              .orElseThrow()
                              .view()
                              .value()
                              .orElseThrow()
                              .distance()
                          == 192);
              savedOperation = reply.operation().orElseThrow().id();
              return command("probe status " + savedOperation);
            })
        .thenCompose(
            reply -> {
              check(
                  "native status binds exact retained job",
                  reply.kind() == SettingsCommands.Kind.STATUS
                      && reply.operation().orElseThrow().id().equals(savedOperation));
              return command("probe cancel " + savedOperation);
            })
        .thenCompose(
            reply -> {
              check(
                  "cancel does not rewrite completed save",
                  reply.kind() == SettingsCommands.Kind.CANCELLATION
                      && reply.operation().orElseThrow().state()
                          == OperationSnapshot.State.COMPLETED);
              // Test an error directly: rejected invocations return zero, rather than an accepted
              // job.
              var denied = new CompletableFuture<SettingsCommands.Reply>();
              waiting = denied;
              try {
                check(
                    "foreign UUID not admitted",
                    dispatcher.execute("probe status " + UUID.randomUUID(), source) == 0);
              } catch (CommandSyntaxException failed) {
                return CompletableFuture.failedFuture(failed);
              }
              return denied;
            })
        .thenCompose(
            reply -> {
              check(
                  "foreign UUID cannot select another operation",
                  reply.kind() == SettingsCommands.Kind.ERROR && reply.operation().isEmpty());
              return command("probe set distance 128");
            })
        .thenCompose(reply -> command("probe save"))
        .thenApply(
            reply -> {
              result(reply);
              try {
                check(
                    "original GUI baseline restored explicitly",
                    Files.readString(path).equals(original));
              } catch (java.io.IOException failed) {
                throw new java.io.UncheckedIOException(failed);
              }
              var report = new JsonObject();
              report.addProperty("status", "PASS");
              report.add("checks", checks);
              report.addProperty(
                  "source",
                  "actual ClientCommandSourceStack, native event and Brigadier; capture sink at main menu, not chat packet/world recipient");
              return report;
            });
  }
}
