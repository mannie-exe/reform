package example.reformprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentConflicts;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import example.reform.ConflictExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import net.minecraft.Util;
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

/**
 * Fixture-only worker coordinator; all real dispatch, authority and delivery use the native lane.
 */
final class NativeConflictCommandControls {
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 1024, 512, 64, 1024);
  private final Minecraft client = Minecraft.getInstance();
  private final Path directory =
      client.gameDirectory.toPath().toAbsolutePath().resolve("command-conflict-fixture");
  private final Path file = directory.resolve("owner.json");
  private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
  private final CommandSourceStack source =
      new ClientCommandSourceStack(
          CommandSource.NULL,
          Vec3.ZERO,
          Vec2.ZERO,
          0,
          "conflict-probe",
          Component.literal("conflict-probe"),
          null);
  private final JsonArray checks = new JsonArray();
  private boolean readAllowed = true, writeAllowed = true, complete;
  private final OwnerContext context =
      new OwnerContext(
          client::execute,
          Util.ioPool(),
          () -> {
            if (!client.isSameThread())
              throw new AssertionError("Conflict owner escaped native lane");
          },
          OwnerContext.Limits.STANDARD);
  private final OwnerContext.Access access = context.access(() -> readAllowed, () -> writeAllowed);
  private JsonOwner<ConflictExample.Profile> owner;
  private JsonOwner<ConflictExample.Profile>.Session session;
  private SettingsCommands.Target<ConflictExample.Profile> target;
  private CompletableFuture<SettingsCommands.Reply> waiting;
  private int maximum = 8192, targets;

  private void check(String name, boolean valid) {
    if (!client.isSameThread()) throw new AssertionError("Check escaped native executor");
    if (!valid) throw new AssertionError(name);
    checks.add(name);
  }

  private <V> V nativeCall(Callable<V> action) throws Exception {
    if (client.isSameThread()) throw new AssertionError("Fixture coordinator blocked native lane");
    return context.onOwner(action).toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private void verify(String name, Callable<Boolean> condition) throws Exception {
    nativeCall(
        () -> {
          check(name, condition.call());
          return null;
        });
  }

  private SettingsCommands.Reply command(String text, int accepted) throws Exception {
    var stage =
        nativeCall(
            () -> {
              waiting = new CompletableFuture<>();
              var result = waiting;
              try {
                check("native dispatch " + text, dispatcher.execute(text, source) == accepted);
              } catch (Throwable failed) {
                result.completeExceptionally(failed);
              }
              return result;
            });
    return stage.get(10, TimeUnit.SECONDS);
  }

  private SettingsCommands.Reply command(String text) throws Exception {
    return command(text, 1);
  }

  private Object result(SettingsCommands.Reply reply) throws Exception {
    return nativeCall(
        () -> {
          var snapshot = reply.operation().orElseThrow();
          check(
              "completed actual conflict command",
              snapshot.state() == OperationSnapshot.State.COMPLETED
                  && snapshot.target().orElseThrow().equals(ConflictExample.DEFINITION.id()));
          return snapshot.result().orElseThrow();
        });
  }

  private String raw(String field) {
    return ((DraftNode.Scalar)
            target
                .actions()
                .view()
                .snapshot()
                .orElseThrow()
                .draft()
                .at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private void write(int minimum, int maximum, String role) throws Exception {
    Files.writeString(
        file,
        "{\"schemaVersion\":1,\"values\":"
            + ConflictExample.DEFINITION
                .encode(new ConflictExample.Profile(minimum, maximum, List.of(role)))
                .getOrThrow()
            + "}");
  }

  private void choose(UUID id, int row, String value) throws Exception {
    var reply = command("conflictprobe conflict choose " + id + " " + row + " " + value);
    verify(
        "selection is local status, not a document operation",
        () -> reply.kind() == SettingsCommands.Kind.STATUS && reply.operation().isEmpty());
  }

  private DocumentConflicts.Preview preview() throws Exception {
    return (DocumentConflicts.Preview) result(command("conflictprobe conflict preview"));
  }

  private void bind() {
    var binding =
        new SettingsCommands.Binding<CommandSourceStack, ConflictExample.Profile>() {
          public boolean available(CommandSourceStack actual) {
            return actual == source && client.isSameThread() && context.isOpen();
          }

          public SettingsCommands.Target<ConflictExample.Profile> target(
              CommandSourceStack actual) {
            targets++;
            if (target == null)
              target =
                  new SettingsCommands.Target<>(
                      context,
                      new DocumentActions<>(
                          context,
                          access,
                          new EditorActor<>(
                              context,
                              owner.open(access),
                              ConflictExample.DEFINITION,
                              JSON,
                              DraftLimits.CONFIGURATION,
                              32),
                          OperationScope.Limits.STANDARD,
                          JSON.maximumBytes()));
            return target;
          }

          public Optional<SettingsCommands.Target<ConflictExample.Profile>> existing(
              CommandSourceStack actual) {
            return available(actual) ? Optional.ofNullable(target) : Optional.empty();
          }

          public void reply(CommandSourceStack actual, SettingsCommands.Reply reply) {
            check(
                "bounded native conflict feedback " + reply.kind(),
                reply.message().getString().length() <= maximum);
            if (reply.kind() != SettingsCommands.Kind.QUEUED) {
              var current = waiting;
              waiting = null;
              if (current == null) throw new AssertionError("Unmatched conflict command feedback");
              current.complete(reply);
            }
          }
        };
    dispatcher.register(
        SettingsCommands.branch("conflictprobe", ConflictExample.DEFINITION.id(), binding, 8192));
    dispatcher.register(
        SettingsCommands.branch("shortconflict", ConflictExample.DEFINITION.id(), binding, 128));
    NeoForge.EVENT_BUS.post(
        new RegisterClientCommandsEvent(
            dispatcher,
            CommandBuildContext.simple(RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS)));
    check(
        "ordinary native event exposes conflict commands",
        dispatcher.getRoot().getChild("reformconflicts").getChild("conflict") != null);
  }

  CompletionStage<JsonObject> run(boolean reopen) {
    return context.onWorker(
        () -> {
          Files.createDirectories(directory);
          var initialization =
              nativeCall(
                  () -> {
                    owner =
                        new JsonOwner<>(
                            context,
                            new JsonStore<>(
                                ConflictExample.DEFINITION,
                                new OwnedFile(
                                    StorageBinding.relative(directory, "owner.json"),
                                    JSON.maximumBytes()),
                                JsonStore.Format.NEW_ENVELOPE,
                                JSON,
                                DraftLimits.CONFIGURATION),
                            8);
                    session = owner.open(access);
                    bind();
                    return session.initialize();
                  });
          initialization.toCompletableFuture().get(10, TimeUnit.SECONDS);
          if (reopen) {
            result(command("conflictprobe get minimum"));
            verify(
                "second native JVM reopens manually reconciled save",
                () ->
                    raw("minimum").equals("9")
                        && raw("maximum").equals("10")
                        && target.actions().view().validation().valid()
                        && !target.actions().view().dirty());
          } else controls();
          return nativeCall(
              () -> {
                complete = true;
                return report();
              });
        });
  }

  private void controls() throws Exception {
    var suggestions =
        nativeCall(
            () ->
                dispatcher.getCompletionSuggestions(
                    dispatcher.parse("conflictprobe conflict ", source)));
    var values = suggestions.get(10, TimeUnit.SECONDS).getList();
    verify(
        "native suggestions do not create editor or review",
        () ->
            targets == 0
                && target == null
                && values.stream().anyMatch(value -> value.getText().equals("choose")));
    nativeCall(
        () -> {
          for (String text :
              List.of(
                  "conflictprobe conflict review no-uuid",
                  "conflictprobe conflict choose " + UUID.randomUUID() + " 0 draft")) {
            try {
              dispatcher.execute(text, source);
              throw new AssertionError("Malformed command accepted");
            } catch (CommandSyntaxException expected) {
              checks.add("native malformed review/row refused");
            }
          }
          return null;
        });
    var cold = command("conflictprobe conflict preview");
    verify(
        "cold review never invents a source conflict",
        () -> cold.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED);
    result(command("conflictprobe set minimum 5"));
    write(2, 12, "remote");
    result(command("conflictprobe reload"));
    var review = preview();
    var id = review.id();
    verify(
        "command preview reports actual whole input",
        () ->
            review.entries().size() == 3
                && review.entries().get(2).wholeValue()
                && target.actions().conflicts().choices(review).isEmpty());
    var described = command("conflictprobe conflict review " + id + " 3");
    verify(
        "native row inspection includes all three alternatives",
        () ->
            described.message().getString().contains("[whole value]")
                && described.message().getString().contains("remote")
                && described.message().getString().contains("base"));
    var incomplete = command("conflictprobe conflict resolve " + id, 0);
    verify(
        "incomplete resolution is a truthful refusal",
        () -> incomplete.kind() == SettingsCommands.Kind.ERROR && raw("minimum").equals("5"));
    choose(id, 1, "draft");
    choose(id, 2, "observed");
    choose(id, 3, "baseline");
    choose(id, 3, "clear");
    verify(
        "clear leaves no implicit collection choice",
        () -> target.actions().conflicts().choices(review).size() == 2);
    nativeCall(
        () -> {
          readAllowed = false;
          return null;
        });
    var denied = command("conflictprobe conflict choose " + id + " 3 observed", 0);
    nativeCall(
        () -> {
          readAllowed = true;
          return null;
        });
    verify(
        "choice checks fresh actual READ",
        () ->
            denied.kind() == SettingsCommands.Kind.ERROR
                && target.actions().conflicts().choices(review).size() == 2);
    var primary = nativeCall(() -> target);
    nativeCall(
        () -> {
          target = null;
          return null;
        });
    var foreign = command("conflictprobe conflict review " + id, 0);
    nativeCall(
        () -> {
          target.actions().close();
          target = primary;
          return null;
        });
    verify(
        "same document and UUID grant no cross-editor review",
        () -> foreign.kind() == SettingsCommands.Kind.ERROR);
    choose(id, 3, "baseline");
    var bytes = Files.readString(file);
    var resolvedReply = command("conflictprobe conflict resolve " + id);
    var resolution = (DocumentConflicts.Resolution<?>) result(resolvedReply);
    verify(
        "command resolution edits draft only and validates it",
        () ->
            resolution.report().valid()
                && raw("minimum").equals("5")
                && raw("maximum").equals("12")
                && !target.actions().view().canUndo());
    if (!Files.readString(file).equals(bytes)) throw new AssertionError("Resolution wrote source");
    var operation = resolvedReply.operation().orElseThrow().id();
    command("conflictprobe conflict status " + operation);
    command("conflictprobe conflict cancel " + operation);
    var wrongScope = command("conflictprobe status " + operation, 0);
    var replay = command("conflictprobe conflict resolve " + id, 0);
    verify(
        "scope lookup and one-use review prevent replay",
        () ->
            wrongScope.kind() == SettingsCommands.Kind.ERROR
                && replay.kind() == SettingsCommands.Kind.ERROR);
    nativeCall(
        () -> {
          writeAllowed = false;
          return null;
        });
    var noWrite = command("conflictprobe save", 0);
    nativeCall(
        () -> {
          writeAllowed = true;
          return null;
        });
    verify(
        "READ resolution does not grant WRITE",
        () -> noWrite.kind() == SettingsCommands.Kind.ERROR);
    var saved = (EditorActor.Commit<?>) result(command("conflictprobe save"));
    verify(
        "separate command Save uses actual owner receipt",
        () ->
            saved.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED
                && saved.receipt().orElseThrow().application()
                    == CommitReceipt.Application.APPLIED);
    result(command("conflictprobe set minimum 6"));
    write(3, 14, "next");
    result(command("conflictprobe reload"));
    var stale = preview();
    Files.writeString(file, "not json");
    result(command("conflictprobe reload"));
    var unreadable = command("conflictprobe conflict choose " + stale.id() + " 1 draft", 0);
    verify(
        "failed actual read disables older native review",
        () -> unreadable.kind() == SettingsCommands.Kind.ERROR && raw("minimum").equals("6"));
    write(7, 10, "safe");
    result(command("conflictprobe set minimum 19"));
    result(command("conflictprobe reload"));
    var invalid = preview();
    choose(invalid.id(), 1, "draft");
    choose(invalid.id(), 2, "observed");
    choose(invalid.id(), 3, "observed");
    var invalidReply = command("conflictprobe conflict resolve " + invalid.id());
    var retained = (DocumentConflicts.Resolution<?>) result(invalidReply);
    verify(
        "native invalid resolution is retained with warning",
        () ->
            !retained.report().valid()
                && invalidReply.level() == SettingsCommands.Level.WARNING
                && raw("minimum").equals("19")
                && raw("maximum").equals("10"));
    var invalidSave = command("conflictprobe save");
    verify(
        "complete validation blocks invalid Save",
        () -> invalidSave.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED);
    result(command("conflictprobe set minimum 9"));
    result(command("conflictprobe save"));
    nativeCall(
        () -> {
          maximum = 128;
          return null;
        });
    command("shortconflict get minimum");
    nativeCall(
        () -> {
          maximum = 8192;
          return null;
        });
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "RUNNING");
    result.add("checks", checks);
    result.addProperty("contextClosed", !context.isOpen());
    return result;
  }

  void shutdown() {
    if (context.isOpen()) context.close();
  }
}
