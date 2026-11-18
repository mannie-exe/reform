package example.reformprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.importing.FileImportSource;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.importing.JsonImportFormat;
import design.inherent.reform.neoforge.command.ImportCatalog;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import example.reform.ImportExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Actual native registered-source command dispatch and source/recipient lifetime controls. */
final class NativeImportCommandControls {
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 1024, 512, 64, 1024);
  private static final String VALID =
      "{\"count\":3,\"label\":\"Imported café\",\"roles\":[\"alpha\",\"beta\"],\"note\":[]}";
  private static final String PREVIEW =
      "importprobe import preview example:valid reform:import/value_json";
  private final Minecraft client = Minecraft.getInstance();
  private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
  private final CommandSourceStack source =
      new ClientCommandSourceStack(
          CommandSource.NULL,
          Vec3.ZERO,
          Vec2.ZERO,
          0,
          "import-probe",
          Component.literal("import-probe"),
          null);
  private final JsonArray checks = new JsonArray();
  private final Path directory =
      client.gameDirectory.toPath().toAbsolutePath().resolve("command-import-fixture");
  private boolean readAllowed = true, writeAllowed = true, sourceAllowed = true, available = true;
  private final OwnerContext context =
      new OwnerContext(
          client::execute,
          Util.ioPool(),
          () -> {
            if (!client.isSameThread()) throw new AssertionError("Import owner left native thread");
          },
          OwnerContext.Limits.STANDARD);
  private final OwnerContext.Access access = context.access(() -> readAllowed, () -> writeAllowed);
  private final AtomicInteger captures = new AtomicInteger();
  private final Map<UUID, CompletableFuture<SettingsCommands.Reply>> pending =
      new LinkedHashMap<>();
  private final Map<UUID, CompletableFuture<SettingsCommands.Reply>> cancellations =
      new LinkedHashMap<>();
  private final List<SettingsCommands.Reply> replies = new ArrayList<>();
  private List<ImportSource> sources;
  private ImportCatalog<ImportExample.Profile> catalog;
  private SettingsCommands.Target<ImportExample.Profile> target;
  private JsonOwner<ImportExample.Profile> owner;
  private JsonOwner<ImportExample.Profile>.Session session;
  private CompletableFuture<SettingsCommands.Reply> submitting;
  private UUID submittedId;
  private DocumentImports.Preview preview;
  private OperationHandle<?> heldOperation;
  private Gate gate;
  private int targets, beforeReplies;
  private boolean complete;
  private String original;

  private static final class Gate {
    final CompletableFuture<Void> entered = new CompletableFuture<>();
    final CountDownLatch release = new CountDownLatch(1);
  }

  private void check(String name, boolean valid) {
    if (!client.isSameThread()) throw new AssertionError("Check escaped native executor: " + name);
    if (!valid) throw new AssertionError(name);
    checks.add(name);
  }

  void shutdown() {
    if (gate != null) gate.release.countDown();
    if (context.isOpen()) context.close();
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "RUNNING");
    result.add("checks", checks);
    result.addProperty("capturedSources", captures.get());
    result.addProperty("contextClosed", !context.isOpen());
    return result;
  }

  private ImportCatalog<ImportExample.Profile> catalog() {
    return new ImportCatalog<>(
        ImportExample.DEFINITION,
        sources,
        JsonImportFormat.documentFormats(
            ImportExample.DEFINITION,
            JSON,
            DraftLimits.CONFIGURATION,
            ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE));
  }

  private ImportSource file(String id, String name) {
    var file =
        new FileImportSource(
            DocumentId.parse("example:" + id), directory.resolve(name), JSON.maximumBytes());
    return new ImportSource() {
      public DocumentId id() {
        return file.id();
      }

      public String label() {
        return file.label();
      }

      public int maximumBytes() {
        return file.maximumBytes();
      }

      public void requireCurrent() {
        if (!client.isSameThread()) throw new AssertionError("Source guard escaped native thread");
        if (!sourceAllowed)
          throw new PolicyRefusal("source.permission", "Source permission is required");
      }

      public Capture capture() {
        if (!client.isSameThread())
          throw new AssertionError("Source capture escaped native thread");
        captures.incrementAndGet();
        var reader = file.capture();
        var held = id.equals("held") ? gate : null;
        return () -> {
          if (client.isSameThread()) throw new AssertionError("Source read blocked native thread");
          if (held != null) {
            held.entered.complete(null);
            try {
              if (!held.release.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Held read deadline");
            } catch (InterruptedException failed) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException(failed);
            }
          }
          return reader.read();
        };
      }
    };
  }

  private void bind() {
    dispatcher.register(
        SettingsCommands.branch(
            "importprobe",
            ImportExample.DEFINITION.id(),
            new SettingsCommands.Binding<CommandSourceStack, ImportExample.Profile>() {
              public boolean available(CommandSourceStack actual) {
                return actual == source && available && client.isSameThread() && context.isOpen();
              }

              public SettingsCommands.Target<ImportExample.Profile> target(
                  CommandSourceStack actual) {
                if (!available(actual))
                  throw new PolicyRefusal("owner.permission", "Native permission required");
                targets++;
                if (target == null) {
                  var actor =
                      new EditorActor<>(
                          context,
                          session,
                          ImportExample.DEFINITION,
                          JSON,
                          DraftLimits.CONFIGURATION,
                          32);
                  target =
                      new SettingsCommands.Target<>(
                          context,
                          new DocumentActions<>(
                              context,
                              access,
                              actor,
                              OperationScope.Limits.STANDARD,
                              JSON.maximumBytes()));
                }
                return target;
              }

              public Optional<SettingsCommands.Target<ImportExample.Profile>> existing(
                  CommandSourceStack actual) {
                return available(actual) ? Optional.ofNullable(target) : Optional.empty();
              }

              public Optional<ImportCatalog<ImportExample.Profile>> imports(
                  CommandSourceStack actual) {
                return available(actual) && readAllowed ? Optional.of(catalog) : Optional.empty();
              }

              public void reply(CommandSourceStack actual, SettingsCommands.Reply reply) {
                receive(reply);
              }
            },
            8192));
  }

  private void receive(SettingsCommands.Reply reply) {
    check("native bounded feedback " + reply.kind(), reply.message().getString().length() <= 8192);
    replies.add(reply);
    if (reply.kind() == SettingsCommands.Kind.QUEUED) {
      submittedId = reply.operation().orElseThrow().id();
      pending.put(submittedId, submitting);
    } else if (reply.kind() == SettingsCommands.Kind.COMPLETED) {
      var result = pending.remove(reply.operation().orElseThrow().id());
      if (result == null) throw new AssertionError("Unmatched operation reply");
      result.complete(reply);
    } else if (reply.kind() == SettingsCommands.Kind.CANCELLATION) {
      var result = cancellations.remove(reply.operation().orElseThrow().id());
      if (result == null) throw new AssertionError("Unmatched cancellation reply");
      result.complete(reply);
    } else if (submitting != null) submitting.complete(reply);
    else if (reply.kind() == SettingsCommands.Kind.ERROR && pending.size() == 1) {
      var first = pending.keySet().iterator().next();
      pending.remove(first).complete(reply);
    } else throw new AssertionError("Unmatched native command feedback: " + reply.kind());
  }

  private CompletionStage<SettingsCommands.Reply> command(String text, int expected) {
    if (!client.isSameThread())
      throw new AssertionError("Command dispatch escaped native executor");
    var result = new CompletableFuture<SettingsCommands.Reply>();
    submitting = result;
    submittedId = null;
    try {
      if (text.startsWith("importprobe import cancel "))
        cancellations.put(
            UUID.fromString(text.substring("importprobe import cancel ".length())), result);
      check("native dispatch " + text, dispatcher.execute(text, source) == expected);
    } catch (Throwable failed) {
      result.completeExceptionally(failed);
    } finally {
      submitting = null;
    }
    return result;
  }

  private CompletionStage<SettingsCommands.Reply> command(String text) {
    return command(text, 1);
  }

  private Object completed(SettingsCommands.Reply reply) {
    var operation = reply.operation().orElseThrow();
    check(
        "completed import operation " + operation.operation(),
        operation.state() == OperationSnapshot.State.COMPLETED);
    check(
        "actual import target",
        operation.target().orElseThrow().equals(ImportExample.DEFINITION.id()));
    return operation.result().orElseThrow();
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

  /** Small fixture assertion only, never part of the production command/read path. */
  private boolean sourceUnchanged() {
    try {
      return Files.readString(directory.resolve("owner.json")).equals(original);
    } catch (java.io.IOException failed) {
      throw new java.io.UncheckedIOException(failed);
    }
  }

  private void syntax(String text) {
    try {
      dispatcher.execute(text, source);
      throw new AssertionError("Native malformed input accepted: " + text);
    } catch (CommandSyntaxException expected) {
      check("native syntax refused " + text, true);
    }
  }

  private CompletionStage<Void> coldControls() {
    return command("importprobe import list")
        .thenCompose(
            reply -> {
              check(
                  "catalog listing creates no editor or source capture",
                  targets == 0 && target == null && captures.get() == 0);
              check(
                  "registered Unicode filename is metadata",
                  reply.message().getString().contains("Café source.json"));
              return dispatcher.getCompletionSuggestions(
                  dispatcher.parse("importprobe import preview example:v", source));
            })
        .thenCompose(
            suggestions -> {
              check(
                  "unquoted native source suggestions",
                  suggestions.getList().stream()
                      .map(s -> s.getText())
                      .toList()
                      .equals(List.of("example:valid")));
              return dispatcher.getCompletionSuggestions(
                  dispatcher.parse(
                      "importprobe import preview example:valid reform:import/s", source));
            })
        .thenCompose(
            suggestions -> {
              check(
                  "unquoted native format suggestions",
                  suggestions.getList().stream()
                      .map(s -> s.getText())
                      .toList()
                      .equals(List.of("reform:import/schema_json")));
              check(
                  "suggestions create no editor or source capture",
                  targets == 0 && captures.get() == 0);
              syntax("importprobe import review not-a-uuid");
              syntax("importprobe import preview EXAMPLE:invalid reform:import/value_json");
              return command(
                  "importprobe import preview example:unknown reform:import/value_json", 0);
            })
        .thenCompose(
            reply -> {
              check(
                  "unknown source creates no editor",
                  targets == 0 && target == null && captures.get() == 0);
              return command("importprobe set label \"Prior draft\"");
            })
        .thenCompose(
            reply -> {
              completed(reply);
              return command(PREVIEW);
            })
        .thenCompose(
            reply -> {
              preview = (DocumentImports.Preview) completed(reply);
              check(
                  "preview preserves current draft and separate source version",
                  raw("label").equals("Prior draft") && preview.sourceSchemaVersion().isEmpty());
              check(
                  "native typed review contains source and changes",
                  reply.message().getString().contains("Imported café")
                      && preview.changes().size() == 3);
              check(
                  "copy review link carries exact scoped ticket",
                  reply.message().getSiblings().stream()
                      .anyMatch(
                          c ->
                              c.getStyle().getClickEvent() != null
                                  && c.getStyle()
                                      .getClickEvent()
                                      .getValue()
                                      .equals(preview.id().toString())));
              return command("importprobe import status " + reply.operation().orElseThrow().id());
            })
        .thenCompose(
            reply -> {
              check("import scope status retains preview", completed(reply) == preview);
              return command("importprobe status " + reply.operation().orElseThrow().id(), 0);
            })
        .thenCompose(
            reply -> {
              check(
                  "document scope does not inherit import operations", reply.operation().isEmpty());
              return command("importprobe import preview example:valid example:unknown_format", 0);
            })
        .thenCompose(reply -> command("importprobe import review " + preview.id()))
        .thenCompose(
            reply -> {
              check(
                  "unknown selection preserved valid review",
                  reply.message().getString().contains(preview.id().toString())
                      && reply.operation().isEmpty());
              check(
                  "preview did not persist or publish",
                  owner.handle().view().value().orElseThrow().label().equals("Original")
                      && sourceUnchanged());
              return command("importprobe import stage " + preview.id());
            })
        .thenCompose(
            reply -> {
              check(
                  "explicit stage is a draft effect only",
                  completed(reply) instanceof EditorActor.View<?> view
                      && view.dirty()
                      && raw("label").equals("Imported café")
                      && sourceUnchanged()
                      && reply.message().getString().contains("Save is separate"));
              return command("importprobe import stage " + preview.id(), 0);
            })
        .thenCompose(reply -> command("importprobe undo"))
        .thenCompose(
            reply -> {
              completed(reply);
              check(
                  "import has one shared undo step retaining earlier work",
                  raw("label").equals("Prior draft") && raw("count").equals("1"));
              return command("importprobe redo");
            })
        .thenCompose(reply -> command("importprobe save"))
        .thenAccept(
            reply -> {
              var commit = (EditorActor.Commit<?>) completed(reply);
              check(
                  "only explicit Save persists the imported profile",
                  commit.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED
                      && owner
                          .handle()
                          .view()
                          .value()
                          .orElseThrow()
                          .label()
                          .equals("Imported café"));
            });
  }

  private CompletionStage<Void> refusalControls() {
    return command(PREVIEW)
        .thenCompose(
            reply -> {
              preview = (DocumentImports.Preview) completed(reply);
              return command("importprobe set count 4");
            })
        .thenCompose(reply -> command("importprobe import stage " + preview.id()))
        .thenCompose(
            reply -> {
              check(
                  "later editing refuses old target without replacing it",
                  reply.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED
                      && raw("count").equals("4"));
              return command("importprobe revert");
            })
        .thenCompose(
            reply -> command("importprobe import preview example:repair reform:import/schema_json"))
        .thenCompose(
            reply -> {
              preview = (DocumentImports.Preview) completed(reply);
              check(
                  "repairable invalid draft is explicit and comparison unavailable",
                  preview.stageable()
                      && !preview.importedValidation().valid()
                      && !preview.comparisonAvailable()
                      && reply.level() == SettingsCommands.Level.WARNING
                      && reply.message().getString().contains("comparison=unavailable"));
              return command("importprobe import preview example:wrong reform:import/schema_json");
            })
        .thenCompose(
            reply -> {
              check(
                  "wrong source version refuses",
                  reply.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED);
              return command("importprobe import preview example:broken reform:import/value_json");
            })
        .thenCompose(
            reply -> {
              check(
                  "malformed source refuses without editing",
                  reply.operation().orElseThrow().state() == OperationSnapshot.State.REFUSED
                      && raw("count").equals("3"));
              return command(PREVIEW);
            })
        .thenCompose(
            reply -> {
              preview = (DocumentImports.Preview) completed(reply);
              catalog = catalog();
              return command("importprobe import review " + preview.id(), 0);
            })
        .thenCompose(
            reply -> {
              check(
                  "same-name catalog replacement invalidates review",
                  reply.operation().isEmpty() && raw("count").equals("3"));
              return command(PREVIEW);
            })
        .thenCompose(
            reply -> {
              preview = (DocumentImports.Preview) completed(reply);
              sourceAllowed = false;
              return command("importprobe import stage " + preview.id(), 0);
            })
        .thenCompose(
            reply -> {
              check(
                  "current source permission is required",
                  reply.operation().isEmpty() && raw("count").equals("3"));
              sourceAllowed = true;
              return command("importprobe import status " + UUID.randomUUID(), 0);
            })
        .thenCompose(
            reply -> {
              check("foreign operation UUID grants no authority", reply.operation().isEmpty());
              var unregistered =
                  ImportSource.snapshot(
                      DocumentId.parse("example:valid"),
                      new ImportInput(
                          "Unregistered GUI source",
                          VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          JSON.maximumBytes()));
              var local =
                  target
                      .actions()
                      .imports(
                          unregistered,
                          JsonImportFormat.documentFormats(
                              ImportExample.DEFINITION,
                              JSON,
                              DraftLimits.CONFIGURATION,
                              ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE));
              return local
                  .preview(EntryPoint.GUI, DocumentId.parse("reform:import/value_json"))
                  .completion();
            })
        .thenCompose(
            snapshot -> {
              var selected = snapshot.result().orElseThrow();
              return command("importprobe import review " + selected.id(), 0);
            })
        .thenAccept(
            reply ->
                check(
                    "same-name unregistered GUI source is not command admission",
                    reply.operation().isEmpty()
                        && !reply.message().getString().contains("Unregistered GUI source")));
  }

  private CompletionStage<Void> heldControls() {
    gate = new Gate();
    var result = command("importprobe import preview example:held reform:import/value_json");
    heldOperation =
        target.actions().activeImports().orElseThrow().operation(submittedId).orElseThrow();
    return gate.entered
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      check(
                          "pending read leaves native thread responsive",
                          !result.toCompletableFuture().isDone());
                      return null;
                    }))
        .thenCompose(
            ignored -> command("importprobe import status " + heldOperation.snapshot().id()))
        .thenCompose(
            reply -> {
              check(
                  "actual pending import is inspectable",
                  !reply.operation().orElseThrow().terminal());
              return command("importprobe import cancel " + heldOperation.snapshot().id());
            })
        .thenCompose(
            reply -> {
              check(
                  "cancellation does not invent read rollback",
                  reply.operation().orElseThrow().cancellationRequested()
                      && !heldOperation.snapshot().terminal());
              gate.release.countDown();
              return result;
            })
        .thenCompose(
            reply -> {
              check(
                  "cancelled import settles without a ticket",
                  reply.operation().orElseThrow().state() == OperationSnapshot.State.CANCELLED
                      && reply.operation().orElseThrow().result().isEmpty());
              gate = new Gate();
              var revoked =
                  command("importprobe import preview example:held reform:import/value_json");
              return gate.entered
                  .thenCompose(
                      ignored ->
                          context.onOwner(
                              () -> {
                                sourceAllowed = false;
                                gate.release.countDown();
                                return null;
                              }))
                  .thenCompose(ignored -> revoked);
            })
        .thenCompose(
            reply -> {
              check(
                  "revoked late source sends no material result",
                  reply.kind() == SettingsCommands.Kind.ERROR
                      && reply.operation().isEmpty()
                      && !reply.message().getString().contains("Imported café"));
              sourceAllowed = true;
              gate = new Gate();
              var revokedRead =
                  command("importprobe import preview example:held reform:import/value_json");
              return gate.entered
                  .thenCompose(
                      ignored ->
                          context.onOwner(
                              () -> {
                                readAllowed = false;
                                gate.release.countDown();
                                return null;
                              }))
                  .thenCompose(ignored -> revokedRead);
            })
        .thenCompose(
            reply -> {
              check(
                  "revoked document READ sends no material result",
                  reply.kind() == SettingsCommands.Kind.ERROR
                      && reply.operation().isEmpty()
                      && !reply.message().getString().contains("Imported café"));
              readAllowed = true;
              gate = new Gate();
              command("importprobe import preview example:held reform:import/value_json");
              heldOperation =
                  target
                      .actions()
                      .activeImports()
                      .orElseThrow()
                      .operation(submittedId)
                      .orElseThrow();
              return gate.entered.thenCompose(
                  ignored ->
                      context.onOwner(
                          () -> {
                            beforeReplies = replies.size();
                            available = false;
                            gate.release.countDown();
                            return null;
                          }));
            })
        .thenCompose(ignored -> heldOperation.completion())
        .thenCompose(
            snapshot ->
                context.onOwner(
                    () -> {
                      check(
                          "departed native recipient receives no late feedback",
                          replies.size() == beforeReplies);
                      pending.clear();
                      available = true;
                      return null;
                    }));
  }

  CompletionStage<JsonObject> run(boolean reopen) {
    return context
        .onWorker(
            () -> {
              Files.createDirectories(directory);
              Files.writeString(directory.resolve("Café source.json"), VALID);
              Files.writeString(
                  directory.resolve("repair.json"),
                  "{\"schemaVersion\":2,\"values\":"
                      + VALID.replace("\"count\":3", "\"count\":99")
                      + "}");
              Files.writeString(
                  directory.resolve("wrong.json"),
                  "{\"schemaVersion\":1,\"values\":" + VALID + "}");
              Files.writeString(directory.resolve("broken.json"), "{broken");
              return null;
            })
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      owner =
                          new JsonOwner<>(
                              context,
                              new JsonStore<>(
                                  ImportExample.DEFINITION,
                                  new OwnedFile(
                                      StorageBinding.relative(directory, "owner.json"),
                                      JSON.maximumBytes()),
                                  JsonStore.Format.NEW_ENVELOPE,
                                  JSON,
                                  DraftLimits.CONFIGURATION),
                              8);
                      session = owner.open(access);
                      return session.initialize();
                    }))
        .thenCompose(initialized -> initialized)
        .thenCompose(
            initialized ->
                context.onWorker(() -> Files.readString(directory.resolve("owner.json"))))
        .thenCompose(
            bytes ->
                context.onOwner(
                    () -> {
                      original = bytes;
                      sources =
                          List.of(
                              file("valid", "Café source.json"),
                              file("repair", "repair.json"),
                              file("wrong", "wrong.json"),
                              file("broken", "broken.json"),
                              file("held", "Café source.json"));
                      catalog = catalog();
                      bind();
                      NeoForge.EVENT_BUS.post(
                          new RegisterClientCommandsEvent(
                              dispatcher,
                              CommandBuildContext.simple(
                                  RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS)));
                      check(
                          "ordinary addon registered imports through native event",
                          dispatcher.getRoot().getChild("reformimports") != null);
                      dispatcher.register(
                          ImportExample.settings()
                              .commandBranch(
                                  "factoryimports",
                                  ImportExample.settings().importCatalog(sources),
                                  this::receive));
                      return null;
                    }))
        .thenCompose(
            ignored -> {
              if (reopen)
                return command("importprobe get label")
                    .thenAccept(
                        reply -> {
                          var inspection = (EditorActor.Inspection<?>) completed(reply);
                          check(
                              "saved import reopens in a second actual JVM",
                              raw("label").equals("Imported café")
                                  && raw("count").equals("3")
                                  && inspection.provenance().kind().equals("json"));
                        });
              return coldControls()
                  .thenCompose(unused -> refusalControls())
                  .thenCompose(unused -> heldControls())
                  .thenCompose(
                      unused ->
                          command(
                              "factoryimports import preview example:valid reform:import/value_json"))
                  .thenAccept(
                      reply ->
                          check(
                              "ordinary client factory exposes same cold import workflow",
                              completed(reply) instanceof DocumentImports.Preview actual
                                  && actual.source().label().equals("Café source.json")));
            })
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      check(
                          "native controls retain no pending operation",
                          pending.isEmpty() && cancellations.isEmpty());
                      complete = true;
                      return report();
                    }));
  }
}
