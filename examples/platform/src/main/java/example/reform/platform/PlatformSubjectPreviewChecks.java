package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.*;
import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.minecraft.client.ui.*;
import design.inherent.reform.owner.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.presentation.ui.*;
import design.inherent.reform.reference.*;
import design.inherent.reform.value.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Real native subject controls and mounted host viewport; no alternate actor/render coordinator.
 */
public final class PlatformSubjectPreviewChecks implements PlatformFormProbe.Scene {
  record Subject(int container, boolean looted, boolean open) {}

  record Entry(
      Subject subject,
      GraphicSourceStamp stamp,
      PlatformAuditionChecks.Profile candidate,
      long at) {}

  static final FieldKey<Integer> CONTAINER = FieldKey.of("container", ValueTypes.integer(0, 2));
  static final FieldKey<Boolean> LOOTED = FieldKey.of("looted", ValueTypes.bool()),
      OPEN = FieldKey.of("open", ValueTypes.bool());
  static final ValueType<Subject> SUBJECT =
      RecordType.<Subject>builder(v -> new Subject(v.get(CONTAINER), v.get(LOOTED), v.get(OPEN)))
          .field(CONTAINER, Subject::container)
          .field(LOOTED, Subject::looted)
          .field(OPEN, Subject::open)
          .build();
  static final DocumentId PAGE = DocumentId.parse("fixture:semantic_preview"),
      PROVIDER = DocumentId.parse("fixture:semantic_preview_provider");
  private static PlatformSubjectPreviewChecks observing;

  public static void draw(
      Object graphic, Object outcome, GraphicCamera camera, int x, int y, int width, int height) {
    var active = observing;
    if (active != null
        && graphic instanceof PreparedGraphic prepared
        && prepared.stamp().provider().equals(PROVIDER)
        && outcome == ClientMeshGraphic.DrawOutcome.RENDERED) {
      active.draws++;
      active.lastDraw = prepared.stamp();
      active.drawCamera = camera;
      active.viewportX = x;
      active.viewportY = y;
      active.viewportWidth = width;
      active.viewportHeight = height;
    }
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(registration, "PREVIEWS", PlatformSubjectPreviewChecks::new);
  }

  static CompletionStage<JsonObject> runBasis(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration, "PREVIEW_BASIS", driver -> new PlatformSubjectPreviewChecks(driver, true));
  }

  static CompletionStage<JsonObject> runSelections(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "PREVIEW_SELECTIONS",
        driver -> new PlatformSubjectPreviewChecks(driver, false, true));
  }

  private final boolean basisMode, inlineMode;
  private final AtomicBoolean sourceRead = new AtomicBoolean(true);
  private OwnerContext sourceContext;
  private ReferenceActions<String, List<String>> source;
  private ReferencePicker<PlatformAuditionChecks.Profile, List<String>> picker, artworkPicker;
  private boolean firstSelectionActivated,
      inspectionDidNotEdit,
      combinedDraft,
      sameValueActivated,
      restored;
  private static final PickerRole<PlatformAuditionChecks.Profile> MODELS_ROLE =
      new PickerRole<>("models", FieldPath.ROOT.field("models"));
  private static final PickerRole<PlatformAuditionChecks.Profile> ARTWORK_ROLE =
      new PickerRole<>("artwork", FieldPath.ROOT.field("artwork"));
  private boolean privateWithdrawn, explicitRecovery, noReturnPrepare;
  private final PlatformFormProbe.Driver driver;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final JsonArray checks = new JsonArray();
  private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
  private final AtomicBoolean blockNext = new AtomicBoolean();
  private final Set<Subject> combinations = new HashSet<>();
  private volatile CompletableFuture<Void> drain;
  private ClientEditingWorkflow workflow;
  private ClientEditor<PlatformAuditionChecks.Profile> editor;
  private ClientGraphicHost host;
  private PreviewController<PlatformAuditionChecks.Profile, Subject> preview;
  private SubjectForms.Form<Subject> subjects;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private ClientGraphicHost.Attempt held;
  private int stage, draws, viewportX, viewportY, viewportWidth, viewportHeight, combination;
  private long visiblePixels;
  private long frames, gesture, stoppedFrames = -1;
  private GraphicSourceStamp lastDraw;
  private GraphicCamera drawCamera, camera;
  private DraftRevision revision;
  private Path file;
  private byte[] bytes;
  private BasicFileAttributes attributes;
  private boolean complete;

  private PlatformSubjectPreviewChecks(PlatformFormProbe.Driver driver) {
    this(driver, false, false);
  }

  private PlatformSubjectPreviewChecks(PlatformFormProbe.Driver driver, boolean basisMode) {
    this(driver, basisMode, false);
  }

  private PlatformSubjectPreviewChecks(
      PlatformFormProbe.Driver driver, boolean basisMode, boolean inlineMode) {
    this.basisMode = basisMode;
    this.inlineMode = inlineMode;
    this.driver = driver;
    observing = this;
  }

  private Component text(String value) {
    return Component.literal(value);
  }

  private UiText label(String value) {
    return UiText.of(text(value));
  }

  private void check(String description, boolean valid) {
    if (!valid) throw new AssertionError(description);
    checks.add(description);
  }

  private void key(String value) {
    check(
        "native key reaches current control: " + value,
        NativeClientProbe.key(workflow.screen(), "key.keyboard." + value, true));
    NativeClientProbe.key(workflow.screen(), "key.keyboard." + value, false);
  }

  private void press(String caption) {
    var control = button(workflow.screen(), caption);
    check("actual control is enabled: " + caption, control.active);
    check("actual control accepts focus: " + caption, focus(workflow.screen(), control));
    key("space");
  }

  private Checkbox toggle(String caption) {
    return widgets(workflow.screen()).stream()
        .filter(Checkbox.class::isInstance)
        .map(Checkbox.class::cast)
        .filter(widget -> widget.getMessage().getString().equals(caption))
        .findFirst()
        .orElseThrow();
  }

  private Subject value() {
    return subjects.value().snapshot().value();
  }

  private void toggle(String caption, boolean selected) {
    var control = toggle(caption);
    if (control.selected() != selected) {
      check("native checkbox accepts focus: " + caption, focus(workflow.screen(), control));
      key("space");
    }
  }

  private void select(int container) {
    var control =
        widgets(workflow.screen()).stream()
            .filter(w -> w.getMessage().getString().equals("Container"))
            .filter(w -> !(w instanceof Button))
            .findFirst()
            .orElseThrow();
    check("subject list accepts native focus", focus(workflow.screen(), control));
    int before = value().container();
    key("home");
    for (int i = 0; i < container; i++) key("down");
    check("list navigation does not accept a subject", value().container() == before);
    key("enter");
  }

  private void focusPreview() {
    var widget =
        widgets(workflow.screen()).stream()
            .filter(w -> w.getMessage().getString().equals("Subject preview"))
            .findFirst()
            .orElseThrow();
    check("actual shared viewport accepts focus", focus(workflow.screen(), widget));
  }

  private boolean ready(int count) {
    return entries.size() == count
        && preview.state().snapshot().value().phase() == PreviewController.Phase.READY
        && lastDraw != null
        && lastDraw.request().equals(entries.getLast().stamp().request());
  }

  private void navigate(ClientDestination target, int next) {
    navigation = workflow.navigation().open(target).completion().toCompletableFuture();
    stage = next;
  }

  private boolean arrived() {
    if (!navigation.isDone()) return false;
    check(
        "actual navigation reveals target: " + navigation.join(),
        navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  private boolean empty() {
    return host.retainedInput().bytes() == 0
        && host.retainedOutput().bytes() == 0
        && host.nativeOwnership().bytes() == 0;
  }

  private ViewSpec component(UiScope scope, ClientEditor<PlatformAuditionChecks.Profile> admitted) {
    editor = admitted;
    subjects =
        SubjectForms.of(
            scope,
            DocumentId.parse("fixture:preview_subject"),
            SUBJECT,
            new Subject(0, false, false),
            List.of(
                new SubjectForms.Select<>(
                    "container",
                    label("Container"),
                    label(""),
                    CONTAINER.type(),
                    new UiLens<>(
                        Subject::container, (s, v) -> new Subject(v, s.looted(), s.open())),
                    List.of(
                        new SubjectForms.Choice<>("chest", label("Chest"), 0),
                        new SubjectForms.Choice<>("trapped", label("Trapped chest"), 1),
                        new SubjectForms.Choice<>("barrel", label("Barrel"), 2)),
                    3),
                new SubjectForms.Toggle<>(
                    "looted",
                    label("Looted"),
                    label(""),
                    new UiLens<>(
                        Subject::looted, (s, v) -> new Subject(s.container(), v, s.open()))),
                new SubjectForms.Toggle<>(
                    "open",
                    label("Open"),
                    label(""),
                    new UiLens<>(
                        Subject::open, (s, v) -> new Subject(s.container(), s.looted(), v)))));
    host = workflow.graphics(PlatformAuditionChecks.LIMITS, 1);
    var context = UUID.randomUUID();
    var generation = UUID.randomUUID();
    var provider =
        new ClientCapturedGraphicProvider<PlatformAuditionChecks.Profile, Subject>() {
          public Inputs inputs() {
            return Inputs.DRAFT_AND_AUDITION;
          }

          public DocumentId id() {
            return PROVIDER;
          }

          public ValueType<Subject> subjectType() {
            return SUBJECT;
          }

          public GraphicLimits limits() {
            return PlatformAuditionChecks.LIMITS;
          }

          public ReferenceStamp resourceStamp() {
            return new ReferenceStamp(context, PROVIDER, generation, "static-fixture");
          }

          public CompletionStage<GraphicScenePreparation> prepareCaptured(
              CapturedGraphicRequest<PlatformAuditionChecks.Profile, Subject> request,
              ClientGraphicWork work) {
            check("provider entry stays on native thread", client.isSameThread());
            return work.onWorker(
                () -> {
                  if (client.isSameThread())
                    throw new AssertionError("Material work entered native thread");
                  if (inlineMode) {
                    if (request.input().basis().kind() != GraphicSourceStamp.Kind.DRAFT)
                      throw new AssertionError("Inline selection must prepare the actual DRAFT");
                    if (request.input().candidate().value().distance() != 64)
                      throw new AssertionError("Selection discarded an unrelated draft field");
                  } else if (basisMode) {
                    var basis = request.input().basis();
                    if (!(basis instanceof GraphicInputBasis.Audition audition))
                      throw new AssertionError("A followed profile must not fall back to DRAFT");
                    var expected =
                        audition.role().equals("models")
                            ? new PlatformAuditionChecks.Profile(
                                "fixture:alternate", "fixture:base", 64)
                            : new PlatformAuditionChecks.Profile(
                                "fixture:base", "fixture:alternate", 64);
                    if (!request.input().candidate().value().equals(expected))
                      throw new AssertionError(
                          "Audition must overlay only its actual declared field");
                  } else if (!request
                      .input()
                      .candidate()
                      .value()
                      .equals(PlatformAuditionChecks.DEFINITION.defaults()))
                    throw new AssertionError("Subject controls changed complete settings input");
                  entries.add(
                      new Entry(
                          request.subject(),
                          request.stamp(),
                          request.input().candidate().value(),
                          System.nanoTime()));
                  if (blockNext.getAndSet(false)) {
                    var held = new CompletableFuture<Void>();
                    drain = held;
                    held.get(15, TimeUnit.SECONDS);
                  }
                  work.checkpoint();
                  return new GraphicScenePreparation.Ready(
                      new GraphicSceneData(
                          request.stamp(),
                          PlatformMeshChecks.pattern(),
                          new GraphicCamera(0, 0, 0.8, 0, 0),
                          List.of(
                              new GraphicSceneData.Fact(
                                  DocumentId.parse("fixture:subject"),
                                  "Subject",
                                  request.subject().toString()))));
                });
          }
        };
    preview =
        PreviewController.open(
            scope,
            admitted,
            host,
            provider,
            subjects,
            text("Subject preview"),
            PreviewController.Automatic.ACCEPTED_CHANGES);
    if (!basisMode && !inlineMode) return preview.content();
    sourceContext =
        new OwnerContext(
            client::execute,
            NativeClientProbe.ioPool(),
            () -> {
              if (!client.isSameThread()) throw new IllegalStateException("Client thread required");
            },
            OwnerContext.Limits.STANDARD);
    source =
        new ReferenceActions<>(
            sourceContext,
            sourceContext.access(sourceRead::get, () -> false),
            PlatformAuditionChecks.SOURCE,
            ValueTypes.text(128),
            ValueTypes.list(ValueTypes.text(128), 4).snapshotPolicy(),
            ReferenceLimits.STANDARD);
    bindSource();
    if (inlineMode) {
      picker =
          ReferencePicker.open(
              scope,
              admitted.actions(),
              PlatformAuditionChecks.SOURCE,
              () -> source,
              List.of(MODELS_ROLE),
              Map.of(),
              ReferencePicker.Policy.STANDARD,
              System::nanoTime);
      artworkPicker =
          ReferencePicker.open(
              scope,
              admitted.actions(),
              PlatformAuditionChecks.SOURCE,
              () -> source,
              List.of(ARTWORK_ROLE),
              Map.of(),
              ReferencePicker.Policy.STANDARD,
              System::nanoTime);
      preview.activateAfterSelection(List.of(picker, artworkPicker));
      return Views.column(
          "inline-selections",
          List.of(
              selectionView(scope, picker, MODELS_ROLE, "Model profiles"),
              selectionView(scope, artworkPicker, ARTWORK_ROLE, "Artwork profiles"),
              preview.content()));
    }
    picker =
        ReferencePicker.open(
            scope,
            admitted.actions(),
            PlatformAuditionChecks.SOURCE,
            () -> source,
            List.of(MODELS_ROLE, ARTWORK_ROLE),
            Map.of(),
            ReferencePicker.Policy.STANDARD,
            System::nanoTime);
    check(
        "shared follow binding does not stage or admit a source",
        preview.follow(picker, MODELS_ROLE, text("Models")) instanceof UiActionResult.Applied
            && entries.isEmpty());
    return Views.column(
        "profile-with-preview",
        List.of(
            ReferencePickerView.build(
                scope,
                picker,
                new ReferencePickerView.Presentation<>(
                    label("Profiles"),
                    label("Try a profile without editing settings."),
                    label("Available profiles"),
                    label("Search profiles"),
                    List.of(
                        new ReferencePickerView.RoleAction<>(MODELS_ROLE, label("Use for Models")),
                        new ReferencePickerView.RoleAction<>(
                            ARTWORK_ROLE, label("Use for Artwork"))),
                    4,
                    2),
                Optional.empty()),
            preview.content()));
  }

  private ViewSpec selectionView(
      UiScope scope,
      ReferencePicker<PlatformAuditionChecks.Profile, List<String>> selection,
      PickerRole<PlatformAuditionChecks.Profile> role,
      String caption) {
    return ReferencePickerView.build(
        scope,
        selection,
        new ReferencePickerView.Presentation<>(
            label(caption),
            label("Choose a value for this draft."),
            label(caption),
            label("Search profiles"),
            List.of(new ReferencePickerView.RoleAction<>(role, label("Use selection"))),
            4,
            2),
        Optional.of(role));
  }

  private void bindSource() {
    source.bind(
        "same-display-revision",
        stamp ->
            new ReferenceIndex<>(
                stamp,
                ValueTypes.text(128),
                ValueTypes.list(ValueTypes.text(128), 4).snapshotPolicy(),
                ReferenceLimits.STANDARD,
                List.of(
                    new ReferenceIndex.Entry<>(
                        "fixture:base", List.of("base"), "Base", "Base", Map.of()),
                    new ReferenceIndex.Entry<>(
                        "fixture:alternate",
                        List.of(),
                        "Private inherited",
                        "Private inherited",
                        Map.of()))));
  }

  public boolean tick() throws Exception {
    if (inlineMode && stage > 0) return tickSelections();
    if (basisMode && stage > 0) return tickBasis();
    switch (stage) {
      case 0 -> {
        var form = PlatformAuditionChecks.settings.form(text("Preview settings"));
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                PAGE, text("Preview controls"), Component.empty())),
                        List.of(),
                        List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("fixture:preview_settings"),
                                text("Settings"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                form.route(), form.route(), form.title(), Optional.empty())),
                        List.of())),
                List.of());
        var body =
            ClientCustomView.documentComponent(
                new ViewKey<>(
                    new DestinationId(PAGE),
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:preview_page"), ValueTypes.integer(0, 1))),
                0,
                UiLimits.STANDARD,
                form.route(),
                PlatformAuditionChecks.DEFINITION,
                (scope, owner, state) -> component(scope, owner));
        var metadata =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:preview_workspace"),
                        text("Typed preview controls")))
                .withView(body);
        workflow = ClientEditingWorkflow.open(parent, List.of(form), layout, metadata);
        NativeScreens.open(client, workflow.screen());
        frames = PlatformFrameObservations.sequence();
        stage++;
      }
      case 1 -> {
        if (PlatformFrameObservations.sequence() - frames < 8) return false;
        check("Home admits no subject controller or prepare", preview == null && entries.isEmpty());
        navigate(new ClientDestination.View(new DestinationId(PAGE)), 2);
      }
      case 2 -> {
        if (!arrived()
            || preview == null
            || preview.state().snapshot().value().phase() != PreviewController.Phase.IDLE)
          return false;
        check(
            "preview entry is idle and cancel inactive",
            entries.isEmpty() && !button(workflow.screen(), "Cancel preparation").active);
        check(
            "redundant Show preview is absent",
            widgets(workflow.screen()).stream()
                .noneMatch(w -> w.getMessage().getString().equals("Show preview")));
        revision = DraftRevision.of(editor.view().snapshot().orElseThrow());
        file =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/audition_profiles.json");
        bytes = Files.readAllBytes(file);
        attributes = Files.readAttributes(file, BasicFileAttributes.class);
        gesture = System.nanoTime();
        toggle("Open", true);
        toggle("Looted", true);
        focusPreview();
        check(
            "accepted checkbox gestures change one complete local subject",
            value().equals(new Subject(0, true, true)));
        stage++;
      }
      case 3 -> {
        if (!ready(1)) return false;
        check(
            "automatic input is debounced and coalesced on actual provider lane",
            entries.getFirst().subject().equals(new Subject(0, true, true))
                && entries.getFirst().at() - gesture >= 120_000_000L);
        check("READY cancel is inactive", !button(workflow.screen(), "Cancel preparation").active);
        camera = drawCamera;
        press("Rotate right");
        focusPreview();
        stage++;
      }
      case 4 -> {
        if (drawCamera.yaw() != camera.yaw() + 15) return false;
        check("camera gesture redraws without preparing", entries.size() == 1);
        camera = drawCamera;
        select(2);
        focusPreview();
        stage++;
      }
      case 5 -> {
        if (!ready(2)) return false;
        check(
            "accepted list selection retains independent flags and camera",
            value().equals(new Subject(2, true, true)) && drawCamera.equals(camera));
        driver.capture(
            "previews-ready.png",
            () -> {
              pixels("previews-ready.png");
              stage = 6;
            });
        stage = -1;
      }
      case 6 -> {
        int container = combination / 4;
        boolean looted = (combination % 4) / 2 == 1, open = combination % 2 == 1;
        select(container);
        toggle("Looted", looted);
        toggle("Open", open);
        focusPreview();
        stage = 7;
      }
      case 7 -> {
        if (!ready(3 + combination)) return false;
        var expected =
            new Subject(combination / 4, (combination % 4) / 2 == 1, combination % 2 == 1);
        check(
            "all typed state factors reach actual native drawing: " + expected,
            entries.getLast().subject().equals(expected)
                && value().equals(expected)
                && drawCamera.equals(camera));
        combinations.add(expected);
        combination++;
        stage = combination == 12 ? 8 : 6;
      }
      case 8 -> {
        blockNext.set(true);
        toggle("Open", !value().open());
        stage++;
      }
      case 9 -> {
        if (drain == null) return false;
        held = preview.attempt().orElseThrow();
        check(
            "entered automatic prepare remains input charged",
            host.retainedInput().bytes() > 0 && !held.completion().toCompletableFuture().isDone());
        press("Cancel preparation");
        stage++;
      }
      case 10 -> {
        check(
            "cancel is inactive while actual preparation drains",
            !button(workflow.screen(), "Cancel preparation").active
                && host.retainedInput().bytes() > 0
                && !held.completion().toCompletableFuture().isDone());
        drain.complete(null);
        stage++;
      }
      case 11 -> {
        if (!held.completion().toCompletableFuture().isDone() || !empty()) return false;
        if (stoppedFrames < 0) {
          stoppedFrames = PlatformFrameObservations.sequence();
          return false;
        }
        if (PlatformFrameObservations.sequence() - stoppedFrames < 4) return false;
        check(
            "cancelled operation has a freshly rendered terminal offer",
            preview.state().snapshot().value().phase() == PreviewController.Phase.CANCELLED);
        press("Prepare preview");
        check(
            "actual Prepare dispatch replaces the cancelled attempt",
            preview.attempt().orElseThrow() != held);
        focusPreview();
        stage++;
      }
      case 12 -> {
        if (!ready(16)) return false;
        navigate(new ClientDestination.Home(), 13);
        frames = PlatformFrameObservations.sequence();
      }
      case 13 -> {
        if (!arrived() || !empty() || PlatformFrameObservations.sequence() - frames < 8)
          return false;
        check(
            "hidden preview retires native/output/input without new prepares",
            entries.size() == 16);
        navigate(new ClientDestination.View(new DestinationId(PAGE)), 14);
      }
      case 14 -> {
        if (!arrived()
            || preview.state().snapshot().value().phase() != PreviewController.Phase.IDLE)
          return false;
        check(
            "return preserves local flags and owner without automatic preparation",
            entries.size() == 16
                && empty()
                && revision.equals(DraftRevision.of(editor.view().snapshot().orElseThrow())));
        press("Prepare preview");
        focusPreview();
        stage++;
      }
      case 15 -> {
        if (!ready(17)) return false;
        check("explicit return preparation keeps user camera", drawCamera.equals(camera));
        driver.capture(
            "previews-returned.png",
            () -> {
              pixels("previews-returned.png");
              stage = 16;
            });
        stage = -1;
      }
      case 16 -> {
        drain = null;
        blockNext.set(true);
        toggle("Open", !value().open());
        stage++;
      }
      case 17 -> {
        if (drain == null) return false;
        held = preview.attempt().orElseThrow();
        workflow.close();
        check(
            "closing mounted preview does not refund entered worker",
            !workflow.isOpen()
                && !editor.isOpen()
                && host.retainedInput().bytes() > 0
                && !held.completion().toCompletableFuture().isDone());
        drain.complete(null);
        stage++;
      }
      case 18 -> {
        if (!held.completion().toCompletableFuture().isDone() || !empty()) return false;
        var after = Files.readAttributes(file, BasicFileAttributes.class);
        check(
            "typed preview controls do not edit or persist settings",
            Arrays.equals(bytes, Files.readAllBytes(file))
                && attributes.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(attributes.fileKey(), after.fileKey()));
        check(
            "exact bounded provider entries and all twelve factor combinations",
            entries.size() == 18 && combinations.size() == 12 && host.releaseFailure().isEmpty());
        complete = true;
        NativeScreens.open(client, parent);
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean choicesReady(ReferencePicker<?, ?> selection) {
    var state = selection.state().snapshot().value();
    return state.source() == PickerState.Source.READY && state.window().choices().size() == 2;
  }

  private void choose(String caption, boolean alternate) {
    var list =
        widgets(workflow.screen()).stream()
            .filter(w -> w.getMessage().getString().equals(caption) && !(w instanceof Button))
            .findFirst()
            .orElseThrow();
    check("native inline list accepts focus: " + caption, focus(workflow.screen(), list));
    key("home");
    if (alternate) key("down");
    key("enter");
    focusPreview();
  }

  private boolean tickSelections() throws Exception {
    switch (stage) {
      case 1 -> {
        if (PlatformFrameObservations.sequence() - frames < 8) return false;
        check(
            "unopened Home has no selection controller or preparation",
            picker == null && entries.isEmpty());
        navigate(new ClientDestination.View(new DestinationId(PAGE)), 2);
      }
      case 2 -> {
        if (!arrived() || picker == null || !choicesReady(picker) || !choicesReady(artworkPicker))
          return false;
        check(
            "two inline choices share one idle complete-draft preview",
            entries.isEmpty() && !editor.dirty());
        file =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/audition_profiles.json");
        bytes = Files.readAllBytes(file);
        attributes = Files.readAttributes(file, BasicFileAttributes.class);
        choose("Model profiles", true);
        stage++;
      }
      case 3 -> {
        if (!ready(1)) return false;
        firstSelectionActivated =
            entries
                .getLast()
                .candidate()
                .equals(
                    new PlatformAuditionChecks.Profile("fixture:alternate", "fixture:base", 64));
        check(
            "first accepted inline choice activates complete DRAFT without prior Prepare",
            firstSelectionActivated
                && preview.state().snapshot().value().basis() == GraphicSourceStamp.Kind.DRAFT);
        revision = DraftRevision.of(editor.view().snapshot().orElseThrow());
        artworkPicker.inspect().invoke("fixture:alternate");
        frames = PlatformFrameObservations.sequence();
        stage++;
      }
      case 4 -> {
        if (artworkPicker.state().snapshot().value().inspecting() != PickerState.Phase.READY
            || PlatformFrameObservations.sequence() - frames < 12) return false;
        inspectionDidNotEdit =
            entries.size() == 1
                && artworkPicker.state().snapshot().value().accepted().isEmpty()
                && revision.equals(DraftRevision.of(editor.view().snapshot().orElseThrow()));
        check(
            "inspection alone neither stages Artwork nor activates draft preview",
            inspectionDidNotEdit);
        choose("Artwork profiles", true);
        stage++;
      }
      case 5 -> {
        if (!ready(2)) return false;
        combinedDraft =
            entries
                .getLast()
                .candidate()
                .equals(
                    new PlatformAuditionChecks.Profile(
                        "fixture:alternate", "fixture:alternate", 64));
        check("independent Artwork selection prepares both current draft fields", combinedDraft);
        choose("Model profiles", false);
        stage++;
      }
      case 6 -> {
        if (!ready(3)) return false;
        check(
            "changing Models preserves staged Artwork",
            entries
                .getLast()
                .candidate()
                .equals(
                    new PlatformAuditionChecks.Profile("fixture:base", "fixture:alternate", 64)));
        choose("Model profiles", false);
        stage++;
      }
      case 7 -> {
        if (!ready(4)) return false;
        sameValueActivated =
            entries.getLast().candidate().equals(entries.get(entries.size() - 2).candidate());
        check(
            "deliberate same-value acceptance is a new explicit preview gesture",
            sameValueActivated);
        driver.capture(
            "preview-selections-ready.png",
            () -> {
              pixels("preview-selections-ready.png");
              stage = 8;
            });
        stage = -1;
      }
      case 8 -> {
        navigate(new ClientDestination.Home(), 9);
        frames = PlatformFrameObservations.sequence();
      }
      case 9 -> {
        if (!arrived() || !empty() || PlatformFrameObservations.sequence() - frames < 8)
          return false;
        navigate(new ClientDestination.View(new DestinationId(PAGE)), 10);
        frames = PlatformFrameObservations.sequence();
      }
      case 10 -> {
        if (!arrived()
            || !choicesReady(picker)
            || !choicesReady(artworkPicker)
            || PlatformFrameObservations.sequence() - frames < 20) return false;
        noReturnPrepare = entries.size() == 4 && empty();
        check("return cannot replay an old successful selection", noReturnPrepare);
        press("Prepare preview");
        focusPreview();
        stage++;
      }
      case 11 -> {
        if (!ready(5)) return false;
        choose("Artwork profiles", false);
        stage++;
      }
      case 12 -> {
        if (!ready(6)) return false;
        restored =
            entries.getLast().candidate().equals(PlatformAuditionChecks.DEFINITION.defaults())
                && !editor.dirty();
        check("explicit actual selection restores the saved values without writing", restored);
        workflow.close();
        stage++;
      }
      case 13 -> {
        if (!empty()) return false;
        var after = Files.readAttributes(file, BasicFileAttributes.class);
        check(
            "inline selection does not persist settings",
            Arrays.equals(bytes, Files.readAllBytes(file))
                && attributes.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(attributes.fileKey(), after.fileKey()));
        check(
            "six exact complete-draft preparations retire without failure",
            entries.size() == 6 && host.releaseFailure().isEmpty());
        complete = true;
        NativeScreens.open(client, parent);
        return true;
      }
      default -> {}
    }
    return false;
  }

  private boolean inspectionReady() {
    var observed = picker.state().snapshot();
    return observed instanceof UiObservation.Available<PickerState>
        && observed.value().source() == PickerState.Source.READY
        && observed.value().inspecting() == PickerState.Phase.READY;
  }

  private boolean tickBasis() throws Exception {
    switch (stage) {
      case 1 -> {
        if (PlatformFrameObservations.sequence() - frames < 8) return false;
        check("Home has no audition source or provider", picker == null && entries.isEmpty());
        navigate(new ClientDestination.View(new DestinationId(PAGE)), 2);
      }
      case 2 -> {
        if (!arrived()
            || picker == null
            || picker.state().snapshot().value().source() != PickerState.Source.READY) return false;
        check("opening the browser does not prepare", entries.isEmpty());
        revision = DraftRevision.of(editor.view().snapshot().orElseThrow());
        file =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/audition_profiles.json");
        bytes = Files.readAllBytes(file);
        attributes = Files.readAttributes(file, BasicFileAttributes.class);
        picker.inspect().invoke("fixture:alternate");
        focusPreview();
        stage++;
      }
      case 3 -> {
        if (!ready(1)) return false;
        check(
            "resolved inspection automatically auditions Models with a guarded title",
            preview.state().snapshot().value().basis() == GraphicSourceStamp.Kind.AUDITION
                && preview
                    .state()
                    .snapshot()
                    .value()
                    .basisLabel()
                    .equals("Trying Private inherited for Models")
                && !editor.dirty());
        check(
            "role switch is explicit and does not stage",
            preview.follow(picker, ARTWORK_ROLE, text("Artwork"))
                instanceof UiActionResult.Applied);
        stage++;
      }
      case 4 -> {
        if (!ready(2)) return false;
        check(
            "role switch retains complete staged base",
            preview
                    .state()
                    .snapshot()
                    .value()
                    .basisLabel()
                    .equals("Trying Private inherited for Artwork")
                && revision.equals(DraftRevision.of(editor.view().snapshot().orElseThrow())));
        sourceRead.set(false);
        stage++;
      }
      case 5 -> {
        if (!empty()) return false;
        privateWithdrawn = preview.state().snapshot() instanceof UiObservation.Unavailable<?>;
        check(
            "READ revocation withdraws cached audition title, native output and source facts",
            privateWithdrawn
                && picker.state().snapshot().value().source() == PickerState.Source.UNAVAILABLE
                && picker.state().snapshot().value().inspection().isEmpty()
                && picker.state().snapshot().value().window().choices().isEmpty()
                && entries.size() == 2);
        sourceRead.set(true);
        picker.refresh().invoke(null);
        stage++;
      }
      case 6 -> {
        if (picker.state().snapshot().value().source() != PickerState.Source.READY) return false;
        picker.inspect().invoke("fixture:alternate");
        frames = PlatformFrameObservations.sequence();
        stage++;
      }
      case 7 -> {
        if (!inspectionReady() || PlatformFrameObservations.sequence() - frames < 12) return false;
        explicitRecovery = entries.size() == 2 && empty();
        check("read recovery cannot auto prepare or substitute a draft", explicitRecovery);
        press("Prepare preview");
        focusPreview();
        stage++;
      }
      case 8 -> {
        if (!ready(3)) return false;
        bindSource();
        stage++;
      }
      case 9 -> {
        if (!empty()) return false;
        check(
            "same text revision with a new source generation withdraws the old preview",
            entries.size() == 3);
        picker.refresh().invoke(null);
        stage++;
      }
      case 10 -> {
        if (picker.state().snapshot().value().source() != PickerState.Source.READY) return false;
        picker.inspect().invoke("fixture:alternate");
        frames = PlatformFrameObservations.sequence();
        stage++;
      }
      case 11 -> {
        if (!inspectionReady() || PlatformFrameObservations.sequence() - frames < 12) return false;
        check(
            "new source admission still requires explicit preparation",
            entries.size() == 3 && empty());
        press("Prepare preview");
        focusPreview();
        stage++;
      }
      case 12 -> {
        if (!ready(4)) return false;
        driver.capture(
            "preview-basis-ready.png",
            () -> {
              pixels("preview-basis-ready.png");
              stage = 13;
            });
        stage = -1;
      }
      case 13 -> {
        navigate(new ClientDestination.Home(), 14);
        frames = PlatformFrameObservations.sequence();
      }
      case 14 -> {
        if (!arrived() || !empty() || PlatformFrameObservations.sequence() - frames < 8)
          return false;
        navigate(new ClientDestination.View(new DestinationId(PAGE)), 15);
        frames = PlatformFrameObservations.sequence();
      }
      case 15 -> {
        if (!arrived() || !inspectionReady() || PlatformFrameObservations.sequence() - frames < 20)
          return false;
        noReturnPrepare = entries.size() == 4 && empty();
        check(
            "asynchronous pinned inspection restoration is not an audition gesture",
            noReturnPrepare);
        press("Prepare preview");
        focusPreview();
        stage++;
      }
      case 16 -> {
        if (!ready(5)) return false;
        check(
            "complete original settings and revision survive all auditions",
            !editor.dirty()
                && revision.equals(DraftRevision.of(editor.view().snapshot().orElseThrow())));
        workflow.close();
        stage++;
      }
      case 17 -> {
        if (!empty()) return false;
        var after = Files.readAttributes(file, BasicFileAttributes.class);
        check(
            "audition never writes settings",
            Arrays.equals(bytes, Files.readAllBytes(file))
                && attributes.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(attributes.fileKey(), after.fileKey()));
        check(
            "exact bounded audition entries and no cleanup failure",
            entries.size() == 5 && host.releaseFailure().isEmpty());
        complete = true;
        NativeScreens.open(client, parent);
        return true;
      }
      default -> {}
    }
    return false;
  }

  private void pixels(String filename) {
    try {
      var image =
          javax.imageio.ImageIO.read(
              client.gameDirectory.toPath().resolve("platform-" + filename).toFile());
      double sx = (double) image.getWidth() / workflow.screen().width;
      double sy = (double) image.getHeight() / workflow.screen().height;
      var counts = new long[4];
      for (int y = Math.max(0, (int) Math.ceil((viewportY + 4) * sy));
          y < Math.min(image.getHeight(), (int) Math.floor((viewportY + viewportHeight - 4) * sy));
          y++)
        for (int x = Math.max(0, (int) Math.ceil((viewportX + 4) * sx));
            x < Math.min(image.getWidth(), (int) Math.floor((viewportX + viewportWidth - 4) * sx));
            x++) {
          int pixel = image.getRGB(x, y),
              r = (pixel >>> 16) & 255,
              g = (pixel >>> 8) & 255,
              b = pixel & 255;
          if (r > 240 && g < 16 && b < 16) counts[0]++;
          if (r < 16 && g > 240 && b < 16) counts[1]++;
          if (r < 16 && g < 16 && b > 240) counts[2]++;
          if (r > 240 && g > 240 && b < 16) counts[3]++;
        }
      check(
          "actual viewport image contains all four fixture colors: " + filename,
          java.util.Arrays.stream(counts).allMatch(count -> count >= 16));
      visiblePixels += java.util.Arrays.stream(counts).sum();
    } catch (java.io.IOException failed) {
      throw new AssertionError("Cannot inspect actual preview image", failed);
    }
  }

  public JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "FAIL");
    result.addProperty("passed", complete);
    result.add("checks", checks.deepCopy());
    result.addProperty("stage", stage);
    result.addProperty("prepares", entries.size());
    result.addProperty("subjects", combinations.size());
    result.addProperty("firstSelectionActivated", firstSelectionActivated);
    result.addProperty("inspectionDidNotEdit", inspectionDidNotEdit);
    result.addProperty("combinedDraft", combinedDraft);
    result.addProperty("sameValueActivated", sameValueActivated);
    result.addProperty("restored", restored);
    result.addProperty("privateWithdrawn", privateWithdrawn);
    result.addProperty("explicitRecovery", explicitRecovery);
    result.addProperty("noReturnPrepare", noReturnPrepare);
    result.addProperty("draws", draws);
    result.addProperty("viewportX", viewportX);
    result.addProperty("viewportY", viewportY);
    result.addProperty("visiblePixels", visiblePixels);
    result.addProperty("viewportWidth", viewportWidth);
    result.addProperty("viewportHeight", viewportHeight);
    result.addProperty("inputBytes", host == null ? 0 : host.retainedInput().bytes());
    result.addProperty("nativeBytes", host == null ? 0 : host.nativeOwnership().bytes());
    result.addProperty("sceneBytes", host == null ? 0 : host.retainedOutput().bytes());
    return result;
  }

  public String diagnostic() {
    return "stage="
        + stage
        + " prepares="
        + entries.size()
        + " draws="
        + draws
        + " phase="
        + (preview == null
            ? "none"
            : workflow.isOpen() && editor.isOpen() ? preview.state().snapshot() : "closed");
  }

  public void close() {
    observing = null;
    if (drain != null) drain.complete(null);
    if (workflow != null && workflow.isOpen()) workflow.close();
    if (sourceContext != null) sourceContext.close();
  }
}
