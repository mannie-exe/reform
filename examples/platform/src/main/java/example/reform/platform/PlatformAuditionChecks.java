package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.*;
import design.inherent.reform.definition.*;
import design.inherent.reform.edit.*;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.*;
import design.inherent.reform.value.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual typed host/capture/preparation API; no substitute actor, renderer, or settings writer. */
final class PlatformAuditionChecks implements PlatformFormProbe.Scene {
  record Profile(String models, String artwork, int distance) {}

  static final DocumentId SOURCE = DocumentId.parse("fixture:audition_profiles");
  static final FieldKey<String> MODELS =
      FieldKey.of("models", ValueTypes.reference(ValueTypes.text(128), SOURCE));
  static final FieldKey<String> ARTWORK = FieldKey.of("artwork", MODELS.type());
  static final FieldKey<Integer> DISTANCE = FieldKey.of("distance", ValueTypes.integer(1, 256));
  static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_platform_example:audition_profiles"),
          1,
          RecordType.<Profile>builder(
                  values ->
                      new Profile(values.get(MODELS), values.get(ARTWORK), values.get(DISTANCE)))
              .field(MODELS, Profile::models)
              .field(ARTWORK, Profile::artwork)
              .field(DISTANCE, Profile::distance)
              .build(),
          () -> new Profile("fixture:base", "fixture:base", 64));
  static final GraphicLimits LIMITS = new GraphicLimits(65536, 1_048_576, 65536, 16, 4_194_304);
  static ClientFileSettings<Profile> settings;

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(registration, "AUDITIONS", PlatformAuditionChecks::new);
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final JsonArray checks = new JsonArray(), bases = new JsonArray();
  private final Queue<Integer> entries = new ConcurrentLinkedQueue<>();
  private final Map<Integer, CompletableFuture<Void>> gates = new ConcurrentHashMap<>();
  private final AtomicBoolean sourceRead = new AtomicBoolean(true);
  private final AtomicInteger legacyEntries = new AtomicInteger();
  private ClientEditingWorkflow workflow;
  private ClientEditor<Profile> editor;
  private ClientGraphicHost host;
  private ClientGraphicHost.View<Profile, Integer> view, legacy;
  private OwnerContext sourceContext;
  private ReferenceActions<String, List<String>> source;
  private ReferenceBrowser<List<String>> browser;
  private ReferenceBrowser.Lookup<List<String>> lookup;
  private ReferenceAudition<Profile> audition;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private ClientGraphicHost.Attempt current, held, skipped, replacement;
  private DraftRevision initial;
  private GraphicCamera camera;
  private Path file;
  private byte[] bytes;
  private BasicFileAttributes attrs;
  private long heldBytes, entriesBeforeRestore, peakBeforeMismatch;
  private int stage;
  private boolean fullBasisVerified, cameraRetained, sourceUnchanged, closedNoPublication;
  private boolean complete;
  private volatile JsonObject basisObservation;

  private PlatformAuditionChecks(PlatformFormProbe.Driver driver) {}

  private synchronized void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private boolean done(ClientGraphicHost.Attempt attempt) {
    return attempt.completion().toCompletableFuture().isDone();
  }

  private boolean cancelled(ClientGraphicHost.Attempt attempt) {
    return attempt.operation().orElseThrow().snapshot().cancellationRequested();
  }

  private void bind() {
    source.bind(
        "same-readable-revision",
        stamp ->
            new ReferenceIndex<>(
                stamp,
                ValueTypes.text(128),
                ValueTypes.list(ValueTypes.text(128), 4).snapshotPolicy(),
                new ReferenceLimits(16, 32768, 4, 128, 4),
                List.of(
                    new ReferenceIndex.Entry<>(
                        "fixture:base", List.of("base"), "Base", "Base", Map.of()),
                    new ReferenceIndex.Entry<>(
                        "fixture:alternate", List.of(), "Inherited", "Inherited", Map.of()))));
  }

  private void resolve() {
    if (browser != null) browser.close();
    browser = new ReferenceBrowser<>(source);
    lookup = browser.resolve(OperationDescriptor.EntryPoint.GUI, "fixture:alternate");
  }

  private boolean resolved() {
    if (!lookup.operation().snapshot().terminal()) return false;
    check(
        "real browser accepts resolved-empty choice",
        browser.accept(lookup) && browser.selectedKey().orElseThrow().equals("fixture:alternate"));
    audition =
        ReferenceSelection.auditionFromBrowser(
            "models", editor.actions(), FieldPath.ROOT.field("models"), browser);
    return true;
  }

  private boolean empty() {
    return host.retainedInput().bytes() == 0
        && host.retainedInput().resources() == 0
        && host.retainedOutput().bytes() == 0
        && host.retainedOutput().resources() == 0
        && host.nativeOwnership().bytes() == 0
        && host.nativeOwnership().resources() == 0;
  }

  private void nativeDistance(String raw) {
    var path = FieldPath.ROOT.field("distance");
    check("actual numeric sibling is focusable", editor.form().focusField(path));
    replace(workflow.screen(), (EditBox) editor.form().fieldControl(path).orElseThrow(), raw);
  }

  private void release(int subject) {
    gates.computeIfAbsent(subject, ignored -> new CompletableFuture<>()).complete(null);
  }

  private ClientCapturedGraphicProvider<Profile, Integer> provider() {
    var context = UUID.randomUUID();
    var generation = UUID.randomUUID();
    return new ClientCapturedGraphicProvider<>() {
      public DocumentId id() {
        return DocumentId.parse("fixture:captured_profiles");
      }

      public ValueType<Integer> subjectType() {
        return ValueTypes.integer(0, 9);
      }

      public GraphicLimits limits() {
        return LIMITS;
      }

      public ReferenceStamp resourceStamp() {
        return new ReferenceStamp(context, id(), generation, "local-static-resources");
      }

      public CompletionStage<GraphicScenePreparation> prepareCaptured(
          CapturedGraphicRequest<Profile, Integer> request, ClientGraphicWork work) {
        check("typed provider enters on actual client thread", client.isSameThread());
        return work.onWorker(
            () -> {
              check("captured domain work uses actual borrowed worker", !client.isSameThread());
              int subject = request.subject();
              entries.add(subject);
              var value = request.input().candidate().value();
              if (!value.artwork().equals("fixture:base") || value.distance() != 64)
                throw new AssertionError("Complete sibling values changed");
              if (request.input() instanceof AuditionPreviewInput<Profile> alternate) {
                if (!value.models().equals("fixture:alternate")
                    || !alternate.base().candidate().value().models().equals("fixture:base"))
                  throw new AssertionError("Audition confused overlay and real base");
                if (request.stamp().kind() != GraphicSourceStamp.Kind.AUDITION
                    || !request.stamp().basis().equals(alternate.basis())
                    || !GraphicInputBasis.Content.of(valueJson(alternate))
                        .equals(alternate.basis().content()))
                  throw new AssertionError("Audition basis mismatch");
                if (subject == 1) {
                  var fact = new JsonObject();
                  fact.addProperty("kind", request.stamp().kind().name());
                  fact.addProperty("base", request.input().stamp().draft().toString());
                  fact.addProperty("nativeInputEpoch", request.input().stamp().nativeInputEpoch());
                  fact.addProperty("role", alternate.basis().role());
                  fact.addProperty("key", alternate.basis().key());
                  fact.addProperty("target", alternate.basis().target().path().toString());
                  fact.addProperty(
                      "sourceSnapshot", alternate.basis().reference().snapshot().toString());
                  fact.addProperty("contentSha256", alternate.basis().content().sha256());
                  fact.addProperty("baseModels", alternate.base().candidate().value().models());
                  fact.addProperty("previewModels", value.models());
                  fact.addProperty("unchangedArtwork", value.artwork());
                  fact.addProperty("copiedBytes", alternate.copiedInputBytes());
                  basisObservation = fact;
                }
              } else if (!value.models().equals("fixture:base")
                  || request.stamp().kind() != GraphicSourceStamp.Kind.DRAFT)
                throw new AssertionError("Draft bridge changed its basis");
              if (Set.of(2, 5, 6, 9).contains(subject))
                gates
                    .computeIfAbsent(subject, ignored -> new CompletableFuture<>())
                    .get(15, TimeUnit.SECONDS);
              work.checkpoint();
              var stamp = request.stamp();
              if (subject == 7)
                stamp =
                    new GraphicRequest.Stamp(
                        stamp.provider(),
                        stamp.view(),
                        stamp.request(),
                        stamp.context(),
                        stamp.input(),
                        stamp.resources());
              return new GraphicScenePreparation.Ready(
                  new GraphicSceneData(
                      stamp,
                      PlatformMeshChecks.pattern(),
                      GraphicCamera.STANDARD,
                      List.of(
                          new GraphicSceneData.Fact(
                              DocumentId.parse("fixture:preview_models"),
                              "Preview models",
                              value.models()))));
            });
      }
    };
  }

  private static String valueJson(AuditionPreviewInput<?> input) {
    return input.candidate().authoredJson();
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        var form = settings.form(Component.literal("Audition inputs"));
        workflow =
            ClientEditingWorkflow.open(
                parent, Component.literal("Read-only auditions"), List.of(form));
        NativeScreens.open(client, workflow.screen());
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Document(form.route()))
                .completion()
                .toCompletableFuture();
        stage++;
      }
      case 1 -> {
        if (!navigation.isDone()) return false;
        check(
            "actual document is revealed",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(DEFINITION);
        if (editor.busy()) return false;
        initial = DraftRevision.of(editor.view().snapshot().orElseThrow());
        file =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/audition_profiles.json");
        bytes = Files.readAllBytes(file);
        attrs = Files.readAttributes(file, BasicFileAttributes.class);
        sourceContext =
            new OwnerContext(
                client::execute,
                NativeClientProbe.ioPool(),
                () -> {
                  if (!client.isSameThread())
                    throw new IllegalStateException("Actual source client thread required");
                },
                OwnerContext.Limits.STANDARD);
        source =
            new ReferenceActions<>(
                sourceContext,
                sourceContext.access(sourceRead::get, () -> false),
                SOURCE,
                ValueTypes.text(128),
                ValueTypes.list(ValueTypes.text(128), 4).snapshotPolicy(),
                new ReferenceLimits(16, 32768, 4, 128, 4));
        bind();
        resolve();
        host = workflow.graphics(LIMITS, 2);
        view = host.open(editor, provider());
        legacy =
            host.open(
                editor,
                new ClientGraphicProvider<Profile, Integer>() {
                  public DocumentId id() {
                    return DocumentId.parse("fixture:legacy_draft_provider");
                  }

                  public ValueType<Integer> subjectType() {
                    return ValueTypes.integer(0, 9);
                  }

                  public GraphicLimits limits() {
                    return LIMITS;
                  }

                  public ReferenceStamp resourceStamp() {
                    return source.requireStamp();
                  }

                  public CompletionStage<GraphicScenePreparation> prepare(
                      GraphicRequest<Profile, Integer> request, ClientGraphicWork work) {
                    DraftPreviewInput<Profile> originalAccessor = request.input();
                    check(
                        "legacy provider receives the unchanged actual draft",
                        originalAccessor.candidate().value().models().equals("fixture:base")
                            && request.stamp().kind() == GraphicSourceStamp.Kind.DRAFT);
                    legacyEntries.incrementAndGet();
                    return CompletableFuture.completedStage(
                        new GraphicScenePreparation.Unavailable(
                            design.inherent.reform.validation.ValidationReport.VALID));
                  }
                });
        stage++;
      }
      case 2 -> {
        if (!resolved()) return false;
        try {
          legacy.request(0, audition);
          throw new AssertionError("Legacy provider accepted audition");
        } catch (PolicyRefusal refused) {
          check(
              "legacy audition is refused before capture or provider entry",
              refused.report().issues().getFirst().code().equals("preview.audition_unsupported")
                  && legacyEntries.get() == 0
                  && host.retainedInput().bytes() == 0);
        }
        current = legacy.request(0);
        stage++;
      }
      case 3 -> {
        if (!done(current)) return false;
        check("legacy draft bridge enters once", legacyEntries.get() == 1);
        legacy.close();
        view.orbit(17, -4);
        view.zoom(1.25);
        camera = view.camera();
        current = view.request(0);
        stage++;
      }
      case 4 -> {
        if (!done(current)) return false;
        check(
            "opt-in provider also receives truthful DRAFT",
            view.snapshot().state() == ClientGraphicHost.State.READY
                && view.displayedStamp().orElseThrow().kind() == GraphicSourceStamp.Kind.DRAFT
                && view.camera().equals(camera));
        current = view.request(1, audition);
        stage++;
      }
      case 5 -> {
        if (!done(current)) return false;
        check(
            "actual audition reaches native preparation with full basis",
            view.snapshot().state() == ClientGraphicHost.State.READY
                && view.displayedStamp().orElseThrow().kind() == GraphicSourceStamp.Kind.AUDITION
                && view.facts().getFirst().value().equals("fixture:alternate")
                && host.nativeOwnership().bytes() > 0
                && host.retainedOutput().bytes() > 0
                && host.retainedInput().bytes() == 0);
        check(
            "audition keeps actor revision and camera",
            initial.equals(DraftRevision.of(editor.view().snapshot().orElseThrow()))
                && view.camera().equals(camera));
        bases.add(view.displayedStamp().orElseThrow().kind().name());
        fullBasisVerified = true;
        cameraRetained = true;
        held = view.request(2, audition);
        stage++;
      }
      case 6 -> {
        if (!entries.contains(2)) return false;
        heldBytes = host.retainedInput().bytes();
        check(
            "entered provider retains copied input",
            heldBytes > 0 && host.retainedInput().resources() == 1);
        skipped = view.request(3, audition);
        replacement = view.request(4, audition);
        check(
            "latest demand cannot enter before old provider drain",
            cancelled(held)
                && !done(held)
                && skipped.operation().isEmpty()
                && done(skipped)
                && replacement.operation().isEmpty()
                && !entries.contains(3)
                && !entries.contains(4)
                && host.retainedInput().bytes() == heldBytes);
        release(2);
        stage++;
      }
      case 7 -> {
        if (!done(replacement)) return false;
        check(
            "replacement retires old input before native READY",
            held.operation().orElseThrow().snapshot().state() == OperationSnapshot.State.CANCELLED
                && !entries.contains(3)
                && view.snapshot().state() == ClientGraphicHost.State.READY
                && host.retainedInput().bytes() == 0
                && view.camera().equals(camera));
        held = view.request(5, audition);
        stage++;
      }
      case 8 -> {
        if (!entries.contains(5)) return false;
        bind();
        stage++;
      }
      case 9 -> {
        if (!cancelled(held)) return false;
        check(
            "catalogue generation invalidates held audition despite same label",
            !done(held) && host.retainedInput().bytes() > 0);
        release(5);
        stage++;
      }
      case 10 -> {
        if (!done(held) || !empty()) return false;
        try {
          view.request(1, audition);
          throw new AssertionError("Stale audition accepted");
        } catch (PolicyRefusal refused) {
          check("old source token cannot enter replacement", !refused.report().valid());
        }
        resolve();
        stage++;
      }
      case 11 -> {
        if (!resolved()) return false;
        held = view.request(6, audition);
        stage++;
      }
      case 12 -> {
        if (!entries.contains(6)) return false;
        nativeDistance("broken");
        try {
          view.request(1, audition);
          throw new AssertionError("Unaccepted native input entered preview");
        } catch (PolicyRefusal refused) {
          check(
              "pending native input is refused before replacing the entered request",
              refused.report().issues().getFirst().code().equals("preview.input_unaccepted"));
        }
        stage++;
      }
      case 13 -> {
        if (!cancelled(held)) return false;
        check(
            "actual invalid native sibling cancels held audition without early refund",
            !done(held) && host.retainedInput().bytes() > 0);
        release(6);
        stage++;
      }
      case 14 -> {
        if (!done(held) || !empty()) return false;
        if (editor.busy() || editor.view().validation().valid()) return false;
        entriesBeforeRestore = entries.size();
        current = view.request(1, audition);
        stage = 140;
      }
      case 140 -> {
        if (!done(current)) return false;
        var result = current.completion().toCompletableFuture().join();
        check(
            "accepted invalid sibling is refused by complete base preflight without a copy",
            result.state() == ClientGraphicHost.State.REFUSED
                && result.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("preview.input_invalid"))
                && entries.size() == entriesBeforeRestore
                && empty());
        nativeDistance("64");
        stage = 15;
      }
      case 15 -> {
        if (!editor.canStage(FieldPath.ROOT.field("distance"))
            || !editor.view().validation().valid()) return false;
        audition =
            ReferenceSelection.auditionFromBrowser(
                "models", editor.actions(), FieldPath.ROOT.field("models"), browser);
        peakBeforeMismatch = host.nativeOwnership().peakBytes();
        current = view.request(7, audition);
        stage++;
      }
      case 16 -> {
        if (!done(current)) return false;
        check(
            "DRAFT-tagged audition output cannot mount",
            current.completion().toCompletableFuture().join().state()
                    == ClientGraphicHost.State.REFUSED
                && empty()
                && host.nativeOwnership().peakBytes() == peakBeforeMismatch);
        current = view.request(8, audition);
        stage++;
      }
      case 17 -> {
        if (!done(current)) return false;
        check(
            "explicit fresh audition recovery reaches READY",
            view.snapshot().state() == ClientGraphicHost.State.READY);
        sourceRead.set(false);
        check(
            "source READ loss immediately withholds current facts and basis",
            view.facts().isEmpty() && view.displayedStamp().isEmpty());
        stage++;
      }
      case 18 -> {
        if (!empty()) return false;
        try {
          view.request(1, audition);
          throw new AssertionError("Denied source entered preview");
        } catch (PolicyRefusal refused) {
          check("actual source READ refusal prevents preparation", !refused.report().valid());
        }
        entriesBeforeRestore = entries.size();
        sourceRead.set(true);
        stage++;
      }
      case 19 -> {
        check(
            "permission recovery alone does not reprepare", entries.size() == entriesBeforeRestore);
        held = view.request(9, audition);
        stage++;
      }
      case 20 -> {
        if (!entries.contains(9)) return false;
        workflow.close();
        check(
            "close retains entered copied input until actual worker drain",
            view.snapshot().state() == ClientGraphicHost.State.CLOSED
                && !done(held)
                && host.retainedInput().bytes() > 0);
        release(9);
        stage++;
      }
      case 21 -> {
        if (!done(held) || !empty()) return false;
        check(
            "closed owner cannot mount late output",
            !workflow.isOpen()
                && !editor.isOpen()
                && view.snapshot().state() == ClientGraphicHost.State.CLOSED
                && host.releaseFailure().isEmpty());
        check(
            "only actual latest subjects entered",
            new ArrayList<>(entries).equals(List.of(0, 1, 2, 4, 5, 6, 7, 8, 9)));
        var after = Files.readAttributes(file, BasicFileAttributes.class);
        check(
            "auditions and repair never persist or replace file",
            Arrays.equals(bytes, Files.readAllBytes(file))
                && attrs.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(attrs.fileKey(), after.fileKey()));
        sourceUnchanged = true;
        closedNoPublication = true;
        complete = true;
        NativeScreens.open(client, parent);
        stage++;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }

  @Override
  public String diagnostic() {
    return "stage="
        + stage
        + " entries="
        + entries
        + " state="
        + (view == null ? "none" : view.snapshot().state())
        + " copied="
        + (host == null ? 0 : host.retainedInput().bytes());
  }

  @Override
  public synchronized JsonObject report() {
    var result = new JsonObject();
    result.add("checks", checks.deepCopy());
    result.addProperty("stage", stage);
    result.addProperty("fullBasisVerified", fullBasisVerified);
    result.addProperty("cameraRetained", cameraRetained);
    result.addProperty("sourceUnchanged", sourceUnchanged);
    result.addProperty("closedNoPublication", closedNoPublication);
    if (basisObservation != null) result.add("basis", basisObservation.deepCopy());
    result.addProperty("prepares", entries.size());
    result.addProperty("legacyPrepares", legacyEntries.get());
    result.addProperty("passed", complete);
    result.addProperty("status", complete ? "PASS" : "FAIL");
    if (host != null) {
      result.addProperty("inputBytes", host.retainedInput().bytes());
      result.addProperty("inputObjects", host.retainedInput().resources());
      result.addProperty("peakInputBytes", host.retainedInput().peakBytes());
      result.addProperty("nativeBytes", host.nativeOwnership().bytes());
      result.addProperty("sceneBytes", host.retainedOutput().bytes());
    }
    return result;
  }

  @Override
  public void close() {
    for (var gate : gates.values()) gate.complete(null);
    if (browser != null) browser.close();
    if (workflow != null && workflow.isOpen()) workflow.close();
    if (sourceContext != null && sourceContext.isOpen()) sourceContext.close();
  }
}
