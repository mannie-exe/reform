package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.importing.FileImportSource;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.JsonImportFormat;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public packaged API only. This is a headless fixture, not a production world/profile compiler.
 */
public final class ReviewProfile {
  private record Profile(String name, int minimum, int maximum, List<String> references) {}

  public static void main(String[] args) throws Exception {
    refuseGameClasspath("net.minecraft.world.level.Level");
    refuseGameClasspath("net.neoforged.fml.ModContainer");
    refuseGameClasspath("net.fabricmc.loader.api.FabricLoader");
    var name = FieldKey.of("name", ValueTypes.text(64));
    var minimum = FieldKey.of("minimum", ValueTypes.integer(0, 512));
    var maximum = FieldKey.of("maximum", ValueTypes.integer(0, 512));
    var references = FieldKey.of("references", ValueTypes.list(ValueTypes.text(128), 16));
    var type =
        RecordType.<Profile>builder(
                values ->
                    new Profile(
                        values.get(name),
                        values.get(minimum),
                        values.get(maximum),
                        values.get(references)))
            .field(name, Profile::name)
            .field(minimum, Profile::minimum)
            .field(maximum, Profile::maximum)
            .field(references, Profile::references)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:profile"),
            1,
            type,
            () -> new Profile("Default", 16, 128, List.of()),
            profile ->
                profile.minimum() <= profile.maximum()
                    ? ValidationReport.VALID
                    : new ValidationReport(
                        List.of(
                            new Issue(
                                "range.order",
                                Issue.Severity.ERROR,
                                FieldPath.ROOT.field("minimum"),
                                List.of(FieldPath.ROOT.field("maximum")),
                                java.util.Map.of(),
                                "Minimum must not exceed maximum")),
                        true));
    var limits = new JsonLimits(8192, 16, 512, 256, 64, 1024);
    var good =
        definition.decode(
            JsonInput.read(
                "{\"name\":\"Review\",\"minimum\":32,\"maximum\":96,\"references\":[\"optional:missing\"]}",
                limits));
    if (!good.report().valid() || good.value().orElseThrow().minimum() != 32) {
      throw new IllegalStateException("Public valid candidate failed");
    }
    var invalid =
        definition.decode(
            JsonInput.read(
                "{\"name\":\"Review\",\"minimum\":96,\"maximum\":32,\"references\":[]}", limits));
    if (invalid.value().isPresent()
        || !invalid.report().issues().getFirst().code().equals("range.order")) {
      throw new IllegalStateException("Public cross-field diagnostics failed");
    }
    var fraction =
        definition.decode(
            JsonInput.read(
                "{\"name\":\"Review\",\"minimum\":32.5,\"maximum\":96,\"references\":[]}", limits));
    if (fraction.value().isPresent())
      throw new IllegalStateException("Fraction was silently truncated");
    if (definition.defaults().minimum() != 16) throw new IllegalStateException("Defaults changed");

    var session =
        new EditSession<>(
            definition,
            UUID.randomUUID(),
            "saved-1",
            good.value().orElseThrow(),
            limits,
            DraftLimits.CONFIGURATION);
    var minPath = FieldPath.ROOT.field("minimum");
    session.setRaw(minPath, "-");
    if (session.prepareSubmission().state() != EditSession.PreparationState.INVALID)
      throw new IllegalStateException("Incomplete draft became a submission");
    session.undo();
    session.setRaw(minPath, "40");
    var submission = session.prepareSubmission().submission().orElseThrow();
    session.markInFlight(submission);
    session.setRaw(FieldPath.ROOT.field("name"), "Later");
    if (session.acknowledge(
            submission, new Profile("Review", 40, 96, List.of("optional:missing")), "saved-2")
        != EditSession.Acknowledgement.ACCEPTED)
      throw new IllegalStateException("Public acknowledgement failed");
    if (!session.snapshot().inspect().candidate().orElseThrow().value().name().equals("Later"))
      throw new IllegalStateException("Late draft edit was lost");
    var observed =
        DraftTree.from(
            type,
            new Profile("Remote", 40, 128, List.of("optional:missing")),
            DraftLimits.CONFIGURATION);
    var review = session.reviewConflict(observed, "saved-3");
    session.resolve(
        review,
        Map.of(
            FieldPath.ROOT.field("name"),
            ConflictReview.Choice.DRAFT,
            FieldPath.ROOT.field("maximum"),
            ConflictReview.Choice.OBSERVED));
    var reviewed = session.snapshot().inspect().candidate().orElseThrow().value();
    if (!reviewed.name().equals("Later") || reviewed.maximum() != 128)
      throw new IllegalStateException("Public manual review did not retain choices");
    var next = session.prepareSubmission().submission().orElseThrow();
    if (!next.sourceRevision().equals("saved-3"))
      throw new IllegalStateException("Review retained the stale saved revision");
    session.reload(definition.defaults(), "reloaded");
    if (session.acknowledge(next, next.candidate().value(), "late")
        != EditSession.Acknowledgement.STALE)
      throw new IllegalStateException("Old acknowledgement crossed a reload");
    session.close();
    verifyStorage(definition, limits);
    CreationRecipe.verify();
    MapEditing.verify();
    SetEditing.verify();
    FieldDefaults.verify();
    OptionalEditing.verify();
    TaggedEditing.verify();
    CompositionEditing.verify();
    UnitsEditing.verify();
    NumericDomains.verify();
    RangeEditing.verify();
    DependentEditing.verify();
    PropertiesEditing.verify();
    System.out.println(
        "Packaged public consumer passed: decode/draft/history/late-save/conflict/reload/real-file/async-owner/context-recipe/typed-maps/sets/field-defaults/optionals/variants/composition/units/ranges/dependencies/scalar-selection/manual-conflict-service/properties-startup; game classes absent");
  }

  private static void verifyStorage(DocumentDefinition<Profile> definition, JsonLimits limits)
      throws Exception {
    Path fixture = Files.createTempDirectory("reform-public-");
    try (var ownerExecutor = Executors.newSingleThreadExecutor();
        var worker = Executors.newSingleThreadExecutor()) {
      var binding = StorageBinding.newJson(fixture, definition.id());
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(binding, limits.maximumBytes()),
              JsonStore.Format.NEW_ENVELOPE,
              limits,
              DraftLimits.CONFIGURATION);
      long nativeThread = ownerExecutor.submit(() -> Thread.currentThread().threadId()).get();
      var context =
          new OwnerContext(
              ownerExecutor,
              worker,
              () -> {
                if (Thread.currentThread().threadId() != nativeThread)
                  throw new IllegalStateException("Wrong native owner thread");
              },
              OwnerContext.Limits.STANDARD);
      try {
        var owner = ownerExecutor.submit(() -> new FileOwner<>(context, store, 8)).get();
        var writable = new AtomicBoolean(true);
        var access = ownerExecutor.submit(() -> context.access(() -> true, writable::get)).get();
        var bound = ownerExecutor.submit(() -> owner.open(access)).get();
        if (await(bound.read()).saved().state() != JsonStore.State.MISSING
            || Files.exists(binding.path()))
          throw new IllegalStateException("Public browsing initialized storage implicitly");
        var initialized = await(bound.initialize());
        if (initialized.persistence() != CommitReceipt.Persistence.SAVED
            || initialized.application() != CommitReceipt.Application.APPLIED)
          throw new IllegalStateException("Public owner initialization failed");
        var baseline = await(bound.read());
        var session =
            EditSession.fromDraft(
                definition,
                baseline.context(),
                baseline.saved().source().revision(),
                baseline.saved().draft().orElseThrow(),
                limits,
                DraftLimits.CONFIGURATION);
        session.setRaw(FieldPath.ROOT.field("name"), "Stored");
        var submission = session.prepareSubmission().submission().orElseThrow();
        var prepared = await(bound.prepare(baseline, submission.candidate().draft()));
        session.markInFlight(submission);
        var committing = bound.commit(prepared);
        session.setRaw(FieldPath.ROOT.field("name"), "Later");
        var written = await(committing);
        if (written.persistence() != CommitReceipt.Persistence.SAVED
            || written.application() != CommitReceipt.Application.APPLIED)
          throw new IllegalStateException("Public asynchronous commit did not save and publish");
        var observed = written.observation().orElseThrow();
        if (session.acknowledge(
                    submission,
                    observed.saved().draft().orElseThrow(),
                    observed.saved().source().revision())
                != EditSession.Acknowledgement.ACCEPTED
            || !session
                .snapshot()
                .inspect()
                .candidate()
                .orElseThrow()
                .value()
                .name()
                .equals("Later")
            || !observed.effective().value().orElseThrow().name().equals("Stored"))
          throw new IllegalStateException(
              "Public owner receipt lost saved/draft/effective separation");
        var text = Files.readString(binding.path());
        var modified = Files.getLastModifiedTime(binding.path());
        writable.set(false);
        var denied = await(bound.applySaved(observed));
        if (denied.persistence() != CommitReceipt.Persistence.NOT_ATTEMPTED
            || denied.application() != CommitReceipt.Application.NOT_ATTEMPTED
            || !denied.persistenceReport().issues().getFirst().code().equals("owner.permission"))
          throw new IllegalStateException("Public owner accepted revoked authorization");
        writable.set(true);
        var applied = await(bound.applySaved(observed));
        if (applied.persistence() != CommitReceipt.Persistence.NOT_ATTEMPTED
            || applied.application() != CommitReceipt.Application.APPLIED
            || !Files.readString(binding.path()).equals(text)
            || !Files.getLastModifiedTime(binding.path()).equals(modified))
          throw new IllegalStateException("Public application-only retry wrote the file again");
        session.close();
        var actor =
            ownerExecutor
                .submit(
                    () ->
                        new EditorActor<>(
                            context, bound, definition, limits, DraftLimits.CONFIGURATION, 8))
                .get();
        try {
          await(actor.load());
          await(actor.setRaw(FieldPath.ROOT.field("name"), "Actor"));
          var savedByActor = await(actor.save());
          if (savedByActor.receipt().orElseThrow().persistence() != CommitReceipt.Persistence.SAVED
              || actor.view().dirty()
              || !owner.handle().view().value().orElseThrow().name().equals("Actor"))
            throw new IllegalStateException(
                "Public serialized editor did not acknowledge its real save");
          var beforeStage = actor.view().snapshot().orElseThrow();
          String sourceBeforeStage = Files.readString(binding.path());
          var staged =
              await(
                  actor.stage(
                      beforeStage.target(FieldPath.ROOT),
                      beforeStage
                          .draft()
                          .change(
                              FieldPath.ROOT.field("name"),
                              ignored ->
                                  new design.inherent.reform.edit.DraftNode.Scalar(
                                      "Imported draft")),
                      "Stage reviewed profile"));
          if (!staged
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value()
                  .name()
                  .equals("Imported draft")
              || !Files.readString(binding.path()).equals(sourceBeforeStage)
              || !owner.handle().view().value().orElseThrow().name().equals("Actor"))
            throw new IllegalStateException("Public reviewed stage saved or published its draft");
          if (!await(actor.undo())
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value()
                  .name()
                  .equals("Actor")
              || !await(actor.redo())
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value()
                  .name()
                  .equals("Imported draft"))
            throw new IllegalStateException(
                "Public reviewed stage lost its single history boundary");
          var actions =
              ownerExecutor.submit(() -> new DocumentActions<>(context, access, actor)).get();
          var input =
              new ImportInput(
                  "Reviewed external profile",
                  "{\"schemaVersion\":1,\"values\":{\"name\":\"Source import\",\"minimum\":16,\"maximum\":128,\"references\":[]}}"
                      .getBytes(StandardCharsets.UTF_8),
                  limits.maximumBytes());
          var format =
              new JsonImportFormat<>(
                  DocumentId.parse("example:versioned_json"),
                  definition,
                  JsonImportFormat.Mode.SCHEMA_ENVELOPE,
                  limits,
                  DraftLimits.CONFIGURATION,
                  ImportFormat.InvalidDraftPolicy.REFUSE);
          Path chosen = fixture.resolve("reviewed source.json");
          Files.write(chosen, input.bytes());
          var imports =
              ownerExecutor
                  .submit(
                      () ->
                          actions.imports(
                              new FileImportSource(
                                  DocumentId.parse("example:chosen_profile"),
                                  chosen,
                                  limits.maximumBytes()),
                              List.of(format)))
                  .get();
          var preview =
              await(
                  DocumentActions.result(
                      ownerExecutor
                          .submit(() -> imports.preview(EntryPoint.GUI, format.id()))
                          .get()));
          if (!preview.stageable()
              || !preview.comparisonAvailable()
              || preview.sourceSchemaVersion().orElseThrow() != 1
              || !preview.source().sha256().equals(input.sha256())
              || preview.changes().stream()
                  .noneMatch(c -> c.path().equals(FieldPath.ROOT.field("name"))))
            throw new IllegalStateException(
                "Public admitted import lost its source/comparison identity");
          await(
              DocumentActions.result(
                  ownerExecutor.submit(() -> imports.stage(EntryPoint.COMMAND, preview)).get()));
          if (!actor
                  .view()
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value()
                  .name()
                  .equals("Source import")
              || !await(actor.undo())
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value()
                  .name()
                  .equals("Imported draft")
              || !await(actor.redo())
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value()
                  .name()
                  .equals("Source import")
              || !Files.readString(binding.path()).equals(sourceBeforeStage)
              || !owner.handle().view().value().orElseThrow().name().equals("Actor"))
            throw new IllegalStateException(
                "Public admitted import lost history or performed a write");
          Files.writeString(
              binding.path(),
              "{\"schemaVersion\":1,\"values\":{\"name\":\"Remote saved\",\"minimum\":20,\"maximum\":112,\"references\":[\"missing:retained\"]}}");
          var remote = Files.readString(binding.path());
          await(actor.load());
          var conflicts = ownerExecutor.submit(() -> actions.conflicts()).get();
          var conflict =
              await(
                  DocumentActions.result(
                      ownerExecutor.submit(() -> conflicts.preview(EntryPoint.COMMAND)).get()));
          var choices = new java.util.LinkedHashMap<FieldPath, ConflictReview.Choice>();
          for (var entry : conflict.entries())
            choices.put(
                entry.path(),
                entry.path().equals(FieldPath.ROOT.field("name"))
                    ? ConflictReview.Choice.DRAFT
                    : ConflictReview.Choice.OBSERVED);
          var reconciled =
              await(
                  DocumentActions.result(
                      ownerExecutor
                          .submit(() -> conflicts.resolve(EntryPoint.GUI, conflict, choices))
                          .get()));
          var candidate =
              reconciled
                  .resolved()
                  .snapshot()
                  .orElseThrow()
                  .inspect()
                  .candidate()
                  .orElseThrow()
                  .value();
          if (!reconciled.report().valid()
              || !candidate.name().equals("Source import")
              || candidate.maximum() != 112
              || !candidate.references().equals(List.of("missing:retained"))
              || reconciled.resolved().canUndo()
              || !Files.readString(binding.path()).equals(remote)
              || !owner.handle().view().value().orElseThrow().name().equals("Actor"))
            throw new IllegalStateException(
                "Public manual conflict service lost selected input or wrote/published it");
          ownerExecutor.submit(actions::close).get();
        } finally {
          actor.close();
        }
      } finally {
        ownerExecutor.submit(context::close).get(10, TimeUnit.SECONDS);
      }
    } finally {
      List<Path> owned;
      try (var paths = Files.walk(fixture)) {
        owned = paths.sorted(Comparator.reverseOrder()).toList();
      }
      for (var path : owned) Files.delete(path);
    }
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private static void refuseGameClasspath(String name) {
    try {
      Class.forName(name, false, ReviewProfile.class.getClassLoader());
      throw new IllegalStateException("Headless fixture unexpectedly contains " + name);
    } catch (ClassNotFoundException expected) {
      // The combined JAR's core API must resolve without loading its NeoForge entry point.
    }
  }

  private ReviewProfile() {}
}
