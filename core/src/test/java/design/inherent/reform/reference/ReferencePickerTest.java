package design.inherent.reform.reference;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.*;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual file actor/reference services on distinct owner and worker threads, no native UI claim.
 */
class ReferencePickerTest {
  @TempDir Path root;

  record Profile(String models, String artwork, int count) {}

  static final DocumentId SOURCE = DocumentId.parse("fixture:picker_profiles");
  static final FieldKey<String> MODELS =
      FieldKey.of("models", ValueTypes.reference(ValueTypes.text(128), SOURCE));
  static final FieldKey<String> ARTWORK = FieldKey.of("artwork", MODELS.type());
  static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:picker_document"),
          1,
          RecordType.<Profile>builder(v -> new Profile(v.get(MODELS), v.get(ARTWORK), v.get(COUNT)))
              .field(MODELS, Profile::models)
              .field(ARTWORK, Profile::artwork)
              .field(COUNT, Profile::count)
              .build(),
          () -> new Profile("fixture:a", "fixture:a", 1));
  static final PickerRole<Profile> MODEL_ROLE =
      new PickerRole<>("models", FieldPath.ROOT.field("models"));
  static final PickerRole<Profile> ART_ROLE =
      new PickerRole<>("artwork", FieldPath.ROOT.field("artwork"));

  static void done(OperationHandle<?> handle) throws Exception {
    handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  static OperationHandle<?> pending(UiActionResult result) {
    return assertInstanceOf(UiActionResult.Pending.class, result).operation();
  }

  static String code(UiActionResult result) {
    return assertInstanceOf(UiActionResult.Refused.class, result)
        .report()
        .issues()
        .getFirst()
        .code();
  }

  final class Harness implements AutoCloseable {
    final ExecutorService ownerThread = Executors.newSingleThreadExecutor(),
        workers = Executors.newFixedThreadPool(2);
    final AtomicBoolean read = new AtomicBoolean(true),
        sourceRead = new AtomicBoolean(true),
        write = new AtomicBoolean(true);
    final AtomicLong now = new AtomicLong();
    final AtomicInteger factories = new AtomicInteger(),
        searches = new AtomicInteger(),
        lookups = new AtomicInteger();
    final AtomicReference<Consumer<String>> beforeLookup = new AtomicReference<>(ignored -> {});
    final AtomicReference<Consumer<ReferenceQuery>> beforeSearch =
        new AtomicReference<>(ignored -> {});
    final AtomicReference<Runnable> beforeSourceRead = new AtomicReference<>();
    final AtomicReference<Optional<FieldFrontend.Input>> input =
        new AtomicReference<>(Optional.empty());
    final OwnerContext context;
    final FileOwner<Profile> owner;
    final DocumentActions<Profile> actions;
    final ReferenceActions<String, List<String>> references;
    final UiScope scope;
    final ReferencePicker<Profile, List<String>> picker;
    final Path file;
    final byte[] original;
    final java.nio.file.attribute.BasicFileAttributes originalAttributes;

    Harness() throws Exception {
      this(UiLimits.STANDARD);
    }

    Harness(UiLimits limits) throws Exception {
      this(limits, ReferencePicker.Policy.STANDARD);
    }

    Harness(UiLimits limits, ReferencePicker.Policy policy) throws Exception {
      file = root.resolve(UUID.randomUUID() + ".json");
      long actualThread = call(() -> Thread.currentThread().threadId());
      context =
          new OwnerContext(
              ownerThread,
              workers,
              () -> assertEquals(actualThread, Thread.currentThread().threadId()),
              OwnerContext.Limits.STANDARD);
      var json = new JsonLimits(32768, 64, 4096, 1024, 128, 1024);
      var store =
          new JsonStore<>(
              DEFINITION,
              new OwnedFile(
                  StorageBinding.relative(root, file.getFileName().toString()), 32768, 4096),
              JsonStore.Format.NEW_ENVELOPE,
              json,
              DraftLimits.CONFIGURATION);
      owner = call(() -> new FileOwner<>(context, store, 16));
      var access = call(() -> context.access(read::get, write::get));
      var session = call(() -> owner.open(access));
      session.initialize().toCompletableFuture().get(5, TimeUnit.SECONDS);
      actions =
          call(
              () ->
                  new DocumentActions<>(
                      context,
                      access,
                      new EditorActor<>(
                          context, session, DEFINITION, json, DraftLimits.CONFIGURATION, 32)));
      done(call(() -> actions.load(OperationDescriptor.EntryPoint.GUI)));
      original = Files.readAllBytes(file);
      originalAttributes =
          Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
      references =
          call(
              () ->
                  new ReferenceActions<>(
                      context,
                      context.access(
                          () -> {
                            var work = beforeSourceRead.getAndSet(null);
                            if (work != null) work.run();
                            return sourceRead.get();
                          },
                          () -> false),
                      SOURCE,
                      ValueTypes.text(128),
                      ValueTypes.list(ValueTypes.text(128), 16).snapshotPolicy(),
                      ReferenceIndexTest.LIMITS));
      bind();
      scope = call(() -> UiScope.borrow(context, limits));
      var frontend =
          new FieldFrontend() {
            public Optional<Input> input() {
              return input.get();
            }

            public UiAvailability editing() {
              return UiAvailability.READY;
            }

            public UiAvailability submission() {
              return UiAvailability.READY;
            }
          };
      picker =
          call(
              () ->
                  ReferencePicker.open(
                      scope,
                      actions,
                      SOURCE,
                      () -> {
                        factories.incrementAndGet();
                        return references;
                      },
                      List.of(MODEL_ROLE, ART_ROLE),
                      Map.of("models", frontend),
                      policy,
                      now::get));
    }

    <R> R call(Callable<R> action) throws Exception {
      return ownerThread.submit(action).get(5, TimeUnit.SECONDS);
    }

    void bind() throws Exception {
      call(
          () ->
              references.bind(
                  "same-label",
                  stamp -> {
                    var index =
                        ReferenceIndexTest.index(
                            stamp,
                            List.of(
                                ReferenceIndexTest.entry("fixture:a", "Alpha"),
                                ReferenceIndexTest.entry("fixture:b", "Beta"),
                                new ReferenceIndex.Entry<>(
                                    "fixture:empty",
                                    List.of(),
                                    "Inherited",
                                    "Inherited",
                                    Map.of("contributions", "0"))));
                    return new ReferenceResolver<String, List<String>>() {
                      public ReferenceStamp stamp() {
                        return index.stamp();
                      }

                      public ReferenceResolution<List<String>> resolve(String key) {
                        lookups.incrementAndGet();
                        beforeLookup.get().accept(key);
                        return index.resolve(key);
                      }

                      public ReferencePage<String> search(ReferenceQuery query) {
                        searches.incrementAndGet();
                        beforeSearch.get().accept(query);
                        return index.search(query);
                      }
                    };
                  }));
    }

    PickerState snapshot() throws Exception {
      return call(
          () -> {
            scope.refreshObservations();
            return picker.state().snapshot().value();
          });
    }

    PickerState update() throws Exception {
      return call(
          () -> {
            picker.update();
            scope.refreshObservations();
            return picker.state().snapshot().value();
          });
    }

    void admit() throws Exception {
      done(pending(call(() -> picker.admit().invoke(null))));
      update();
    }

    void inspect(String key) throws Exception {
      done(pending(call(() -> picker.inspect().invoke(key))));
      update();
    }

    PickerState.Option option(String key) throws Exception {
      var s = snapshot();
      return new PickerState.Option(s.stamp().orElseThrow(), s.queryGeneration(), key);
    }

    String raw(String field) throws Exception {
      return call(
          () ->
              ((DraftNode.Scalar)
                      actions
                          .view()
                          .snapshot()
                          .orElseThrow()
                          .draft()
                          .at(FieldPath.ROOT.field(field)))
                  .raw());
    }

    void edit(String field, String value) throws Exception {
      done(
          call(
              () ->
                  actions.edit(
                      OperationDescriptor.EntryPoint.GUI,
                      new DraftEdit(
                          actions
                              .view()
                              .snapshot()
                              .orElseThrow()
                              .target(FieldPath.ROOT.field(field)),
                          new DraftEdit.SetRaw(value)))));
    }

    void unchanged() throws Exception {
      assertArrayEquals(original, Files.readAllBytes(file));
      var actual = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
      assertEquals(originalAttributes.lastModifiedTime(), actual.lastModifiedTime());
      assertEquals(originalAttributes.fileKey(), actual.fileKey());
    }

    @Override
    public void close() {
      try {
        call(
            () -> {
              context.close();
              return null;
            });
      } catch (Exception failed) {
        if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
        throw new AssertionError(failed);
      } finally {
        workers.shutdownNow();
        ownerThread.shutdownNow();
        try {
          assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
          assertTrue(ownerThread.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException failed) {
          Thread.currentThread().interrupt();
          throw new AssertionError(failed);
        }
      }
    }
  }

  @Test
  void initialQueryContinuesEmptyBoundedScanPagesWithoutDeclaringNoMatches() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("Inherited")));
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      var scanning = h.update();
      assertEquals(PickerState.Phase.RUNNING, scanning.searching());
      assertTrue(scanning.window().choices().isEmpty());
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      var finished = h.update();
      assertEquals(PickerState.Phase.READY, finished.searching());
      assertEquals("fixture:empty", finished.window().choices().getFirst().key());
      assertEquals(3, h.searches.get());
      assertEquals("fixture:a", h.raw("models"));
    }
  }

  @Test
  void repeatedContinuationAfterServiceCompletionReusesTheActualRequest() throws Exception {
    try (var h =
        new Harness(UiLimits.STANDARD, new ReferencePicker.Policy(1, 128, 32768, 0, 2, 8))) {
      h.admit();
      var next = pending(h.call(() -> h.picker.nextPage().invoke(null)));
      done(next);
      var repeated = pending(h.call(() -> h.picker.nextPage().invoke(null)));
      assertEquals(
          next.snapshot().id(),
          repeated.snapshot().id(),
          "The completed current page has not been projected yet; reuse it rather than entering twice");
      h.update();
      assertEquals(2, h.searches.get());
    }
  }

  @Test
  void mountInterestsAndBorrowedFieldFrontendsKeepPureAndLifetimeGuards() throws Exception {
    try (var h = new Harness()) {
      h.call(
          () -> {
            var first = h.picker.mount();
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.scope.project(
                        List.of(),
                        () -> {
                          first.close();
                          return null;
                        }));
            assertThrows(
                PolicyRefusal.class,
                () ->
                    h.scope.project(
                        List.of(),
                        () -> {
                          first.update();
                          return null;
                        }));
            assertThrows(
                PolicyRefusal.class, () -> h.scope.project(List.of(), () -> h.picker.mount()));
            first.close();
            var field = FieldBinding.bind(h.scope, h.actions, MODEL_ROLE.field(), MODELS.type());
            var restriction = field.frontend();
            assertEquals(Optional.empty(), restriction.input());
            var clone =
                new DocumentDefinition<>(
                    DEFINITION.id(), 1, DEFINITION.type(), DEFINITION::defaults);
            assertThrows(
                PolicyRefusal.class,
                () ->
                    ReferencePicker.open(
                        h.scope,
                        field,
                        clone,
                        SOURCE,
                        () -> h.references,
                        List.of(MODEL_ROLE),
                        Map.of(),
                        ReferencePicker.Policy.STANDARD,
                        h.now::get));
            var borrowed =
                ReferencePicker.open(
                    h.scope,
                    field,
                    DEFINITION,
                    SOURCE,
                    () -> h.references,
                    List.of(MODEL_ROLE),
                    Map.of("models", restriction),
                    ReferencePicker.Policy.STANDARD,
                    h.now::get);
            field.close();
            assertThrows(PolicyRefusal.class, restriction::input);
            assertThrows(PolicyRefusal.class, restriction::editing);
            assertTrue(h.actions.view().snapshot().isPresent());
            borrowed.close();
            return null;
          });
      assertEquals(0, h.factories.get());
    }
  }

  @Test
  void boundedPagesRecoverPreviousSourceIssuedCursorsAndKeepCurrentness() throws Exception {
    try (var h =
        new Harness(UiLimits.STANDARD, new ReferencePicker.Policy(1, 128, 32768, 0, 2, 8))) {
      h.admit();
      var first = h.option("fixture:a");
      h.call(() -> h.picker.active().invoke(first));
      done(pending(h.call(() -> h.picker.nextPage().invoke(null))));
      var second = h.update();
      assertEquals(
          List.of("fixture:a", "fixture:b"),
          second.window().choices().stream().map(ReferencePage.Choice::key).toList());
      assertEquals(Optional.of("fixture:a"), second.active());
      h.inspect("fixture:a");
      assertEquals(Optional.of("fixture:a"), h.snapshot().inspection().orElseThrow().title());
      done(pending(h.call(() -> h.picker.nextPage().invoke(null))));
      var third = h.update();
      assertEquals(1, third.window().firstPage());
      assertEquals(2, third.window().pageCount());
      assertEquals("reference.option_stale", code(h.call(() -> h.picker.active().invoke(first))));
      assertTrue(h.call(() -> h.picker.previousPage().availability().available()));
      done(pending(h.call(() -> h.picker.previousPage().invoke(null))));
      var back = h.update();
      assertEquals(0, back.window().firstPage());
      assertEquals(3, back.window().retainedCursors());
      assertInstanceOf(UiActionResult.Applied.class, h.call(() -> h.picker.active().invoke(first)));
      assertEquals(4, h.searches.get());
      h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("Beta")));
      h.update();
      assertTrue(h.snapshot().window().choices().isEmpty());
      assertEquals("reference.option_stale", code(h.call(() -> h.picker.active().invoke(first))));
      assertEquals("fixture:a", h.raw("models"));
    }
  }

  @Test
  void cursorBudgetRefusesRatherThanDroppingHistoryOrEditingTheDraft() throws Exception {
    try (var h =
        new Harness(UiLimits.STANDARD, new ReferencePicker.Policy(1, 128, 32768, 0, 2, 2))) {
      h.admit();
      done(pending(h.call(() -> h.picker.nextPage().invoke(null))));
      var refused = h.update();
      assertEquals(PickerState.Phase.REFUSED, refused.searching());
      assertEquals("reference.page_history_limit", refused.report().issues().getFirst().code());
      assertEquals(
          List.of("fixture:a"),
          refused.window().choices().stream().map(ReferencePage.Choice::key).toList());
      assertEquals(2, refused.window().retainedCursors());
      assertEquals("fixture:a", h.raw("models"));
      done(pending(h.call(() -> h.picker.refresh().invoke(null))));
      h.update();
      assertTrue(h.snapshot().report().valid());
    }
  }

  @Test
  void oneMountedInterestIsLazyAndClosingItDoesNotCloseBorrowedServices() throws Exception {
    try (var h = new Harness()) {
      var first = h.call(() -> h.picker.mount());
      assertEquals(0, h.factories.get());
      var refusal = assertThrows(ExecutionException.class, () -> h.call(() -> h.picker.mount()));
      assertInstanceOf(PolicyRefusal.class, refusal.getCause());
      h.call(
          () -> {
            first.update();
            return null;
          });
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      h.update();
      assertEquals(1, h.factories.get());
      h.call(
          () -> {
            first.close();
            return null;
          });
      assertEquals(PickerState.Source.SUSPENDED, h.snapshot().source());
      assertTrue(h.call(() -> h.actions.view().snapshot().isPresent()));
      var next = h.call(() -> h.picker.mount());
      h.call(
          () -> {
            next.update();
            return null;
          });
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      h.update();
      assertEquals(2, h.factories.get());
      h.call(
          () -> {
            next.close();
            return null;
          });
      assertEquals("fixture:a", h.raw("models"));
    }
  }

  @Test
  void enterAfterWorkerCompletionBeforeProjectionDoesNotSubmitTheSameSearchAgain()
      throws Exception {
    try (var h = new Harness()) {
      var first = pending(h.call(() -> h.picker.admit().invoke(null)));
      done(first);
      assertEquals(
          PickerState.Phase.RUNNING,
          h.snapshot().searching(),
          "The host has not projected the terminal service result yet");
      var flushed = pending(h.call(() -> h.picker.flushSearch().invoke(null)));
      assertEquals(
          first.snapshot().id(),
          flushed.snapshot().id(),
          "Enter must flush demand, not duplicate a completed current query");
      h.update();
      assertEquals(1, h.searches.get());
      h.unchanged();
    }
  }

  @Test
  void actualRoleReadinessAndStructuralAdmissionAreSeparateFromReadOnlyBrowsing() throws Exception {
    try (var h = new Harness()) {
      assertFalse(h.call(() -> h.picker.flushSearch().availability().available()));
      assertFalse(h.call(() -> h.picker.acceptInspected(MODEL_ROLE).availability().available()));
      var before = h.call(h.scope::usage);
      var factoryCalls = new AtomicInteger();
      h.call(
          () -> {
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    ReferencePicker.open(
                        h.scope,
                        h.actions,
                        SOURCE,
                        () -> {
                          factoryCalls.incrementAndGet();
                          return h.references;
                        },
                        List.of(new PickerRole<Profile>("wrong", FieldPath.ROOT.field("count"))),
                        Map.of(),
                        ReferencePicker.Policy.STANDARD,
                        h.now::get));
            assertEquals(before, h.scope.usage());
            return null;
          });
      assertEquals(0, factoryCalls.get());
      h.admit();
      assertTrue(h.call(() -> h.picker.nextPage().availability().available()));
      assertFalse(h.call(() -> h.picker.acceptInspected(MODEL_ROLE).availability().available()));
      h.inspect("fixture:b");
      assertTrue(h.call(() -> h.picker.acceptInspected(MODEL_ROLE).availability().available()));
      h.write.set(false);
      h.snapshot();
      assertFalse(h.call(() -> h.picker.acceptInspected(MODEL_ROLE).availability().available()));
      assertFalse(h.call(() -> h.picker.activate(MODEL_ROLE).availability().available()));
      assertTrue(h.call(() -> h.picker.inspect().availability().available()));
      assertEquals(List.of("models", "artwork"), List.copyOf(h.snapshot().requested().keySet()));
      h.unchanged();
    }
  }

  @Test
  void reentrantDeliveryCannotEraseOrRebindANewerExplicitAcceptance() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      var first = h.option("fixture:a");
      var second = h.option("fixture:b");
      done(
          pending(
              h.call(
                  () ->
                      h.picker
                          .activate()
                          .invoke(new PickerState.Activation<>(first, Optional.of(MODEL_ROLE))))));
      var newer = new AtomicReference<OperationHandle<?>>();
      h.beforeSourceRead.set(
          () ->
              h.beforeSourceRead.set(
                  () ->
                      newer.set(
                          pending(
                              h.picker
                                  .activate()
                                  .invoke(
                                      new PickerState.Activation<>(
                                          second, Optional.of(ART_ROLE)))))));
      h.update();
      assertNotNull(newer.get());
      done(newer.get());
      awaitState(
          h,
          state -> state.requested().get("artwork").raw().filter("fixture:b"::equals).isPresent());
      assertEquals("fixture:a", h.raw("models"));
      assertEquals("fixture:b", h.raw("artwork"));
      h.unchanged();
    }
  }

  @Test
  void enteredSearchDrainsBeforeOneLatestQueryCanEnter() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.beforeSearch.set(
          query -> {
            if (query.filter().equals("Alpha")) {
              entered.countDown();
              awaitGate(release);
            }
          });
      h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("Alpha")));
      var old = pending(h.call(() -> h.picker.flushSearch().invoke(null)));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("Inherited")));
        h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("Beta")));
        var waiting = pending(h.call(() -> h.picker.flushSearch().invoke(null)));
        assertEquals(old.snapshot().id(), waiting.snapshot().id());
        assertTrue(old.snapshot().cancellationRequested());
        assertFalse(old.snapshot().terminal());
        assertEquals(2, h.searches.get(), "The entered predecessor owns the only worker interest");
        assertEquals(PickerState.Phase.DRAINING, h.snapshot().searching());
      } finally {
        release.countDown();
      }
      done(old);
      h.update();
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      var current = h.update();
      assertEquals(PickerState.Phase.READY, current.searching());
      assertEquals("Beta", current.query().text());
      assertEquals(3, h.searches.get());
      assertEquals("fixture:b", current.page().orElseThrow().choices().getFirst().key());
      h.unchanged();
    }
  }

  @Test
  void enteredLookupKeepsOnlyLatestInspectionAndCloseCancelsOnlyOwnedWork() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      h.beforeLookup.set(
          raw -> {
            if (raw.equals("fixture:a")) {
              entered.countDown();
              awaitGate(release);
            }
          });
      var old = pending(h.call(() -> h.picker.inspect().invoke("fixture:a")));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        h.call(() -> h.picker.inspect().invoke("fixture:b"));
        h.call(() -> h.picker.inspect().invoke("fixture:empty"));
        assertEquals(1, h.lookups.get());
        assertFalse(old.snapshot().terminal());
        assertEquals(PickerState.Phase.DRAINING, h.snapshot().inspecting());
      } finally {
        release.countDown();
      }
      done(old);
      var ready =
          awaitState(
              h,
              state -> state.inspection().filter(i -> i.key().equals("fixture:empty")).isPresent());
      assertEquals(ReferenceResolution.Status.RESOLVED, ready.inspection().orElseThrow().status());
      assertEquals(2, h.lookups.get());
      var enteredAgain = new CountDownLatch(1);
      var releaseAgain = new CountDownLatch(1);
      h.beforeLookup.set(
          raw -> {
            enteredAgain.countDown();
            awaitGate(releaseAgain);
          });
      var closing = pending(h.call(() -> h.picker.inspect().invoke("fixture:b")));
      try {
        assertTrue(enteredAgain.await(5, TimeUnit.SECONDS));
        h.call(
            () -> {
              h.picker.close();
              assertTrue(h.references.isOpen());
              assertTrue(h.actions.isOpen());
              assertEquals(1, h.scope.usage().scopes());
              assertEquals(0, h.scope.usage().stateBytes());
              return null;
            });
        assertFalse(closing.snapshot().terminal());
        assertTrue(h.call(() -> closing.snapshot().cancellationRequested()));
      } finally {
        releaseAgain.countDown();
      }
      done(closing);
      assertEquals(OperationSnapshot.State.CANCELLED, closing.snapshot().state());
      h.unchanged();
    }
  }

  @Test
  void budgetRefusalWithdrawsTheBorrowedPageAndRetainsNoExtraUiPayload() throws Exception {
    var limits = new UiLimits(128, 16, 128, 128, 512, 128, 4096);
    try (var h = new Harness(limits)) {
      h.call(
          () ->
              h.references.bind(
                  "large",
                  stamp ->
                      ReferenceIndexTest.index(
                          stamp,
                          List.of(
                              new ReferenceIndex.Entry<>(
                                  "fixture:a", List.of(), "x".repeat(6000), "", Map.of())))));
      done(pending(h.call(() -> h.picker.admit().invoke(null))));
      h.call(
          () -> {
            h.picker.update();
            h.scope.refreshObservations();
            assertInstanceOf(UiObservation.Unavailable.class, h.picker.state().snapshot());
            return null;
          });
      var refused = h.update();
      assertEquals(PickerState.Source.UNAVAILABLE, refused.source());
      assertTrue(refused.page().isEmpty());
      assertTrue(h.call(() -> h.references.isOpen()));
      h.call(
          () -> {
            h.picker.close();
            assertEquals(0, h.scope.usage().stateBytes());
            return null;
          });
      h.unchanged();
    }
  }

  static void awaitGate(CountDownLatch gate) {
    try {
      if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("Owned gate was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  static PickerState awaitState(Harness h, Predicate<PickerState> ready) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      var state = h.update();
      if (ready.test(state)) return state;
      Thread.yield();
    } while (System.nanoTime() - end < 0);
    throw new AssertionError("Picker did not reach its expected actual state");
  }

  @Test
  void lazyAdmissionDebounceLatestQueryAndCaretOnlyChanges() throws Exception {
    try (var h = new Harness()) {
      assertEquals(PickerState.Source.UNADMITTED, h.snapshot().source());
      assertEquals(0, h.factories.get());
      assertEquals(0, h.searches.get());
      h.admit();
      assertEquals(1, h.factories.get());
      var initial = h.snapshot();
      assertEquals(PickerState.Phase.READY, initial.searching());
      assertEquals(2, initial.page().orElseThrow().choices().size());
      assertFalse(
          initial.page().orElseThrow().exhausted(), "The bounded scan does not invent a total");
      assertInstanceOf(
          UiActionResult.Applied.class,
          h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("Alpha"))));
      h.now.set(100_000_000);
      h.update();
      assertEquals(1, h.searches.get());
      h.call(() -> h.picker.query().invoke(new PickerState.Query("Beta", 2, 1)));
      h.now.set(299_999_999);
      h.update();
      assertEquals(1, h.searches.get());
      h.now.set(300_000_000);
      var s = h.update();
      var work = h.call(() -> h.picker.state().snapshot().value().searchWork().orElseThrow());
      assertNotNull(work.id());
      // Await the actual service result; repeated host observation is bounded and contains no
      // sleeps.
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      s = h.update();
      assertEquals("Beta", s.query().text());
      assertEquals(2, s.query().caret());
      assertEquals(1, s.query().anchor());
      int before = h.searches.get();
      h.call(() -> h.picker.query().invoke(new PickerState.Query("Beta", 1, 0)));
      h.now.addAndGet(500_000_000);
      h.update();
      assertEquals(before, h.searches.get(), "Caret-only changes do not search again");
      assertEquals(1, h.factories.get());
      h.unchanged();
    }
  }

  @Test
  void activeInspectionAndBothRolesAreIndependentOfSavedValues() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      var option = h.option("fixture:b");
      h.call(() -> h.picker.active().invoke(option));
      assertEquals("fixture:a", h.raw("models"));
      assertTrue(h.snapshot().inspection().isEmpty());
      var pin = new PickerState.Activation<Profile>(option, Optional.empty());
      done(pending(h.call(() -> h.picker.activate().invoke(pin))));
      h.update();
      assertEquals("fixture:b", h.snapshot().inspection().orElseThrow().key());
      assertEquals("fixture:a", h.raw("models"));
      done(pending(h.call(() -> h.picker.acceptInspected().invoke(MODEL_ROLE))));
      h.update();
      assertEquals("fixture:b", h.raw("models"));
      assertEquals("fixture:a", h.raw("artwork"));
      h.inspect("fixture:empty");
      assertEquals(
          ReferenceResolution.Status.RESOLVED, h.snapshot().inspection().orElseThrow().status());
      done(pending(h.call(() -> h.picker.acceptInspected().invoke(ART_ROLE))));
      h.update();
      assertEquals("fixture:empty", h.raw("artwork"));
      assertEquals("fixture:b", h.raw("models"));
      h.unchanged();
    }
  }

  @Test
  void delayedInlineAcceptanceCannotOverwriteANewerDraftOrNativeInputEpoch() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      var selected = h.option("fixture:b");
      h.input.set(Optional.of(new FieldFrontend.Input("fixture:a", 1, 9, 9, true, false)));
      var activation = new PickerState.Activation<>(selected, Optional.of(MODEL_ROLE));
      done(pending(h.call(() -> h.picker.activate().invoke(activation))));
      h.edit("count", "2");
      h.update();
      assertEquals("fixture:a", h.raw("models"));
      assertEquals("reference.selection_stale", h.snapshot().report().issues().getFirst().code());
      done(pending(h.call(() -> h.picker.activate().invoke(activation))));
      h.input.set(Optional.of(new FieldFrontend.Input("fixture:a", 2, 8, 8, true, false)));
      h.update();
      assertEquals("fixture:a", h.raw("models"));
      assertEquals("reference.input_stale", h.snapshot().report().issues().getFirst().code());
      h.input.set(Optional.of(new FieldFrontend.Input("bad raw", 3, 3, 3, false, false)));
      assertEquals(
          "reference.input_unaccepted", code(h.call(() -> h.picker.activate().invoke(activation))));
      h.unchanged();
    }
  }

  @Test
  void readOnlyAndInvalidSiblingDoNotInventSelectionAuthority() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      h.inspect("fixture:b");
      h.write.set(false);
      assertInstanceOf(
          UiActionResult.Refused.class,
          h.call(() -> h.picker.acceptInspected().invoke(MODEL_ROLE)));
      assertEquals("fixture:a", h.raw("models"));
      assertEquals(
          ReferenceResolution.Status.RESOLVED, h.snapshot().inspection().orElseThrow().status());
      h.write.set(true);
      h.edit("count", "invalid");
      done(pending(h.call(() -> h.picker.acceptInspected().invoke(MODEL_ROLE))));
      h.update();
      assertEquals("fixture:b", h.raw("models"));
      assertEquals("invalid", h.raw("count"));
      h.unchanged();
    }
  }

  @Test
  void sourceReplacementAndReadLossWithdrawFactsPreservingQueryAndRequestedIds() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      h.inspect("fixture:a");
      h.call(() -> h.picker.query().invoke(new PickerState.Query("Alpha", 2, 1)));
      done(pending(h.call(() -> h.picker.flushSearch().invoke(null))));
      h.update();
      var old = h.option("fixture:a");
      h.bind();
      var stale = h.update();
      assertEquals(PickerState.Source.STALE, stale.source());
      assertTrue(stale.page().isEmpty());
      assertTrue(stale.inspection().isEmpty());
      assertEquals(new PickerState.Query("Alpha", 2, 1), stale.query());
      assertEquals("fixture:a", stale.requested().get("models").raw().orElseThrow());
      h.admit();
      assertEquals("reference.option_stale", code(h.call(() -> h.picker.active().invoke(old))));
      h.sourceRead.set(false);
      var denied = h.update();
      assertTrue(denied.page().isEmpty());
      assertTrue(denied.inspection().isEmpty());
      assertTrue(denied.searchWork().isEmpty());
      assertTrue(denied.lookupWork().isEmpty());
      assertInstanceOf(
          UiActionResult.Refused.class, h.call(() -> h.picker.flushSearch().invoke(null)));
      h.sourceRead.set(true);
      h.admit();
      assertFalse(h.snapshot().page().orElseThrow().choices().isEmpty());
      h.read.set(false);
      var ownerDenied = h.snapshot();
      assertTrue(ownerDenied.requested().isEmpty());
      assertTrue(ownerDenied.page().isEmpty());
      h.read.set(true);
      h.unchanged();
    }
  }

  @Test
  void pureProjectionCannotAdmitSourceOrChangeQueryAndCloseKeepsBorrowedServices()
      throws Exception {
    try (var h = new Harness()) {
      h.call(
          () -> {
            var result =
                h.scope.project(List.of(h.picker.state()), () -> h.picker.admit().invoke(null));
            assertInstanceOf(UiActionResult.Refused.class, result);
            return null;
          });
      assertEquals(0, h.factories.get());
      assertEquals("", h.snapshot().query().text());
      h.admit();
      h.call(
          () -> {
            h.picker.close();
            assertTrue(h.actions.isOpen());
            assertTrue(h.references.isOpen());
            assertTrue(h.scope.isOpen());
            return null;
          });
      assertInstanceOf(
          UiActionResult.Refused.class,
          h.call(() -> h.picker.query().invoke(PickerState.Query.atEnd("x"))));
      h.unchanged();
    }
  }

  @Test
  void unknownMalformedAndUnavailableSourcesRemainDistinct() throws Exception {
    try (var h = new Harness()) {
      h.admit();
      h.inspect("fixture:missing");
      assertEquals(
          ReferenceResolution.Status.UNKNOWN_REFERENCE,
          h.snapshot().inspection().orElseThrow().status());
      h.inspect("NOT VALID");
      assertEquals(
          ReferenceResolution.Status.MALFORMED, h.snapshot().inspection().orElseThrow().status());
      h.call(
          () ->
              h.references.invalidate(
                  "unavailable",
                  ReferencePage.Availability.MISSING_DEPENDENCY,
                  "Required pack is missing"));
      h.update();
      h.admit();
      assertEquals(
          ReferencePage.Availability.MISSING_DEPENDENCY,
          h.snapshot().page().orElseThrow().availability());
      assertEquals("Required pack is missing", h.snapshot().page().orElseThrow().reason());
      h.unchanged();
    }
  }
}
