package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.importing.ImportCatalog;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.importing.JsonImportFormat;
import design.inherent.reform.minecraft.client.ClientCommands;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.command.SettingsCommands;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** Caller-owned installation data stays live while an actual play recipient disappears. */
final class PlatformHeldClientChecks implements PlatformFormProbe.Scene {
  private static final String ROOT = "platformheldclient";
  private static final DocumentId SOURCE = DocumentId.parse(PlatformServer.ID + ":held_input");
  private static final DocumentDefinition<PlatformData.Values> DEFINITION =
      PlatformData.definition("held_client");
  private static final JsonLimits JSON = new JsonLimits(32768, 32, 1024, 512, 64, 1024);
  private static PlatformHeldClientChecks active;

  static void register(ClientRegistration registration) {
    var held =
        new ImportSource() {
          @Override
          public DocumentId id() {
            return SOURCE;
          }

          @Override
          public String label() {
            return "Held installation input";
          }

          @Override
          public int maximumBytes() {
            return 1024;
          }

          @Override
          public void requireCurrent() {
            if (active == null || !active.context.isOpen())
              throw new IllegalStateException("Held owner is closed");
          }

          @Override
          public Capture capture() {
            requireCurrent();
            var owner = active;
            if (!owner.client.isSameThread())
              throw new AssertionError("Source capture escaped client owner");
            return () -> {
              if (owner.client.isSameThread())
                throw new AssertionError("Held read blocked client thread");
              owner.entered.complete(null);
              try {
                if (!owner.release.await(10, TimeUnit.SECONDS))
                  throw new AssertionError("Held source deadline");
              } catch (InterruptedException failed) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(failed);
              }
              owner.reads.incrementAndGet();
              return new ImportInput(
                  "Private late input",
                  "{\"distance\":13,\"label\":\"Late private draft\"}"
                      .getBytes(StandardCharsets.UTF_8),
                  1024);
            };
          }
        };
    var catalog =
        new ImportCatalog<>(
            DEFINITION,
            List.of(held),
            JsonImportFormat.documentFormats(
                DEFINITION,
                JSON,
                DraftLimits.CONFIGURATION,
                ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE));
    registration.registerCommands(
        ROOT,
        new ClientRegistration.CommandTree() {
          @Override
          public <S> LiteralArgumentBuilder<S> bind(ClientRegistration.CommandSource<S> source) {
            return ClientCommands.branch(
                ROOT,
                DEFINITION.id(),
                new ClientCommands.Owner<PlatformData.Values>() {
                  @Override
                  public boolean ready() {
                    return active != null && active.target != null && active.context.isOpen();
                  }

                  @Override
                  public SettingsCommands.Target<PlatformData.Values> target() {
                    return active.target;
                  }

                  @Override
                  public Optional<SettingsCommands.Target<PlatformData.Values>> existing() {
                    return ready() ? Optional.of(active.target) : Optional.empty();
                  }
                },
                Optional.of(catalog),
                new ClientRegistration.CommandSource<S>() {
                  @Override
                  public boolean current(S value) {
                    return source.current(value);
                  }

                  @Override
                  public void requireLocalInvocation(S value) {
                    source.requireLocalInvocation(value);
                    if (active != null && active.firstSource == null)
                      active.firstSource = () -> source.current(value);
                  }
                },
                reply -> {
                  if (active != null) active.receive(reply);
                });
          }
        });
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "HELD_CLIENT",
        driver -> {
          Files.createDirectories(
              Minecraft.getInstance()
                  .gameDirectory
                  .toPath()
                  .toAbsolutePath()
                  .resolve("held-client-probe"));
          active = new PlatformHeldClientChecks();
          return active;
        });
  }

  static void closeActive() {
    if (active != null) active.close();
  }

  private final Minecraft client = Minecraft.getInstance();
  private final OwnerContext context =
      new OwnerContext(
          client::execute,
          NativeClientProbe.ioPool(),
          () -> {
            if (!client.isSameThread())
              throw new AssertionError("Held owner escaped client thread");
          },
          OwnerContext.Limits.STANDARD);
  private final OwnerContext.Access access = context.access(() -> true, () -> true);
  private final Path path =
      client.gameDirectory.toPath().toAbsolutePath().resolve("held-client-probe/owner.json");
  private final FileOwner<PlatformData.Values>.Session session;
  private final CompletableFuture<FileOwner.Receipt<PlatformData.Values>> initialized;
  private final CompletableFuture<Void> entered = new CompletableFuture<>();
  private final CountDownLatch release = new CountDownLatch(1);
  private final AtomicInteger reads = new AtomicInteger();
  private final List<SettingsCommands.Reply> replies = new ArrayList<>();
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private SettingsCommands.Target<PlatformData.Values> target;
  private OperationHandle<?> held;
  private BooleanSupplier firstSource;
  private String original;
  private int stage;

  private PlatformHeldClientChecks() {
    var owner =
        new FileOwner<>(
            context,
            new JsonStore<>(
                DEFINITION,
                new OwnedFile(
                    StorageBinding.relative(path.getParent(), path.getFileName().toString()),
                    JSON.maximumBytes()),
                JsonStore.Format.NEW_ENVELOPE,
                JSON,
                DraftLimits.CONFIGURATION),
            8);
    session = owner.open(access);
    initialized = session.initialize().toCompletableFuture();
    report.addProperty("status", "RUNNING");
    report.add("checks", checks);
  }

  private void receive(SettingsCommands.Reply reply) {
    if (!client.isSameThread() || replies.size() >= 8)
      throw new AssertionError("Held native reply escaped its bound/thread");
    replies.add(reply);
  }

  private void check(String label, boolean valid) {
    if (!valid)
      throw new AssertionError(
          label
              + "; stage="
              + stage
              + ", reads="
              + reads.get()
              + ", held="
              + (held == null ? "none" : held.snapshot().state() + " " + held.snapshot().report()));
    checks.add(label);
  }

  @Override
  public String diagnostic() {
    return "held client stage=" + stage + ", replies=" + replies.size();
  }

  @Override
  public int tickBudget() {
    return 1200;
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!initialized.isDone()) return false;
        var receipt = initialized.join();
        check(
            "held owner initialization has an admitted observation",
            receipt.persistenceReport().valid()
                && receipt.applicationReport().valid()
                && receipt.observation().isPresent());
        original = Files.readString(path);
        var actor =
            new EditorActor<>(context, session, DEFINITION, JSON, DraftLimits.CONFIGURATION, 32);
        target =
            new SettingsCommands.Target<>(
                context,
                new DocumentActions<>(
                    context, access, actor, OperationScope.Limits.STANDARD, JSON.maximumBytes()));
        NativeWorldProbe.typed(
            "/" + ROOT + " import preview " + SOURCE + " reform:import/value_json");
        stage++;
      }
      case 1 -> {
        if (!entered.isDone() || replies.isEmpty()) return false;
        entered.join();
        var queued = replies.getFirst();
        check(
            "held input has one actual native queued receipt",
            replies.size() == 1 && queued.kind() == SettingsCommands.Kind.QUEUED);
        held =
            target
                .actions()
                .activeImports()
                .orElseThrow()
                .operation(queued.operation().orElseThrow().id())
                .orElseThrow();
        check(
            "held source remains active without blocking client tick",
            !held.snapshot().terminal() && release.getCount() == 1 && firstSource.getAsBoolean());
        NativeWorldProbe.leave();
        stage++;
      }
      case 2 -> {
        if (client.player != null
            || client.level != null
            || client.getSingleplayerServer() != null
            || !(NativeScreens.current(client) instanceof TitleScreen)) return false;
        check(
            "actual disconnect invalidates the original native source",
            !firstSource.getAsBoolean());
        check(
            "caller-owned installation context survives world stop",
            context.isOpen() && !held.snapshot().terminal());
        release.countDown();
        stage++;
      }
      case 3 -> {
        if (!held.snapshot().terminal()) return false;
        check(
            "entered read returns but departed-source preview is refused",
            reads.get() == 1
                && held.snapshot().state() == OperationSnapshot.State.REFUSED
                && held.snapshot().result().isEmpty()
                && held.snapshot().report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("import.admission")));
        stage++;
      }
      case 4 -> {
        check("old play recipient receives no terminal private disclosure", replies.size() == 1);
        check(
            "held preview does not write installation source",
            Files.readString(path).equals(original));
        NativeWorldProbe.start();
        stage++;
      }
      case 5 -> {
        if (client.player == null || client.level == null || NativeScreens.current(client) != null)
          return false;
        check("reconnect does not revive the earlier native source", !firstSource.getAsBoolean());
        NativeWorldProbe.typed("/" + ROOT + " import status " + held.snapshot().id());
        stage++;
      }
      case 6 -> {
        if (replies.size() < 2) return false;
        var reply = replies.getLast();
        check(
            "fresh query cannot revive departed registered import authority",
            replies.size() == 2
                && reply.kind() == SettingsCommands.Kind.ERROR
                && reply.operation().isEmpty());
        NativeWorldProbe.typed(
            "/" + ROOT + " import preview " + SOURCE + " reform:import/value_json");
        stage++;
      }
      case 7 -> {
        if (replies.size() < 4) return false;
        var reply = replies.getLast();
        check(
            "fresh explicit preview acquires current native authority",
            replies.size() == 4
                && reads.get() == 2
                && reply.kind() == SettingsCommands.Kind.COMPLETED
                && reply.operation().orElseThrow().state() == OperationSnapshot.State.COMPLETED
                && !reply.operation().orElseThrow().id().equals(held.snapshot().id())
                && reply.operation().orElseThrow().result().orElseThrow()
                    instanceof DocumentImports.Preview preview
                && preview.stageable());
        check(
            "explicit status does not stage or save the held preview",
            !target.actions().view().dirty() && Files.readString(path).equals(original));
        close();
        check("caller-owned held context closes after settled work", !context.isOpen());
        report.addProperty("status", "PASS");
        report.addProperty("contextClosed", true);
        return true;
      }
      default -> throw new AssertionError("Unexpected held client stage");
    }
    return false;
  }

  @Override
  public JsonObject report() {
    return report;
  }

  @Override
  public void close() {
    release.countDown();
    if (context.isOpen()) context.close();
    if (active == this) active = null;
  }
}
