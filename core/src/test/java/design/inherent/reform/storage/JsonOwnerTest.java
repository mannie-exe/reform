package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.JsonOwner;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonOwnerTest {
  @TempDir Path root;

  record Profile(int count) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 100));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(values -> new Profile(values.get(COUNT)))
          .field(COUNT, Profile::count)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:preferences"), 1, TYPE, () -> new Profile(1));

  private static <T> T await(CompletionStage<T> stage) throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static void waitFor(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture latch timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static DraftTree changed(JsonOwner.Observation<Profile> observed, String count) {
    return observed
        .saved()
        .draft()
        .orElseThrow()
        .change(FieldPath.ROOT.field("count"), ignored -> new DraftNode.Scalar(count));
  }

  private final class Harness implements AutoCloseable {
    final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final AtomicBoolean read = new AtomicBoolean(true), write = new AtomicBoolean(true);
    final AtomicInteger writes = new AtomicInteger();
    final AtomicBoolean pauseBefore = new AtomicBoolean(), pauseAfter = new AtomicBoolean();
    final AtomicBoolean revokeWrite = new AtomicBoolean(), revokeRead = new AtomicBoolean();
    final CountDownLatch reached = new CountDownLatch(1), release = new CountDownLatch(1);
    final OwnerContext context;
    final JsonOwner<Profile> owner;
    final JsonOwner<Profile>.Session session;
    final Path path = root.resolve("preferences.json");

    Harness() throws Exception {
      long nativeThread = nativeCall(() -> Thread.currentThread().threadId());
      var source =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(
                  StorageBinding.relative(root, "preferences.json"),
                  8192,
                  4096,
                  new OwnedFile.WriteProbe() {
                    @Override
                    public void before() {
                      assertNotEquals(nativeThread, Thread.currentThread().threadId());
                      if (pauseBefore.get()) pause();
                    }

                    @Override
                    public void run() {
                      writes.incrementAndGet();
                      if (revokeWrite.get()) write.set(false);
                      if (revokeRead.get()) read.set(false);
                      if (pauseAfter.get()) pause();
                    }

                    private void pause() {
                      reached.countDown();
                      waitFor(release);
                    }
                  }),
              JsonStore.Format.NEW_ENVELOPE,
              new JsonLimits(8192, 16, 512, 128, 64, 1024),
              DraftLimits.CONFIGURATION);
      context =
          new OwnerContext(
              nativeExecutor,
              worker,
              () -> {
                assertEquals(nativeThread, Thread.currentThread().threadId());
              },
              OwnerContext.Limits.STANDARD);
      owner = nativeCall(() -> new JsonOwner<>(context, source, 8));
      session = nativeCall(() -> owner.open(context.access(read::get, write::get)));
    }

    <T> T nativeCall(Callable<T> work) throws Exception {
      return nativeExecutor.submit(work).get(5, TimeUnit.SECONDS);
    }

    void initialize() throws Exception {
      var receipt = await(session.initialize());
      assertEquals(JsonOwner.Persistence.SAVED, receipt.persistence());
      assertEquals(JsonOwner.Application.APPLIED, receipt.application());
    }

    @Override
    public void close() {
      release.countDown();
      try {
        nativeCall(
            () -> {
              context.close();
              return null;
            });
      } catch (Exception failure) {
        throw new AssertionError("Fixture close failed", failure);
      } finally {
        worker.shutdown();
        nativeExecutor.shutdown();
        try {
          assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
          assertTrue(nativeExecutor.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }
    }
  }

  @Test
  void readDoesNotInitializeButExplicitInitializationPublishesOnTheNativeThread() throws Exception {
    try (var h = new Harness()) {
      assertEquals(JsonStore.State.MISSING, await(h.session.read()).saved().state());
      assertFalse(Files.exists(h.path));
      assertEquals(
          SettingsHandle.Availability.UNINITIALIZED, h.owner.handle().view().availability());
      h.initialize();
      var observed = await(h.session.read());
      assertEquals(new Profile(1), observed.saved().candidate().orElseThrow().value());
      assertEquals(new Profile(1), observed.effective().value().orElseThrow());
      var revision = observed.effective().revision();
      var unchanged = await(h.session.initialize());
      assertEquals(JsonOwner.Persistence.UNCHANGED, unchanged.persistence());
      assertSame(revision, h.owner.handle().view().revision());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void completeSavePublishesAndConsumesOnlyItsSubmittedProposal() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "7")));
      var result = await(h.session.commit(prepared));
      assertEquals(JsonOwner.Persistence.SAVED, result.persistence());
      assertEquals(JsonOwner.Application.APPLIED, result.application());
      assertEquals(
          new Profile(7), result.observation().orElseThrow().effective().value().orElseThrow());
      assertEquals(
          new Profile(7), await(h.session.read()).saved().candidate().orElseThrow().value());
      assertThrows(PolicyRefusal.class, () -> h.session.commit(prepared));
      assertEquals(2, h.writes.get());
    }
  }

  @Test
  void aSavedButRefusedApplicationRetriesWithoutWritingTheFileAgain() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "4")));
      h.revokeWrite.set(true);
      var refused = await(h.session.commit(prepared));
      assertEquals(JsonOwner.Persistence.SAVED, refused.persistence());
      assertEquals(JsonOwner.Application.REFUSED, refused.application());
      assertEquals("owner.permission", refused.applicationReport().issues().getFirst().code());
      assertEquals(new Profile(1), h.owner.handle().view().value().orElseThrow());
      var saved = refused.observation().orElseThrow();
      assertEquals(new Profile(4), saved.saved().candidate().orElseThrow().value());
      var text = Files.readString(h.path);
      var modified = Files.getLastModifiedTime(h.path);
      h.write.set(true);
      var applied = await(h.session.applySaved(saved));
      assertEquals(JsonOwner.Persistence.NOT_ATTEMPTED, applied.persistence());
      assertEquals(JsonOwner.Application.APPLIED, applied.application());
      assertEquals(new Profile(4), h.owner.handle().view().value().orElseThrow());
      assertEquals(text, Files.readString(h.path));
      assertEquals(modified, Files.getLastModifiedTime(h.path));
      assertEquals(2, h.writes.get());
    }
  }

  @Test
  void closureAfterReplacementKeepsTheSavedFactAndDrainsTheActualWrite() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "5")));
      h.pauseAfter.set(true);
      var result = h.session.commit(prepared);
      waitFor(h.reached);
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      assertFalse(result.toCompletableFuture().isDone());
      h.release.countDown();
      var receipt = await(result);
      assertEquals(JsonOwner.Persistence.SAVED, receipt.persistence());
      assertEquals(JsonOwner.Application.UNAVAILABLE, receipt.application());
      assertTrue(receipt.observation().isEmpty());
      assertTrue(Files.readString(h.path).contains("\"count\": 5"));
      assertEquals(SettingsHandle.Availability.CLOSED, h.owner.handle().view().availability());
    }
  }

  @Test
  void closureBeforeReplacementRefusesTheWriteAndRemovesItsTemporaryFile() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var original = Files.readString(h.path);
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "5")));
      h.pauseBefore.set(true);
      var result = h.session.commit(prepared);
      waitFor(h.reached);
      h.nativeCall(
          () -> {
            h.context.close();
            return null;
          });
      h.release.countDown();
      assertEquals(JsonOwner.Persistence.REFUSED, await(result).persistence());
      assertEquals(original, Files.readString(h.path));
      try (var entries = Files.list(root)) {
        assertEquals(1, entries.count());
      }
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void actorSessionsShareOneLaneThroughPublicationRatherThanJustOneFileLock() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var second = h.nativeCall(() -> h.owner.open(h.context.access(() -> true, () -> true)));
      var firstObserved = await(h.session.read());
      var secondObserved = await(second.read());
      var first = await(h.session.prepare(firstObserved, changed(firstObserved, "3")));
      var next = await(second.prepare(secondObserved, changed(secondObserved, "6")));
      h.pauseAfter.set(true);
      var committing = h.session.commit(first);
      waitFor(h.reached);
      var queued = second.commit(next);
      assertFalse(queued.toCompletableFuture().isDone());
      h.release.countDown();
      assertEquals(JsonOwner.Application.APPLIED, await(committing).application());
      var stale = await(queued);
      assertEquals(JsonOwner.Persistence.NOT_ATTEMPTED, stale.persistence());
      assertEquals("owner.revision", stale.persistenceReport().issues().getFirst().code());
      assertEquals(new Profile(3), h.owner.handle().view().value().orElseThrow());
      assertEquals(2, h.writes.get());
    }
  }

  @Test
  void invalidAndRevokedProposalsDoNotTouchDisk() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var text = Files.readString(h.path);
      var observed = await(h.session.read());
      assertThrows(
          ExecutionException.class,
          () -> await(h.session.prepare(observed, changed(observed, "no number"))));
      var prepared = await(h.session.prepare(observed, changed(observed, "8")));
      h.write.set(false);
      var refused = await(h.session.commit(prepared));
      assertEquals(JsonOwner.Persistence.NOT_ATTEMPTED, refused.persistence());
      assertEquals("owner.permission", refused.persistenceReport().issues().getFirst().code());
      assertEquals(text, Files.readString(h.path));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void revokedReadPermissionHidesNewlyObservedValuesWithoutErasingTheReceipt() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "9")));
      h.revokeRead.set(true);
      h.revokeWrite.set(true);
      var receipt = await(h.session.commit(prepared));
      assertEquals(JsonOwner.Persistence.SAVED, receipt.persistence());
      assertTrue(receipt.savedRevision().isPresent());
      assertTrue(receipt.observation().isEmpty());
      assertEquals(JsonOwner.Application.REFUSED, receipt.application());
      assertThrows(ExecutionException.class, () -> await(h.session.read()));
    }
  }

  @Test
  void failedReloadRetainsOnlyThePreviouslyQualifiedRuntimeValue() throws Exception {
    try (var h = new Harness()) {
      Files.writeString(h.path, "not json");
      var broken = await(h.session.initialize());
      assertEquals(JsonOwner.Persistence.UNCHANGED, broken.persistence());
      assertEquals(JsonOwner.Application.REFUSED, broken.application());
      assertTrue(h.owner.handle().view().value().isEmpty());
      assertEquals("not json", Files.readString(h.path));
      Files.delete(h.path);
      h.initialize();
      Files.writeString(h.path, "still not json");
      var reloaded = await(h.session.initialize());
      assertEquals(JsonOwner.Application.REFUSED, reloaded.application());
      assertEquals(new Profile(1), h.owner.handle().view().value().orElseThrow());
      assertFalse(h.owner.handle().view().observationReport().valid());
      assertEquals("still not json", Files.readString(h.path));
    }
  }

  @Test
  void applyOnlyRefusesChangedSavedBytesAndCanReviewTheNewObservation() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var old = await(h.session.read());
      Files.writeString(h.path, "{\"schemaVersion\":1,\"values\":{\"count\":12}}");
      var changed = await(h.session.applySaved(old));
      assertEquals(JsonOwner.Persistence.NOT_ATTEMPTED, changed.persistence());
      assertEquals(JsonOwner.Application.REFUSED, changed.application());
      assertEquals("owner.saved_revision", changed.applicationReport().issues().getFirst().code());
      assertEquals(new Profile(1), h.owner.handle().view().value().orElseThrow());
      assertEquals(
          JsonOwner.Application.APPLIED,
          await(h.session.applySaved(changed.observation().orElseThrow())).application());
      assertEquals(new Profile(12), h.owner.handle().view().value().orElseThrow());
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void queuedWorkerClosureCompletesTheCommitAsUnattemptedWithoutWaitingForThatExecutor()
      throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "10")));
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.worker.submit(
          () -> {
            entered.countDown();
            waitFor(release);
          });
      waitFor(entered);
      try {
        var commit = h.session.commit(prepared);
        h.nativeCall(
            () -> {
              h.context.close();
              return null;
            });
        assertEquals(JsonOwner.Persistence.NOT_ATTEMPTED, await(commit).persistence());
        assertEquals(1, h.writes.get());
        assertTrue(Files.readString(h.path).contains("\"count\": 1"));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void anExternalFileChangeIsObservedAsAConflictWithoutPublishingOrOverwritingIt()
      throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var observed = await(h.session.read());
      var prepared = await(h.session.prepare(observed, changed(observed, "9")));
      var external = "{\"schemaVersion\":1,\"values\":{\"count\":13}}";
      Files.writeString(h.path, external);
      var result = await(h.session.commit(prepared));
      assertEquals(JsonOwner.Persistence.REFUSED, result.persistence());
      assertNotEquals(JsonOwner.Application.APPLIED, result.application());
      assertEquals(new Profile(1), h.owner.handle().view().value().orElseThrow());
      assertEquals(
          new Profile(13),
          result.observation().orElseThrow().saved().candidate().orElseThrow().value());
      assertEquals(external, Files.readString(h.path));
      assertEquals(1, h.writes.get());
    }
  }

  @Test
  void observationsAndProposalsAreBoundToTheActualSession() throws Exception {
    try (var h = new Harness()) {
      h.initialize();
      var second = h.nativeCall(() -> h.owner.open(h.context.access(() -> true, () -> true)));
      var observed = await(h.session.read());
      assertThrows(
          ExecutionException.class, () -> await(second.prepare(observed, changed(observed, "3"))));
      var prepared = await(h.session.prepare(observed, changed(observed, "3")));
      assertThrows(PolicyRefusal.class, () -> second.commit(prepared));
      h.session.close();
      assertEquals(
          JsonOwner.Persistence.NOT_ATTEMPTED, await(h.session.commit(prepared)).persistence());
      assertEquals(1, h.writes.get());
    }
  }
}
