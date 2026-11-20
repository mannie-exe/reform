package example.reformprobe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.neoforge.configuration.RegisteredConfig;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.PreparedEdit;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Independent native consumer of the packaged public owner/editor API, never shipped. */
final class RegisteredOwnerProbe {
  private enum Choice {
    FIRST,
    SECOND
  }

  private final ModConfigSpec spec;
  private final ModConfigSpec.IntValue live, world, game, cold;
  private final AtomicInteger saves = new AtomicInteger();
  private final AtomicInteger nativeValidatorCalls = new AtomicInteger();
  private final AtomicInteger offThreadValidators = new AtomicInteger();
  private final AtomicBoolean writeAllowed = new AtomicBoolean(true);
  private final AtomicBoolean failListener = new AtomicBoolean();
  private final AtomicBoolean readAllowed = new AtomicBoolean(true);
  private final AtomicBoolean revokeReadOnReload = new AtomicBoolean();
  private final AtomicBoolean closeOnReload = new AtomicBoolean();
  private final List<String> checks = new ArrayList<>();
  private final JsonObject report = new JsonObject();
  private final DocumentId id = new DocumentId("reform_native_example_probe", "registered");
  private ModConfig config;
  private Thread ownerThread;
  private OwnerContext context;
  private RegisteredConfig owner;
  private RegisteredConfig.Session session;
  private ExecutorService workers;
  private RegisteredConfig.Observation baseline;
  private RegisteredConfig.Prepared prepared;
  private Set<PosixFilePermission> directoryPermissions;

  RegisteredOwnerProbe(IEventBus bus, ModContainer container) {
    var builder = new ModConfigSpec.Builder();
    live = builder.defineInRange("live", 1, 0, 100);
    world = builder.worldRestart().defineInRange("world", 1, 0, 100);
    game = builder.gameRestart().defineInRange("game", 1, 0, 100);
    cold = builder.worldRestart().defineInRange("cold", 1, 0, 100);
    builder.push("nested");
    builder.define(
        "label",
        "native",
        value -> {
          if (ownerThread != null) {
            nativeValidatorCalls.incrementAndGet();
            if (Thread.currentThread() != ownerThread) offThreadValidators.incrementAndGet();
          }
          return value instanceof String text && text.length() <= 16;
        });
    builder.defineEnum("choice", Choice.FIRST);
    builder.defineListAllowEmpty(
        "names", List.<String>of(), () -> "name", String.class::isInstance);
    builder.pop();
    spec = builder.build();
    container.registerConfig(ModConfig.Type.COMMON, spec, "registered-owner.toml");
    bus.addListener(
        (ModConfigEvent.Loading event) -> {
          if (event.getConfig().getSpec() == spec) config = event.getConfig();
        });
    bus.addListener(
        (ModConfigEvent.Reloading event) -> {
          if (event.getConfig().getSpec() == spec && Thread.currentThread() == ownerThread) {
            saves.incrementAndGet();
            if (failListener.getAndSet(false))
              throw new IllegalStateException("deliberate fixture listener failure");
            if (revokeReadOnReload.getAndSet(false)) readAllowed.set(false);
            if (closeOnReload.getAndSet(false)) context.close();
          }
        });
    NeoForge.EVENT_BUS.addListener(this::started);
  }

  private void check(String name, boolean valid) {
    if (!valid) throw new AssertionError(name);
    synchronized (checks) {
      checks.add(name);
    }
  }

  private CompletionStage<Void> refused(
      Supplier<? extends CompletionStage<?>> action, String code) {
    CompletionStage<?> result;
    try {
      result = action.get();
    } catch (Throwable failure) {
      result = CompletableFuture.failedFuture(failure);
    }
    return result.handle(
        (resultValue, failure) -> {
          while (failure instanceof java.util.concurrent.CompletionException
              && failure.getCause() != null) failure = failure.getCause();
          check(
              "refused " + code,
              (failure instanceof PolicyRefusal refused
                      && refused.report().issues().stream()
                          .anyMatch(issue -> issue.code().equals(code)))
                  || (resultValue instanceof CommitReceipt<?> receipt
                      && receipt.persistence() == CommitReceipt.Persistence.REFUSED
                      && receipt.application() == CommitReceipt.Application.NOT_ATTEMPTED
                      && receipt.persistenceReport().issues().stream()
                          .anyMatch(issue -> issue.code().equals(code))));
          return null;
        });
  }

  private DraftTree draft(RegisteredConfig.Observation observed, int value) {
    var draft = observed.document().draft().orElseThrow();
    for (String field : List.of("live", "world", "game", "cold"))
      draft =
          draft.change(
              FieldPath.ROOT.field(field),
              ignored -> new DraftNode.Scalar(Integer.toString(value)));
    return draft;
  }

  private CompletionStage<RegisteredConfig.Receipt> save(int value) {
    return session
        .read()
        .thenCompose(observed -> session.prepare(observed, draft(observed, value)))
        .thenCompose(session::commit);
  }

  private void started(ServerStartedEvent event) {
    var server = event.getServer();
    ownerThread = Thread.currentThread();
    report.addProperty("status", "FAIL");
    final Path directory = server.getServerDirectory().toAbsolutePath();
    final boolean reopen;
    try {
      reopen =
          JsonParser.parseString(Files.readString(directory.resolve("registered-probe-mode.json")))
              .getAsJsonObject()
              .get("mode")
              .getAsString()
              .equals("reopen");
      var artifact = ModList.get().getModFileById("reform").getFile().getFilePath();
      report.addProperty(
          "librarySha256",
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
      workers = Executors.newFixedThreadPool(2);
      context =
          new OwnerContext(
              server::execute,
              workers,
              () -> {
                if (!server.isSameThread())
                  throw new IllegalStateException("Expected native server executor");
              },
              OwnerContext.Limits.STANDARD);
      check(
          "actual registered loaded native spec",
          config != null && config.getSpec() == spec && spec.isLoaded());
      boolean ambiguous = false;
      try {
        new RegisteredConfig(context, config, id);
      } catch (IllegalArgumentException expected) {
        ambiguous = true;
      }
      check("empty native list requires explicit type", ambiguous);
      owner =
          new RegisteredConfig(
              context,
              config,
              id,
              Map.of(
                  FieldPath.ROOT.field("nested").field("names"),
                  ValueTypes.list(ValueTypes.text(32), 16)),
              RegisteredConfig.Limits.STANDARD);
      session = owner.session(context.access(readAllowed::get, writeAllowed::get));
      check(
          "native apply-only unsupported",
          !session.operations().contains(DocumentSession.Operation.APPLY_SAVED));
      if (!reopen) check("warm native restart caches", world.get() == 1 && game.get() == 1);
    } catch (Throwable failure) {
      finish(server, failure);
      return;
    }
    CompletionStage<Void> work =
        NativeReferenceChecks.verify(server, context, report)
            .thenCompose(ignored -> delegatedProjection())
            .thenCompose(
                ignored ->
                    context.onOwner(
                        () ->
                            NativeDraftChecks.verify(context, owner, config.getFullPath(), report)))
            .thenCompose(stage -> stage)
            .thenCompose(ignored -> reopen ? reopened() : first());
    work.whenComplete((ignored, failure) -> server.execute(() -> finish(server, failure)));
  }

  private CompletionStage<Void> delegatedProjection() {
    return context
        .onOwner(
            () -> {
              var nativeVersion =
                  ModList.get()
                      .getModContainerById(config.getModId())
                      .orElseThrow()
                      .getModInfo()
                      .getVersion()
                      .toString();
              var livePath = FieldPath.ROOT.field("live");
              var worldPath = FieldPath.ROOT.field("world");
              var selected = Set.of(livePath, worldPath);
              var wrong =
                  new RegisteredConfig.Delegation(
                      config.getModId(),
                      "unknown-fixture-version",
                      config.getType(),
                      config.getFileName(),
                      selected);
              try {
                wrong.requireTarget(config);
                throw new AssertionError("Unknown native version was admitted");
              } catch (PolicyRefusal refused) {
                check(
                    "explicit delegated version refusal",
                    refused.report().issues().getFirst().code().equals("native.delegate_version"));
              }
              var gate =
                  new RegisteredConfig.Delegation(
                      config.getModId(),
                      nativeVersion,
                      config.getType(),
                      config.getFileName(),
                      selected);
              var delegated =
                  RegisteredConfig.delegated(
                      context,
                      config,
                      new DocumentId(config.getModId(), "projected"),
                      gate,
                      Map.of(),
                      RegisteredConfig.Limits.STANDARD,
                      new RegisteredConfig.Policy(
                          map ->
                              (Integer) map.get("live") >= (Integer) map.get("world")
                                  ? ValidationReport.VALID
                                  : new ValidationReport(
                                      List.of(
                                          Issue.error(
                                              "fixture.native_bounds",
                                              worldPath,
                                              "World limit must not exceed the live limit")),
                                      true),
                          new ApplicationEffects(
                              List.of(
                                  new ApplicationEffects.Rule(
                                      livePath,
                                      Set.of(ApplicationEffects.Timing.NEXT_JOIN),
                                      "Affects the next connection"),
                                  new ApplicationEffects.Rule(
                                      worldPath,
                                      Set.of(ApplicationEffects.Timing.NEW_WORLD),
                                      "Affects future worlds")))));
              var access = context.access(() -> true, () -> true);
              var source = delegated.session(access);
              return source
                  .read()
                  .thenCompose(
                      observed -> {
                        check(
                            "delegated native projection skips ambiguous unrelated list",
                            observed.document().report().valid()
                                && observed.nativeValues().keySet().equals(Set.of("live", "world"))
                                && observed
                                    .document()
                                    .candidate()
                                    .orElseThrow()
                                    .value()
                                    .keySet()
                                    .equals(Set.of("live", "world")));
                        check(
                            "native restart survives the field-specific owner policy",
                            observed
                                    .applicationEffects()
                                    .forField(worldPath)
                                    .orElseThrow()
                                    .timings()
                                    .equals(
                                        Set.of(
                                            ApplicationEffects.Timing.NEW_WORLD,
                                            ApplicationEffects.Timing.WORLD_RESTART))
                                && observed
                                    .applicationEffects()
                                    .forField(livePath)
                                    .orElseThrow()
                                    .timings()
                                    .equals(Set.of(ApplicationEffects.Timing.NEXT_JOIN)));
                        var draft = observed.document().draft().orElseThrow();
                        var invalid =
                            draft.change(worldPath, ignored -> new DraftNode.Scalar("45"));
                        var savesBefore = saves.get();
                        check(
                            "native field spec alone admits the invalid whole-value pair",
                            world.getSpec().test(45)
                                && (Integer) observed.nativeValues().get("live") < 45);
                        return refused(
                                () -> source.prepare(observed, invalid), "fixture.native_bounds")
                            .thenCompose(
                                ignored -> {
                                  check(
                                      "pure complete policy refuses before native save",
                                      saves.get() == savesBefore);
                                  return source.prepare(observed, draft);
                                });
                      })
                  .thenCompose(source::commit)
                  .thenCompose(
                      receipt ->
                          context.onOwner(
                              () -> {
                                check(
                                    "delegated native no-op retains the existing full source",
                                    receipt.persistence() == CommitReceipt.Persistence.UNCHANGED
                                        && receipt.applicationReport().valid()
                                        && !receipt.mutationStarted());
                                return null;
                              }))
                  .handle(
                      (ignored, failure) ->
                          context.<Void>onOwner(
                              () -> {
                                source.close();
                                access.close();
                                delegated.close();
                                if (failure != null)
                                  throw new java.util.concurrent.CompletionException(failure);
                                return null;
                              }))
                  .thenCompose(stage -> stage);
            })
        .thenCompose(stage -> stage);
  }

  private CompletionStage<Void> reopened() {
    return session
        .read()
        .thenCompose(
            observed ->
                context.onOwner(
                    () -> {
                      check("reopened source is valid", observed.document().report().valid());
                      check(
                          "native saved values reopen",
                          live.getRaw() == 3 && world.getRaw() == 3 && game.getRaw() == 3);
                      check(
                          "restart activates staged native values",
                          live.get() == 3
                              && world.get() == 3
                              && game.get() == 3
                              && cold.get() == 3);
                      return null;
                    }));
  }

  private CompletionStage<Void> first() {
    return session
        .read()
        .thenCompose(
            observed -> {
              baseline = observed;
              check(
                  "application declarations retain actual native field restart metadata",
                  observed
                          .applicationEffects()
                          .forField(FieldPath.ROOT.field("live"))
                          .orElseThrow()
                          .timings()
                          .equals(
                              Set.of(
                                  design.inherent.reform.owner.ApplicationEffects.Timing
                                      .OWNER_MANAGED))
                      && observed
                          .applicationEffects()
                          .forField(FieldPath.ROOT.field("world"))
                          .orElseThrow()
                          .timings()
                          .equals(
                              Set.of(
                                  design.inherent.reform.owner.ApplicationEffects.Timing
                                      .WORLD_RESTART))
                      && observed
                          .applicationEffects()
                          .forField(FieldPath.ROOT.field("game"))
                          .orElseThrow()
                          .timings()
                          .equals(
                              Set.of(
                                  design.inherent.reform.owner.ApplicationEffects.Timing
                                      .GAME_RESTART)));
              check(
                  "native original saved/raw observation",
                  observed.document().report().valid()
                      && observed.nativeValues().get("live").equals(1));
              return session.prepare(observed, observed.document().draft().orElseThrow());
            })
        .thenCompose(session::commit)
        .thenCompose(
            receipt ->
                context.onOwner(
                    () -> {
                      check(
                          "native no-op avoids serializer and listeners",
                          receipt.persistence() == CommitReceipt.Persistence.UNCHANGED
                              && !receipt.mutationStarted()
                              && !receipt.nativeSaveReturned()
                              && saves.get() == 0);
                      return null;
                    }))
        .thenCompose(ignored -> refused(() -> session.applySaved(baseline), "owner.unsupported"))
        .thenCompose(
            ignored ->
                refused(
                    () ->
                        session.commit(
                            new PreparedEdit<Map<String, Object>>() {
                              @Override
                              public DocumentId document() {
                                return owner.definition().id();
                              }

                              @Override
                              public UUID context() {
                                return context.id();
                              }

                              @Override
                              public UUID session() {
                                return session.id();
                              }
                            }),
                    "owner.session"))
        .thenCompose(
            ignored ->
                refused(() -> session.prepare(baseline, draft(baseline, 101)), "native.validation"))
        .thenCompose(
            ignored ->
                refused(
                    () ->
                        session.prepare(
                            baseline,
                            baseline
                                .document()
                                .draft()
                                .orElseThrow()
                                .change(
                                    FieldPath.ROOT.field("live"),
                                    value -> new DraftNode.Scalar("1.5"))),
                    "native.validation"))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      var other = owner.session(context.access(() -> true, () -> true));
                      return other;
                    }))
        .thenCompose(
            other ->
                refused(() -> other.prepare(baseline, draft(baseline, 2)), "owner.session")
                    .thenRun(other::close))
        .thenCompose(ignored -> session.prepare(baseline, draft(baseline, 2)))
        .thenCompose(
            value -> {
              prepared = value;
              writeAllowed.set(false);
              return refused(() -> session.commit(value), "owner.permission");
            })
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      check(
                          "revoked writer made no native mutation",
                          live.getRaw() == 1 && saves.get() == 0);
                      writeAllowed.set(true);
                      return null;
                    }))
        .thenCompose(ignored -> refused(() -> session.commit(prepared), "owner.consumed"))
        .thenCompose(ignored -> session.prepare(baseline, draft(baseline, 2)))
        .thenCompose(
            value -> {
              prepared = value;
              return session.commit(value);
            })
        .thenCompose(
            receipt ->
                context.onOwner(
                    () -> {
                      check(
                          "native save and real synchronous callback",
                          receipt.persistence() == CommitReceipt.Persistence.SAVED
                              && receipt.nativeSaveReturned()
                              && saves.get() == 1);
                      check(
                          "native application is staged with explicit requirements",
                          receipt.application() == CommitReceipt.Application.STAGED
                              && receipt.requirements().stream()
                                  .anyMatch(
                                      r -> r.kind() == CommitReceipt.RequirementKind.WORLD_RESTART)
                              && receipt.requirements().stream()
                                  .anyMatch(
                                      r -> r.kind() == CommitReceipt.RequirementKind.GAME_RESTART)
                              && receipt.requirements().stream()
                                  .anyMatch(
                                      r ->
                                          r.kind() == CommitReceipt.RequirementKind.OWNER_MANAGED));
                      check(
                          "saved/native values are separately retained",
                          receipt.observation().orElseThrow().document().candidate().isPresent()
                              && receipt
                                  .observation()
                                  .orElseThrow()
                                  .nativeValues()
                                  .get("live")
                                  .equals(2));
                      check(
                          "native live versus warmed restart cache",
                          live.get() == 2 && world.get() == 1 && game.get() == 1);
                      check("adapter did not warm cold gameplay cache", cold.get() == 2);
                      return null;
                    }))
        .thenCompose(ignored -> refused(() -> session.commit(prepared), "owner.consumed"))
        .thenCompose(ignored -> session.read())
        .thenCompose(
            observed -> {
              baseline = observed;
              return session.prepare(observed, draft(observed, 3));
            })
        .thenCompose(
            value ->
                context.onOwner(
                    () -> {
                      live.set(9);
                      return value;
                    }))
        .thenCompose(value -> refused(() -> session.commit(value), "native.context_changed"))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      live.set(2);
                      return null;
                    }))
        .thenCompose(ignored -> session.prepare(baseline, draft(baseline, 3)))
        .thenCompose(
            value ->
                context.onOwner(
                    () -> {
                      var path = config.getFullPath();
                      var text = Files.readString(path);
                      Files.writeString(path, text + "\n# external fixture edit\n");
                      return Map.entry(value, text);
                    }))
        .thenCompose(
            pair ->
                refused(() -> session.commit(pair.getKey()), "native.source_changed")
                    .thenCompose(
                        ignored ->
                            context.onOwner(
                                () -> {
                                  Files.writeString(config.getFullPath(), pair.getValue());
                                  check(
                                      "stale source refusal preserved native state",
                                      live.getRaw() == 2 && saves.get() == 1);
                                  return null;
                                })))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      failListener.set(true);
                      return null;
                    }))
        .thenCompose(ignored -> save(3))
        .thenCompose(
            receipt ->
                context.onOwner(
                    () -> {
                      check(
                          "listener failure retains confirmed saved source",
                          receipt.persistence() == CommitReceipt.Persistence.SAVED
                              && receipt.application() == CommitReceipt.Application.UNAVAILABLE
                              && !receipt.nativeSaveReturned());
                      check(
                          "listener failure does not fake rollback",
                          live.getRaw() == 3 && saves.get() == 2);
                      return null;
                    }))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      var parent = config.getFullPath().getParent();
                      directoryPermissions = Files.getPosixFilePermissions(parent);
                      Files.setPosixFilePermissions(
                          parent,
                          Set.of(
                              PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                      check("fixture really denies writes", !Files.isWritable(parent));
                      return null;
                    }))
        .thenCompose(ignored -> save(4))
        .thenCompose(
            receipt ->
                context.onOwner(
                    () -> {
                      Files.setPosixFilePermissions(
                          config.getFullPath().getParent(), directoryPermissions);
                      directoryPermissions = null;
                      check(
                          "native persistence failure keeps exact disk outcome",
                          receipt.persistence() == CommitReceipt.Persistence.FAILED
                              && receipt.application() == CommitReceipt.Application.UNAVAILABLE
                              && saves.get() == 2);
                      check(
                          "failed save retains actual changed native state",
                          live.getRaw() == 4
                              && live.get() == 4
                              && receipt
                                  .observation()
                                  .orElseThrow()
                                  .nativeValues()
                                  .get("live")
                                  .equals(4)
                              && !receipt.observation().orElseThrow().document().report().valid());
                      // Restore only this fixture's own known inputs. Product performs no rollback.
                      live.set(3);
                      world.set(3);
                      game.set(3);
                      cold.set(3);
                      return null;
                    }))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      var actor =
                          new EditorActor<>(
                              context,
                              owner.session(context.access(() -> true, () -> true)),
                              owner.definition(),
                              new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
                              DraftLimits.CONFIGURATION,
                              16);
                      return actor;
                    }))
        .thenCompose(
            actor ->
                actor
                    .load()
                    .thenCompose(
                        view -> {
                          check(
                              "shared editor loads native backend",
                              view.availability() == EditorActor.Availability.READY
                                  && view.observationReport().valid());
                          return actor.setRaw(FieldPath.ROOT.field("live"), "5");
                        })
                    .thenCompose(
                        ignored ->
                            context.onOwner(
                                () -> {
                                  live.set(9);
                                  return null;
                                }))
                    .thenCompose(ignored -> actor.save())
                    .thenCompose(
                        refusedSave ->
                            context.onOwner(
                                () -> {
                                  check(
                                      "native preflight refusal is not an unknown editor write",
                                      refusedSave.receipt().orElseThrow().persistence()
                                              == CommitReceipt.Persistence.REFUSED
                                          && actor.view().pending().isEmpty()
                                          && actor.view().dirty()
                                          && actor
                                              .view()
                                              .snapshot()
                                              .orElseThrow()
                                              .draft()
                                              .at(FieldPath.ROOT.field("live"))
                                              .equals(new DraftNode.Scalar("5"))
                                          && live.getRaw() == 9);
                                  live.set(3);
                                  return null;
                                }))
                    .thenCompose(ignored -> actor.save())
                    .thenCompose(
                        saved ->
                            context.onOwner(
                                () -> {
                                  check(
                                      "shared editor saves through native owner",
                                      saved.receipt().orElseThrow().persistence()
                                              == CommitReceipt.Persistence.SAVED
                                          && live.getRaw() == 5
                                          && !actor.view().dirty());
                                  actor.close();
                                  return null;
                                })))
        .thenCompose(ignored -> save(3))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      check(
                          "native validators stayed on actual owner",
                          nativeValidatorCalls.get() > 0 && offThreadValidators.get() == 0);
                      readAllowed.set(false);
                      return null;
                    }))
        .thenCompose(ignored -> refused(session::read, "owner.permission"))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      readAllowed.set(true);
                      var path = config.getFullPath();
                      var original = Files.readString(path);
                      Files.writeString(path, "not = [ valid TOML");
                      return original;
                    }))
        .thenCompose(
            original ->
                session
                    .read()
                    .thenCompose(
                        observed ->
                            context.onOwner(
                                () -> {
                                  check(
                                      "malformed native bytes stay unavailable",
                                      observed.document().draft().isEmpty()
                                          && observed.document().candidate().isEmpty()
                                          && !observed.document().report().complete());
                                  check(
                                      "malformed native source is not corrected",
                                      Files.readString(config.getFullPath())
                                              .equals("not = [ valid TOML")
                                          && live.getRaw() == 3);
                                  Files.writeString(config.getFullPath(), original);
                                  return null;
                                })))
        .thenCompose(
            ignored ->
                context.onOwner(
                    () -> {
                      revokeReadOnReload.set(true);
                      return null;
                    }))
        .thenCompose(ignored -> save(4))
        .thenCompose(
            receipt ->
                context.onOwner(
                    () -> {
                      check(
                          "read revocation suppresses native readback",
                          receipt.persistence() == CommitReceipt.Persistence.UNKNOWN
                              && receipt.observation().isEmpty()
                              && receipt.savedRevision().isEmpty()
                              && receipt.mutationStarted()
                              && receipt.nativeSaveReturned());
                      check("read revocation retains actual native save fact", live.getRaw() == 4);
                      readAllowed.set(true);
                      closeOnReload.set(true);
                      return null;
                    }))
        .thenCompose(ignored -> save(3))
        .thenAccept(
            receipt -> {
              check(
                  "reentrant close settles native attempt",
                  !context.isOpen()
                      && !session.isOpen()
                      && receipt.persistence() == CommitReceipt.Persistence.UNKNOWN
                      && receipt.observation().isEmpty()
                      && receipt.mutationStarted()
                      && receipt.nativeSaveReturned());
              check("closed context does not claim rollback", live.getRaw() == 3);
            });
  }

  private void finish(net.minecraft.server.MinecraftServer server, Throwable failure) {
    try {
      if (directoryPermissions != null)
        Files.setPosixFilePermissions(config.getFullPath().getParent(), directoryPermissions);
      if (context != null && context.isOpen()) context.close();
      if (workers != null) workers.shutdownNow();
      if (failure != null) report.addProperty("failure", failure.toString());
      else report.addProperty("status", "PASS");
      report.addProperty("nativeSaves", saves.get());
      report.addProperty("nativeValidatorCalls", nativeValidatorCalls.get());
      report.addProperty("offThreadValidators", offThreadValidators.get());
      report.add("checks", new com.google.gson.Gson().toJsonTree(checks));
      Files.writeString(
          server.getServerDirectory().resolve("native-addon-server.json"),
          new GsonBuilder().setPrettyPrinting().create().toJson(report));
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    } finally {
      server.halt(false);
    }
  }
}
