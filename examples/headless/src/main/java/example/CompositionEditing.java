package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormModel;
import design.inherent.reform.reference.CollectionSelection;
import design.inherent.reform.reference.ReferenceActions;
import design.inherent.reform.reference.ReferenceBrowser;
import design.inherent.reform.reference.ReferenceIndex;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceQuery;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Public composition-like primitives, not a donor compiler or resource/world publication. */
final class CompositionEditing {
  private interface Donor {}

  private record Asset(String key) implements Donor {}

  private record Inline(String note) implements Donor {}

  private record Role(Donor donor, List<Integer> weights) {}

  private record Bounds(int minimum, int maximum) {}

  private record Profile(Map<String, Role> roles, Bounds bounds, Optional<String> optionalDonor) {}

  private static final DocumentId DONORS = DocumentId.parse("example:donors");
  private static final ValueType<String> KEYS = ValueTypes.text(128);
  private static final JsonLimits JSON = new JsonLimits(32768, 64, 4096, 8192, 128, 4096);
  private static final FieldKey<String> KEY =
      FieldKey.of("key", ValueTypes.reference(KEYS, DONORS));
  private static final FieldKey<String> NOTE = FieldKey.of("note", ValueTypes.text(128));
  private static final ValueType<Donor> DONOR =
      TaggedType.<Donor>builder("kind", "settings")
          .variant(
              "asset",
              Asset.class,
              RecordType.<Asset>builder(v -> new Asset(v.get(KEY))).field(KEY, Asset::key).build())
          .variant(
              "inline",
              Inline.class,
              RecordType.<Inline>builder(v -> new Inline(v.get(NOTE)))
                  .field(NOTE, Inline::note)
                  .build())
          .build();
  private static final FieldKey<Donor> DONOR_FIELD = FieldKey.of("donor", DONOR);
  private static final FieldKey<List<Integer>> WEIGHTS =
      FieldKey.of("weights", ValueTypes.list(ValueTypes.integer(0, 100), 8));
  private static final ValueType<Role> ROLE =
      RecordType.<Role>builder(v -> new Role(v.get(DONOR_FIELD), v.get(WEIGHTS)))
          .field(DONOR_FIELD, Role::donor)
          .field(WEIGHTS, Role::weights)
          .build();
  private static final FieldKey<Integer> MINIMUM =
      FieldKey.of("minimum", ValueTypes.integer(0, 100));
  private static final FieldKey<Integer> MAXIMUM =
      FieldKey.of("maximum", ValueTypes.integer(0, 100));
  private static final ValueType<Bounds> BOUNDS =
      RecordType.<Bounds>builder(v -> new Bounds(v.get(MINIMUM), v.get(MAXIMUM)))
          .field(MINIMUM, Bounds::minimum)
          .field(MAXIMUM, Bounds::maximum)
          .build();
  private static final FieldKey<Map<String, Role>> ROLES =
      FieldKey.of("roles", ValueTypes.objectMap(ValueTypes.text(64), ROLE, 8));
  private static final FieldKey<Bounds> BOUNDS_FIELD = FieldKey.of("bounds", BOUNDS);
  private static final FieldKey<Optional<String>> OPTIONAL =
      FieldKey.of(
          "optional_donor",
          ValueTypes.optional(
              ValueTypes.reference(KEYS, DONORS), ValueShape.OptionalEncoding.SINGLETON_LIST));
  private static final ValueType<Profile> PROFILE =
      RecordType.<Profile>builder(
              v -> new Profile(v.get(ROLES), v.get(BOUNDS_FIELD), v.get(OPTIONAL)))
          .field(ROLES, Profile::roles)
          .field(BOUNDS_FIELD, Profile::bounds)
          .field(OPTIONAL, Profile::optionalDonor)
          .build();
  private static final FieldPath MIN_PATH = FieldPath.ROOT.field("bounds").field("minimum");
  private static final FieldPath MAX_PATH = FieldPath.ROOT.field("bounds").field("maximum");
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("example:composition"),
          1,
          PROFILE,
          () ->
              new Profile(
                  Map.of("anchor", new Role(new Asset("donor:house"), List.of(1, 2))),
                  new Bounds(1, 4),
                  Optional.of("future:donor")),
          profile -> {
            if (profile.bounds().minimum() > profile.bounds().maximum())
              return new ValidationReport(
                  List.of(
                      new Issue(
                          "bounds.order",
                          Issue.Severity.ERROR,
                          MIN_PATH,
                          List.of(MAX_PATH),
                          Map.of(),
                          "Minimum must not exceed maximum")),
                  true);
            for (var role : profile.roles().values())
              if (role.donor() instanceof Asset asset && !asset.key().equals("donor:house"))
                return new ValidationReport(
                    List.of(
                        Issue.error(
                            "donor.unknown",
                            FieldPath.ROOT.field("roles"),
                            "Choose a known required donor")),
                    true);
            return ValidationReport.VALID;
          });

  static void verify() throws Exception {
    try (var h = new Harness()) {
      h.run(() -> h.actions.ensureLoaded(EntryPoint.GUI));
      String original = Files.readString(h.file);
      require(
          h.run(() -> h.actions.validate(EntryPoint.COMMAND)).report().valid(),
          "Initial combined profile rejected");
      var page = h.run(() -> h.references.search(EntryPoint.GUI, new ReferenceQuery("house", 4)));
      require(page.choices().size() == 1 && page.exhausted(), "Public reference search failed");
      var browser = h.nativeCall(() -> new ReferenceBrowser<>(h.references));
      var search =
          h.nativeCall(() -> browser.search(EntryPoint.GUI, new ReferenceQuery("house", 4)));
      await(search.operation().completion());
      require(h.nativeCall(() -> browser.accept(search)), "Public browser rejected its own search");
      require(
          h.nativeCall(() -> browser.page().orElseThrow()).equals(page),
          "Public browser changed page evidence");
      h.nativeCall(
          () -> {
            browser.close();
            return null;
          });
      require(h.references.isOpen(), "Public browser closed its borrowed source");
      var missing = h.run(() -> h.references.resolve(EntryPoint.GUI, "future:donor"));
      require(
          missing.status() == ReferenceResolution.Status.UNKNOWN_REFERENCE,
          "Unknown donor was resolved");
      h.nativeCall(
          () ->
              h.references.invalidate(
                  "missing",
                  ReferencePage.Availability.MISSING_DEPENDENCY,
                  "Optional donor library absent"));
      require(
          h.run(() -> h.references.resolve(EntryPoint.GUI, "future:donor")).status()
              == ReferenceResolution.Status.MISSING_DEPENDENCY,
          "Absent dependency lost its distinct state");
      require(
          h.actions
              .view()
              .snapshot()
              .orElseThrow()
              .inspect()
              .candidate()
              .orElseThrow()
              .value()
              .optionalDonor()
              .orElseThrow()
              .equals("future:donor"),
          "Optional unresolved raw value was lost");

      h.edit(MIN_PATH, new DraftEdit.SetRaw("8"));
      var invalid = h.run(() -> h.actions.validate(EntryPoint.GUI));
      require(
          !invalid.report().valid()
              && invalid.report().issues().getFirst().path().equals(MIN_PATH)
              && invalid.report().issues().getFirst().related().equals(List.of(MAX_PATH)),
          "Cross-field diagnostics lost their targets");
      h.run(() -> h.actions.undo(EntryPoint.COMMAND));

      var roles =
          (DraftNode.Mapping)
              h.actions.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field("roles"));
      var role =
          FieldPath.ROOT
              .field("roles")
              .row(roles.rows().getFirst().id())
              .append(FieldPath.Part.VALUE);
      var roleRow = FieldPath.ROOT.field("roles").row(roles.rows().getFirst().id());
      var catalog =
          new FormCatalog(
              List.of(
                  new FormCatalog.Section(
                      FieldPath.ROOT.field("roles"),
                      "example.roles",
                      "Roles",
                      "Authored roles and donor choices"),
                  new FormCatalog.Section(
                      FieldPath.ROOT.field("bounds"),
                      "example.bounds",
                      "Bounds",
                      "Complete profile limits")));
      var navigation =
          new FormCatalog.Navigation(
              catalog.snapshot(
                  new FormModel(PROFILE, h.actions.view().snapshot().orElseThrow().draft())));
      navigation.select(roleRow);
      require(
          navigation.selected().label().equals("anchor")
              && navigation.selected().path().equals(roleRow),
          "Public catalog lost its actual map-row identity or key caption");
      var donor = role.field("donor");
      var key = donor.variant("asset").field("key");
      h.edit(key, new DraftEdit.SetRaw("missing:required"));
      require(
          !h.run(() -> h.actions.validate(EntryPoint.COMMAND)).report().valid(),
          "Required donor validator did not run");
      navigation.update(
          catalog.snapshot(
              new FormModel(PROFILE, h.actions.view().snapshot().orElseThrow().draft())));
      require(
          navigation.selected().path().equals(roleRow)
              && ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(key))
                  .raw()
                  .equals("missing:required")
              && Files.readString(h.file).equals(original),
          "Catalog inspection repaired, persisted or navigated away from an invalid role");
      h.edit(donor, new DraftEdit.SelectVariant("inline"));
      require(
          h.run(() -> h.actions.validate(EntryPoint.COMMAND)).report().valid(),
          "Inactive donor blocked another branch");
      h.edit(donor, new DraftEdit.SelectVariant("asset"));
      require(
          ((DraftNode.Scalar) h.actions.view().snapshot().orElseThrow().draft().at(key))
              .raw()
              .equals("missing:required"),
          "Branch switch lost invalid inactive reference");
      h.edit(key, new DraftEdit.SetRaw("donor:house"));
      var weights = role.field("weights");
      h.edit(weights, new DraftEdit.InsertRow(Optional.empty()));
      var added =
          ((DraftNode.Sequence) h.actions.view().snapshot().orElseThrow().draft().at(weights))
              .rows()
              .getLast();
      require(
          !h.run(() -> h.actions.validate(EntryPoint.GUI)).report().valid(),
          "Incomplete new row silently became a value");
      h.edit(weights.row(added.id()), new DraftEdit.SetRaw("3"));
      var beforeSelection =
          (DraftNode.Sequence) h.actions.view().snapshot().orElseThrow().draft().at(weights);
      var selection = h.nativeCall(() -> new CollectionSelection<>(h.actions, weights));
      var selectedEdit =
          h.nativeCall(
              () ->
                  selection.change(
                      EntryPoint.GUI,
                      selection.view().target(),
                      new DraftEdit.ChangeScalarRows(
                          List.of("4", "5"), Set.of(beforeSelection.rows().getFirst().id()))));
      await(selectedEdit.operation().completion());
      require(
          h.nativeCall(() -> selection.accept(selectedEdit))
              == CollectionSelection.Acceptance.ACCEPTED,
          "Public collection controller did not accept its actual edit");
      h.nativeCall(
          () -> {
            selection.close();
            return null;
          });
      var selected =
          (DraftNode.Sequence) h.actions.view().snapshot().orElseThrow().draft().at(weights);
      require(
          h.actions.view().validation().valid()
              && selected.rows().stream()
                  .map(row -> ((DraftNode.Scalar) row.value()).raw())
                  .toList()
                  .equals(List.of("2", "3", "4", "5"))
              && selected.rows().getFirst().id().equals(beforeSelection.rows().get(1).id()),
          "Compound scalar selection lost its retained row or complete owner validation");
      h.run(() -> h.actions.undo(EntryPoint.GUI));
      require(
          h.actions.view().snapshot().orElseThrow().draft().at(weights).equals(beforeSelection)
              && Files.readString(h.file).equals(original),
          "One undo failed to restore the complete selection without persistence");
      h.edit(
          FieldPath.ROOT
              .field("roles")
              .row(roles.rows().getFirst().id())
              .append(FieldPath.Part.KEY),
          new DraftEdit.SetRaw("renamed"));
      var renamedCatalog =
          catalog.snapshot(
              new FormModel(PROFILE, h.actions.view().snapshot().orElseThrow().draft()));
      navigation.update(renamedCatalog);
      require(
          navigation.selected().path().equals(roleRow)
              && navigation.selected().label().equals("renamed")
              && renamedCatalog.search("renamed").stream()
                  .map(FormCatalog.Entry::path)
                  .toList()
                  .equals(List.of(roleRow))
              && renamedCatalog.breadcrumbs(roleRow).stream()
                  .map(FormCatalog.Entry::path)
                  .toList()
                  .equals(List.of(FieldPath.ROOT, FieldPath.ROOT.field("roles"), roleRow)),
          "Renaming a public catalog row lost selection, search or breadcrumbs");
      var comparison = h.run(() -> h.actions.compare(EntryPoint.COMMAND));
      var inspection = h.run(() -> h.actions.explain(EntryPoint.GUI));
      var review = h.run(() -> h.actions.review(EntryPoint.GUI));
      var exported = h.run(() -> h.actions.export(EntryPoint.COMMAND));
      var roleDetails =
          review.entries().stream()
              .filter(entry -> entry.selector().equals("roles"))
              .findFirst()
              .orElseThrow();
      require(
          review.target().equals(comparison.draft())
              && review.target().equals(exported.draft())
              && review.source().location().orElseThrow().equals(h.file.toString())
              && roleDetails.wholeValue()
              && roleDetails.draft().contains("renamed")
              && roleDetails.baseline().contains("anchor")
              && roleDetails.effective().orElseThrow().contains("anchor"),
          "Public identified review mixed source revisions or invented effective row alignment");
      require(
          comparison.report().valid()
              && comparison.authoredChanges()
              && !comparison.changes().isEmpty(),
          "Combined comparison missed changes");
      require(
          inspection.provenance().kind().equals("json")
              && inspection.provenance().location().orElseThrow().equals(h.file.toString()),
          "Source provenance changed");
      require(
          inspection.effective().orElseThrow().value().orElseThrow().equals(DEFINITION.defaults()),
          "Draft changed effective state before Save");
      require(
          exported.draft().session().equals(inspection.snapshot().session())
              && exported.draft().version() == inspection.snapshot().version(),
          "Export lost actual editor identity");
      var candidate = JsonInput.read(exported.text(), JSON).decode(PROFILE).value().orElseThrow();
      require(
          candidate.roles().get("renamed").weights().equals(List.of(1, 2, 3)),
          "Export missed structural edits");
      require(
          Files.readString(h.file).equals(original),
          "Inspection/validation/export wrote the profile");
      var saved = h.run(() -> h.actions.save(EntryPoint.GUI)).receipt().orElseThrow();
      require(
          saved.persistence() == CommitReceipt.Persistence.SAVED
              && saved.application() == CommitReceipt.Application.APPLIED,
          "Explicit profile save failed");
      require(
          JsonInput.read(Files.readString(h.file), JSON)
              .decode(PROFILE)
              .value()
              .orElseThrow()
              .equals(candidate),
          "Stored bare profile differs from candidate");
      require(
          h.owner.handle().view().value().orElseThrow().equals(candidate),
          "JSON effective snapshot differs from saved profile");
      h.run(() -> h.actions.load(EntryPoint.COMMAND));
      require(!h.actions.view().dirty(), "Reopened compound profile is dirty");
      try (var files = Files.list(h.directory)) {
        require(files.count() == 1, "Profile editing published unexpected resources");
      }
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final Path directory, file;
    final OwnerContext context;
    final JsonOwner<Profile> owner;
    final DocumentActions<Profile> actions;
    final ReferenceActions<String, String> references;

    Harness() throws Exception {
      directory = Files.createTempDirectory("reform-public-composition-");
      file = directory.resolve("profile.json");
      OwnerContext created = null;
      try {
        long nativeThread = nativeCall(() -> Thread.currentThread().threadId());
        context =
            new OwnerContext(
                nativeExecutor,
                workers,
                () ->
                    require(
                        Thread.currentThread().threadId() == nativeThread, "Wrong owner thread"),
                OwnerContext.Limits.STANDARD);
        created = context;
        var store =
            new JsonStore<>(
                DEFINITION,
                new OwnedFile(StorageBinding.relative(directory, "profile.json"), 32768),
                JsonStore.Format.EXISTING_BARE,
                JSON,
                DraftLimits.CONFIGURATION);
        owner = nativeCall(() -> new JsonOwner<>(context, store, 16));
        var access = nativeCall(() -> context.access(() -> true, () -> true));
        var session = nativeCall(() -> owner.open(access));
        await(session.initialize());
        actions =
            nativeCall(
                () ->
                    new DocumentActions<>(
                        context,
                        access,
                        new EditorActor<>(
                            context, session, DEFINITION, JSON, DraftLimits.CONFIGURATION, 32)));
        references =
            nativeCall(
                () ->
                    new ReferenceActions<>(
                        context,
                        access,
                        DONORS,
                        KEYS,
                        SnapshotPolicy.immutable(),
                        ReferenceLimits.STANDARD));
        nativeCall(
            () ->
                references.bind(
                    "fixture",
                    stamp ->
                        new ReferenceIndex<>(
                            stamp,
                            KEYS,
                            SnapshotPolicy.immutable(),
                            ReferenceLimits.STANDARD,
                            List.of(
                                new ReferenceIndex.Entry<>(
                                    "donor:house",
                                    "fixture-house",
                                    "House",
                                    "house",
                                    Map.of("source", "fixture"))))));
      } catch (Exception | Error failure) {
        try {
          cleanup(created);
        } catch (RuntimeException | Error cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(10, TimeUnit.SECONDS);
    }

    <T> T run(Callable<OperationHandle<T>> request) throws Exception {
      return await(DocumentActions.result(nativeCall(request)));
    }

    void edit(FieldPath path, DraftEdit.Action action) throws Exception {
      var target = actions.view().snapshot().orElseThrow().target(path);
      run(() -> actions.edit(EntryPoint.GUI, new DraftEdit(target, action)));
    }

    @Override
    public void close() {
      cleanup(context);
    }

    private void cleanup(OwnerContext managed) {
      try {
        if (managed != null)
          nativeCall(
              () -> {
                managed.close();
                return null;
              });
      } catch (Exception failure) {
        throw new AssertionError("Could not close the fixture context", failure);
      } finally {
        workers.shutdownNow();
        nativeExecutor.shutdownNow();
        try {
          require(
              workers.awaitTermination(10, TimeUnit.SECONDS)
                  && nativeExecutor.awaitTermination(10, TimeUnit.SECONDS),
              "Fixture worker did not terminate");
          Files.deleteIfExists(file);
          Files.delete(directory);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while closing the fixture", interrupted);
        } catch (java.io.IOException failure) {
          throw new AssertionError("Could not remove the fixture files", failure);
        }
      }
    }
  }

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  private CompositionEditing() {}
}
