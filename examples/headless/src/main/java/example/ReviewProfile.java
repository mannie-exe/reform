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
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.RuntimePolicy;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public packaged API only. This is a headless fixture, not a production world/profile compiler.
 */
public final class ReviewProfile {
  private record Profile(String name, int minimum, int maximum, List<String> references) {}

  public static void main(String[] args) throws Exception {
    refuseGameClasspath("net.minecraft.world.level.Level");
    refuseGameClasspath("net.neoforged.fml.ModContainer");
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
    if (!next.savedRevision().equals("saved-3"))
      throw new IllegalStateException("Review retained the stale saved revision");
    session.reload(definition.defaults(), "reloaded");
    if (session.acknowledge(next, next.candidate().value(), "late")
        != EditSession.Acknowledgement.STALE)
      throw new IllegalStateException("Old acknowledgement crossed a reload");
    session.close();
    verifyStorage(definition, limits);
    System.out.println(
        "Packaged public consumer passed: decode/draft/history/late-save/conflict/reload/real-file/publication; game classes absent");
  }

  private static void verifyStorage(DocumentDefinition<Profile> definition, JsonLimits limits)
      throws Exception {
    Path fixture = Files.createTempDirectory("reform-public-");
    try {
      var binding = StorageBinding.newJson(fixture, definition.id());
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(binding, limits.maximumBytes()),
              JsonStore.Format.NEW_ENVELOPE,
              limits,
              DraftLimits.CONFIGURATION);
      if (store.read().state() != JsonStore.State.MISSING || Files.exists(binding.path()))
        throw new IllegalStateException("Public read initialized storage implicitly");
      var initialized = store.initializeDefaultsIfMissing();
      if (initialized.persistence().state() != OwnedFile.Persistence.SAVED)
        throw new IllegalStateException("Public factory defaults did not initialize");
      var baseline = initialized.observation().orElseThrow();
      long ownerThread = Thread.currentThread().threadId();
      var runtime =
          new RuntimePolicy<>(
              definition,
              () -> {
                if (Thread.currentThread().threadId() != ownerThread)
                  throw new IllegalStateException("Wrong publication thread");
              });
      runtime
          .prepare(
              runtime.handle().view().revision(), baseline.candidate().orElseThrow(), () -> true)
          .publish(() -> true);
      var session =
          EditSession.fromDraft(
              definition,
              UUID.randomUUID(),
              baseline.source().revision(),
              baseline.draft().orElseThrow(),
              limits,
              DraftLimits.CONFIGURATION);
      session.setRaw(FieldPath.ROOT.field("name"), "Stored");
      var submission = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submission);
      var written = store.write(baseline, submission.candidate());
      if (written.persistence().state() != OwnedFile.Persistence.SAVED)
        throw new IllegalStateException("Public file submission did not persist");
      session.setRaw(FieldPath.ROOT.field("name"), "Later");
      var observed = written.observation().orElseThrow();
      var authorized = new AtomicBoolean(true);
      var publication =
          runtime.prepare(
              runtime.handle().view().revision(),
              observed.candidate().orElseThrow(),
              authorized::get);
      authorized.set(false);
      try {
        publication.publish(authorized::get);
        throw new IllegalStateException("Revoked publication was accepted");
      } catch (PolicyRefusal refused) {
        if (!refused.report().issues().getFirst().code().equals("owner.permission")) throw refused;
      }
      if (!runtime.handle().view().value().orElseThrow().name().equals("Default"))
        throw new IllegalStateException("Refused application changed effective values");
      authorized.set(true);
      var retrySource = store.read();
      runtime
          .prepare(
              runtime.handle().view().revision(),
              retrySource.candidate().orElseThrow(),
              authorized::get)
          .publish(authorized::get);
      if (!runtime.handle().view().value().orElseThrow().name().equals("Stored")
          || !store.read().source().revision().equals(retrySource.source().revision()))
        throw new IllegalStateException("Application-only retry lost saved/effective separation");
      if (session.acknowledge(
                  submission, observed.draft().orElseThrow(), observed.source().revision())
              != EditSession.Acknowledgement.ACCEPTED
          || !session.snapshot().inspect().candidate().orElseThrow().value().name().equals("Later")
          || !store.read().candidate().orElseThrow().value().name().equals("Stored"))
        throw new IllegalStateException("Public file receipt lost saved/draft distinction");
      session.close();
      runtime.close();
    } finally {
      List<Path> owned;
      try (var paths = Files.walk(fixture)) {
        owned = paths.sorted(Comparator.reverseOrder()).toList();
      }
      for (var path : owned) Files.delete(path);
    }
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
