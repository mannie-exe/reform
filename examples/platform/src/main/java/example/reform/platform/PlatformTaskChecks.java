package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.*;
import design.inherent.reform.value.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual registered task/body, single owner, two source-bound choices and real native preview. */
public final class PlatformTaskChecks {
  record Config(String models, String artwork) {}

  private static final DocumentId SOURCE = DocumentId.parse("fixture:task_profiles");
  private static final ValueType<String> REF = ValueTypes.reference(ValueTypes.text(128), SOURCE);
  private static final FieldKey<String> MODELS = FieldKey.of("models", REF),
      ARTWORK = FieldKey.of("artwork", REF);
  private static final DocumentDefinition<Config> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("reform_platform_example:task_profiles"),
          1,
          RecordType.<Config>builder(values -> new Config(values.get(MODELS), values.get(ARTWORK)))
              .field(MODELS, Config::models)
              .field(ARTWORK, Config::artwork)
              .build(),
          () -> new Config("fixture:default", "fixture:default"));
  private static final DestinationId PROFILES = DestinationId.parse("fixture:profiles");
  private static final GraphicLimits LIMITS =
      new GraphicLimits(65536, 65536, 1024 * 1024, 64, 16 * 1024 * 1024);
  private static ClientFileSettings<Config> settings;
  private static Object watched;
  private static int draws;
  private static boolean suppress;

  public static boolean suppressDraw(Object graphic) {
    return suppress && (watched == null || graphic == watched);
  }

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
  }

  public static void recordDraw(Object graphic, Object outcome) {
    if (graphic == watched && "RENDERED".equals(String.valueOf(outcome))) draws++;
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final AtomicInteger sources = new AtomicInteger(),
      providers = new AtomicInteger(),
      sourceCloses = new AtomicInteger(),
      providerCloses = new AtomicInteger(),
      prepares = new AtomicInteger();
  private final AtomicReference<Config> captured = new AtomicReference<>();
  private final UUID resources = UUID.randomUUID(), generation = UUID.randomUUID();
  private ClientEditingWorkflow workflow;
  private ReferenceActions<String, String> references;
  private final ReferenceTitles titles = new ReferenceTitles(titleMap("Default"));
  private Object initialSearch;
  private long titleDraftVersion;
  private CompletableFuture<?> pending;
  private byte[] bytes;
  private ClientEditor<?> actual;
  private PreparedGraphic ready;
  private EditBox retainedQuery, retainedManual;
  private int oldWidth, oldHeight;
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private int stage;
  private boolean discarding;

  PlatformTaskChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private byte[] source() throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_platform_example/task_profiles.json"));
  }

  private static Map<String, Component> titleMap(String defaultTitle) {
    return Map.of(
        "fixture:default",
        Component.literal(defaultTitle),
        "fixture:blue",
        Component.literal("Blue"),
        "fixture:inherited",
        Component.literal("Inherited"));
  }

  private String requestedTitle(String field) {
    return taskRow("detail:requested:" + FieldPath.ROOT.field(field));
  }

  private String taskRow(String key) {
    for (var child : workflow.screen().children()) {
      if (!child.getClass().getSimpleName().equals("FormPanel")) continue;
      for (var at : (List<?>) observed(child, "layout")) {
        var row = observed(at, "row");
        if (key.equals(observed(row, "key")))
          return ((Component) observed(row, "summary")).getString();
      }
    }
    return "";
  }

  private Object taskSearch() {
    var views = (Map<?, ?>) observed(workflow, "taskViews");
    var entry = views.values().iterator().next();
    return observed(observed(entry, "view"), "search");
  }

  private String raw(String name) {
    return ((DraftNode.Scalar)
            workflow
                .editor(DEFINITION.id())
                .view()
                .snapshot()
                .orElseThrow()
                .draft()
                .at(FieldPath.ROOT.field(name)))
        .raw();
  }

  private ClientGraphicHost.View<?, ?> graphic() {
    var values = ((Map<?, ?>) observed(workflow.graphics(LIMITS, 2), "views")).values();
    if (values.size() != 1) throw new AssertionError("Expected one actual task preview view");
    return (ClientGraphicHost.View<?, ?>) values.iterator().next();
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (settings.runtime().isEmpty()) return false;
        bytes = source();
        references =
            settings.references(
                SOURCE, ValueTypes.text(128), SnapshotPolicy.immutable(), ReferenceLimits.STANDARD);
        pending =
            CompletableFuture.completedFuture(
                references.bind(
                    "task-native",
                    stamp ->
                        new ReferenceIndex<>(
                            stamp,
                            ValueTypes.text(128),
                            SnapshotPolicy.immutable(),
                            ReferenceLimits.STANDARD,
                            List.of(
                                new ReferenceIndex.Entry<>(
                                    "fixture:default",
                                    "default",
                                    "Default",
                                    "Default",
                                    Map.of("models", "2", "artwork", "2")),
                                new ReferenceIndex.Entry<>(
                                    "fixture:blue",
                                    "blue",
                                    "Blue",
                                    "Blue",
                                    Map.of("models", "1", "artwork", "1")),
                                new ReferenceIndex.Entry<>(
                                    "fixture:inherited",
                                    "",
                                    "Inherited",
                                    "Inherited",
                                    Map.of("models", "0", "artwork", "0"))))));
        stage++;
      }
      case 1 -> {
        if (!pending.isDone()) return false;
        pending.join();
        var declaration =
            new ClientReferenceTask<>(
                    DEFINITION.id(),
                    DEFINITION,
                    SOURCE,
                    ReferenceLimits.STANDARD,
                    List.of(
                        new ClientReferenceTask.Selection(
                            FieldPath.ROOT.field("models"),
                            Component.literal("Models"),
                            Component.literal("Use for Models")),
                        new ClientReferenceTask.Selection(
                            FieldPath.ROOT.field("artwork"),
                            Component.literal("Artwork"),
                            Component.literal("Use for Artwork"))),
                    (ClientViewContext scope, ClientEditor<Config> editor) -> {
                      sources.incrementAndGet();
                      scope.onClose(
                          () -> {
                            sourceCloses.incrementAndGet();
                            if (discarding) {
                              check.accept(
                                  "task cleanup sees its actual owner closed", !editor.isOpen());
                              check.accept(
                                  "closing owner is truthfully unavailable during cleanup",
                                  workflow
                                          .navigation()
                                          .inspect(new ClientDestination.View(PROFILES))
                                          .kind()
                                      == DestinationAvailability.Kind.CLOSED);
                              try {
                                workflow.discard(DEFINITION.id());
                                throw new AssertionError("Recursive owner retirement entered");
                              } catch (PolicyRefusal expected) {
                                check.accept(
                                    "task cleanup cannot recursively discard its retiring owner",
                                    expected.report().issues().stream()
                                        .anyMatch(
                                            issue ->
                                                issue.code().equals("client.editor_retiring")));
                              }
                              try {
                                workflow.editor(DEFINITION.id());
                                throw new AssertionError("Retiring owner was reopened");
                              } catch (PolicyRefusal expected) {
                                check.accept(
                                    "task cleanup cannot reopen its retiring owner",
                                    expected.report().issues().stream()
                                        .anyMatch(
                                            issue ->
                                                issue.code().equals("client.editor_retiring")));
                              }
                            }
                          });
                      return references;
                    })
                .withHelp(
                    Component.literal(
                        "Inspect a profile, then explicitly choose which draft field to change."))
                .withGraphic(
                    new ClientTaskGraphic<>(
                        Component.literal("Draft preview"),
                        LIMITS,
                        2,
                        ValueTypes.integer(1, 2),
                        List.of(
                            new ClientTaskGraphic.Choice<>(
                                "first", Component.literal("First subject"), 1),
                            new ClientTaskGraphic.Choice<>(
                                "second",
                                Component.literal("Second subject, unlooted, closed"),
                                2)),
                        (scope, editor) -> {
                          providers.incrementAndGet();
                          scope.onClose(providerCloses::incrementAndGet);
                          return new ClientGraphicProvider<Config, Integer>() {
                            public DocumentId id() {
                              return DocumentId.parse("fixture:task_graphic");
                            }

                            public ValueType<Integer> subjectType() {
                              return ValueTypes.integer(1, 2);
                            }

                            public GraphicLimits limits() {
                              return LIMITS;
                            }

                            public ReferenceStamp resourceStamp() {
                              return new ReferenceStamp(resources, id(), generation, "task-native");
                            }

                            public CompletionStage<GraphicScenePreparation> prepare(
                                GraphicRequest<Config, Integer> request, ClientGraphicWork work) {
                              prepares.incrementAndGet();
                              return work.onWorker(
                                  () -> {
                                    if (client.isSameThread())
                                      throw new AssertionError(
                                          "Task provider worker ran on client");
                                    var value = request.input().candidate().value();
                                    captured.set(value);
                                    return new GraphicScenePreparation.Ready(
                                        new GraphicSceneData(
                                            request.stamp(),
                                            PlatformMeshChecks.pattern(),
                                            new GraphicCamera(0, 0, 0.8, 0, 0),
                                            List.of(
                                                new GraphicSceneData.Fact(
                                                    DocumentId.parse("fixture:task_models"),
                                                    "Captured Models",
                                                    value.models()),
                                                new GraphicSceneData.Fact(
                                                    DocumentId.parse("fixture:task_artwork"),
                                                    "Captured Artwork",
                                                    value.artwork()))));
                                  });
                            }
                          };
                        }));
        var form =
            settings.form(
                Component.literal("Appearance"),
                new ClientFormOptions<Config>(FormPresentation.EMPTY, Map.of(SOURCE, references))
                    .withReferenceTitles(SOURCE, titles));
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("fixture:settings"),
                                Component.literal("Settings"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                DocumentId.parse("fixture:appearance"),
                                DEFINITION.id(),
                                Component.literal("Appearance"),
                                Optional.empty())),
                        List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                PROFILES.value(),
                                Component.literal("Profiles"),
                                Component.empty())),
                        List.of(),
                        List.of())),
                List.of());
        var presentation =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:tasks"), Component.literal("Task workspace")))
                .withReferenceTask(PROFILES, declaration);
        check.accept(
            "task metadata admission enters no source/provider factories",
            presentation.validate(List.of(form), layout).valid()
                && sources.get() == 0
                && providers.get() == 0);
        var invalid =
            ClientWorkspacePresentation.standard(presentation.identity())
                .withReferenceTask(DestinationId.parse("fixture:missing_task"), declaration);
        check.accept(
            "task admission refuses an undeclared category before any factory",
            !invalid.validate(List.of(form), layout).valid() && sources.get() == 0);
        try {
          presentation.withReferenceTask(PROFILES, declaration);
          throw new AssertionError("Duplicate task body was admitted");
        } catch (IllegalArgumentException expected) {
          check.accept("task registration refuses accidental replacement", true);
        }
        workflow = ClientEditingWorkflow.open(parent, List.of(form), layout, presentation);
        NativeScreens.open(client, workflow.screen());
        check.accept(
            "Home keeps task owner and factories unopened",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened)
                && sources.get() == 0
                && providers.get() == 0);
        pending =
            workflow
                .navigation()
                .open(new ClientDestination.View(PROFILES))
                .completion()
                .thenApply(
                    result -> {
                      check.accept(
                          "registered task acknowledgement sees actual mounted search control",
                          result.outcome() == NavigationAttempt.Outcome.REVEALED
                              && input(workflow.screen(), "Search") != null);
                      return result;
                    })
                .toCompletableFuture();
        stage++;
      }
      case 2 -> {
        if (!pending.isDone()) return false;
        pending.join();
        if (sources.get() == 0 || providers.get() == 0) return false;
        var choices =
            widgets(workflow.screen()).stream()
                .filter(w -> w.getMessage().getString().equals("Blue"))
                .toList();
        if (choices.isEmpty()) return false;
        geometry(workflow.screen());
        actual = workflow.editor(DEFINITION.id());
        var ownerNotice = (Component) observed(actual.form(), "notice");
        check.accept(
            "task owner scope does not attempt to focus hidden settings",
            ownerNotice == null
                || !ownerNotice.getString().contains("That destination is unavailable"));
        check.accept(
            "task entry keeps one actual editor and prepares nothing implicitly",
            workflow.documents().stream()
                        .filter(ClientEditingWorkflow.DocumentState::opened)
                        .count()
                    == 1
                && sources.get() == 1
                && providers.get() == 1
                && prepares.get() == 0
                && Arrays.equals(bytes, source()));
        check.accept(
            "task requested fields use cached friendly titles while preserving actual IDs",
            requestedTitle("models").equals("Draft value: Default")
                && requestedTitle("artwork").equals("Draft value: Default")
                && raw("models").equals("fixture:default")
                && raw("artwork").equals("fixture:default"));
        var disclose = button(workflow.screen(), "Source details");
        check.accept(
            "requested-ID disclosure is in the actual native focus tree",
            focus(workflow.screen(), disclose));
        click(workflow.screen(), disclose);
        check.accept(
            "friendly requested titles retain both exact IDs under explicit details",
            taskRow("detail:requested-id:" + FieldPath.ROOT.field("models"))
                    .equals("fixture:default")
                && taskRow("detail:requested-id:" + FieldPath.ROOT.field("artwork"))
                    .equals("fixture:default"));
        var collapse = button(workflow.screen(), "Hide source details");
        check.accept(
            "requested-ID disclosure closes through the actual native control",
            focus(workflow.screen(), collapse));
        click(workflow.screen(), collapse);
        initialSearch = taskSearch();
        titleDraftVersion = actual.view().snapshot().orElseThrow().version();
        titles.replace(titleMap("Renamed Default"));
        stage = 30;
      }
      case 30 -> {
        if (!requestedTitle("models").equals("Draft value: Renamed Default")) return false;
        check.accept(
            "cached title replacement changes presentation without querying or editing",
            taskSearch() == initialSearch
                && actual.view().snapshot().orElseThrow().version() == titleDraftVersion
                && !actual.dirty()
                && prepares.get() == 0
                && sources.get() == 1
                && Arrays.equals(bytes, source()));
        titles.replace(Map.of("fixture:blue", Component.literal("Blue")));
        stage = 31;
      }
      case 31 -> {
        if (!requestedTitle("models").equals("Draft value: fixture:default")) return false;
        check.accept(
            "missing cached title preserves requested ID without inventing source validity",
            taskSearch() == initialSearch
                && raw("models").equals("fixture:default")
                && actual.view().snapshot().orElseThrow().version() == titleDraftVersion
                && Arrays.equals(bytes, source()));
        titles.replace(titleMap("Default"));
        stage = 32;
      }
      case 32 -> {
        if (!requestedTitle("models").equals("Draft value: Default")) return false;
        check.accept(
            "title repair retains the same source handle and unchanged draft",
            taskSearch() == initialSearch && !actual.dirty());
        retainedQuery = input(workflow.screen(), "Search");
        retainedManual = input(workflow.screen(), "Reference ID");
        replace(workflow.screen(), retainedQuery, "Blue");
        oldWidth = workflow.screen().width;
        oldHeight = workflow.screen().height;
        NativeClientProbe.size(client, windowWidth, windowHeight);
        NativeClientProbe.resize(client, 320, 240);
        stage = 14;
      }
      case 3 -> {
        var use = button(workflow.screen(), "Use for Models");
        if (!use.active) return false;
        check.accept(
            "inspection never stages either actual field",
            raw("models").equals("fixture:default")
                && raw("artwork").equals("fixture:default")
                && !actual.dirty());
        var information =
            widgets(workflow.screen()).stream()
                .filter(widget -> widget.getClass().getSimpleName().equals("InformationButton"))
                .findFirst()
                .orElseThrow();
        check.accept(
            "task exposes actual field information beside requested values",
            focus(workflow.screen(), information));
        click(workflow.screen(), information);
        stage = 17;
      }
      case 4 -> {
        if (actual.busy() || !raw("models").equals("fixture:blue")) return false;
        var inherited = button(workflow.screen(), "Inherited");
        if (!inherited.active || !inherited.visible) return false;
        check.accept(
            "choosing Models preserves independent Artwork and original file",
            raw("artwork").equals("fixture:default") && Arrays.equals(bytes, source()));
        check.accept(
            "next independent choice is natively focusable and revealed",
            focus(workflow.screen(), inherited));
        click(workflow.screen(), inherited);
        stage++;
      }
      case 5 -> {
        var use = button(workflow.screen(), "Use for Artwork");
        if (!use.active) return false;
        click(workflow.screen(), use);
        stage++;
      }
      case 6 -> {
        if (actual.busy() || !raw("artwork").equals("fixture:inherited")) return false;
        check.accept(
            "empty contribution facts do not block a valid independent choice",
            raw("models").equals("fixture:blue")
                && Arrays.equals(bytes, source())
                && prepares.get() == 0);
        stage = 20;
        capture.accept(
            "task-catalogue.png",
            () -> {
              check.accept(
                  "unprepared preview controls can be focused and revealed",
                  focus(workflow.screen(), button(workflow.screen(), "Show preview")));
              click(workflow.screen(), button(workflow.screen(), "Show preview"));
              capture.accept(
                  "task-preview-before-prepare.png",
                  () -> {
                    var prepare = button(workflow.screen(), "Prepare draft preview");
                    check.accept(
                        "task preview action can be focused and revealed",
                        focus(workflow.screen(), prepare));
                    suppress = true;
                    click(workflow.screen(), prepare);
                    stage = 7;
                  });
            });
      }
      case 7 -> {
        if (graphic().snapshot().state() != ClientGraphicHost.State.READY) return false;
        ready = ((GraphicPreparation.Ready) graphic().preparation().orElseThrow()).graphic();
        watched = ready;
        draws = 0;
        check.accept(
            "task preview captures complete unsaved owner rather than highlighted replacement",
            prepares.get() == 1
                && captured.get().equals(new Config("fixture:blue", "fixture:inherited"))
                && Arrays.equals(bytes, source()));
        check.accept(
            "preview camera controls belong to actual native tree",
            focus(workflow.screen(), button(workflow.screen(), "Show preview")));
        click(workflow.screen(), button(workflow.screen(), "Show preview"));
        stage = 22;
      }
      case 22 -> {
        if (!previewFocused()) return false;
        assertViewportVisible();
        var viewport =
            widgets(workflow.screen()).stream()
                .filter(widget -> widget.getMessage().getString().equals("Draft preview"))
                .findFirst()
                .orElseThrow();
        check.accept(
            "read-only preview has current native focus and ready-state narration",
            viewport.isFocused()
                && NativeClientProbe.narration(viewport::updateNarration)
                    .contains("Preview ready"));
        stage = 20;
        capture.accept(
            "task-preview-pre-draw.png",
            () -> {
              suppress = false;
              stage = 8;
            });
      }
      case 8 -> {
        if (draws == 0) return false;
        geometry(workflow.screen());
        check.accept(
            "registered preview widget actually submits its own prepared graphic",
            draws > 0 && actual == workflow.editor(DEFINITION.id()));
        NativeClientProbe.resize(client, 320, 240);
        stage = 19;
      }
      case 9 -> {
        if (workflow.graphics(LIMITS, 2).nativeOwnership().bytes() != 0
            || workflow.graphics(LIMITS, 2).retainedOutput().bytes() != 0) return false;
        check.accept(
            "leaving task retires actual preview and preserves dirty editor without recompile",
            actual == workflow.editor(DEFINITION.id())
                && actual.dirty()
                && prepares.get() == 1
                && Arrays.equals(bytes, source()));
        pending =
            workflow
                .navigation()
                .open(new ClientDestination.View(PROFILES))
                .completion()
                .toCompletableFuture();
        stage++;
      }
      case 10 -> {
        if (!pending.isDone()) return false;
        check.accept(
            "returning task restores same owner and cached factories without preparation",
            ((NavigationAttempt.Result) pending.join()).outcome()
                    == NavigationAttempt.Outcome.REVEALED
                && workflow.editor(DEFINITION.id()) == actual
                && sources.get() == 1
                && providers.get() == 1
                && prepares.get() == 1);
        discarding = true;
        try {
          workflow.discard(DEFINITION.id());
        } finally {
          discarding = false;
        }
        check.accept(
            "explicit owner discard retires its cached task scopes immediately",
            !actual.isOpen() && sourceCloses.get() == 1 && providerCloses.get() == 1);
        pending =
            workflow
                .navigation()
                .open(new ClientDestination.View(PROFILES))
                .completion()
                .toCompletableFuture();
        stage = 16;
      }
      case 11 -> {
        check.accept(
            "workflow retirement closes all task factory scopes but not borrowed source",
            !actual.isOpen()
                && sourceCloses.get() == 2
                && providerCloses.get() == 2
                && references.isOpen()
                && ready.releaseCompletion().toCompletableFuture().isDone()
                && Arrays.equals(bytes, source()));
        watched = null;
        suppress = false;
        stage++;
        return true;
      }
      case 12 -> {
        return true;
      }
      case 13 -> {
        var subject =
            widgets(workflow.screen()).stream()
                .filter(
                    widget ->
                        widget
                            .getMessage()
                            .getString()
                            .contains("Second subject, unlooted, closed"))
                .findFirst()
                .orElseThrow();
        check.accept(
            "wide task offers the complete actual long subject caption",
            subject.getWidth() >= client.font.width(subject.getMessage()) + 4);
        check.accept(
            "long subject retains its complete native narration",
            NativeClientProbe.narration(subject::updateNarration)
                .contains("Second subject, unlooted, closed"));
        check.accept(
            "changing subject invalidates old preview without preparing another",
            graphic().snapshot().state() == ClientGraphicHost.State.STALE
                && prepares.get() == 1
                && ready.state().closing());
        workflow.showHome();
        stage = 9;
      }
      case 14 -> {
        if (workflow.screen().width > 400 || workflow.screen().width >= oldWidth) return false;
        geometry(workflow.screen());
        var task =
            observed(
                ((Map<?, ?>) observed(workflow, "taskViews")).values().iterator().next(), "view");
        var regions = observed(observed(task, "panels"), "regions");
        var narrowGraphic =
            widgets(workflow.screen()).stream()
                .filter(widget -> widget.getMessage().getString().equals("Draft preview"))
                .findFirst()
                .orElseThrow();
        check.accept(
            "graphic preferred height fits the actual narrow task viewport",
            narrowGraphic.getHeight()
                <= ((design.inherent.reform.presentation.TaskPresentation.Regions) regions)
                    .primary()
                    .height());
        check.accept(
            "task narrow reflow retains actual query and manual controls",
            input(workflow.screen(), "Search") == retainedQuery
                && input(workflow.screen(), "Reference ID") == retainedManual
                && retainedQuery.getValue().equals("Blue")
                && ((design.inherent.reform.presentation.TaskPresentation.Regions) regions)
                        .arrangement()
                    == design.inherent.reform.presentation.TaskPresentation.Arrangement.FOCUSED);
        NativeClientProbe.resize(client, windowWidth[0], windowHeight[0]);
        stage = 15;
      }
      case 15 -> {
        if (workflow.screen().width != oldWidth || workflow.screen().height != oldHeight)
          return false;
        geometry(workflow.screen());
        check.accept(
            "task return reflow preserves source owner and no implicit preparation",
            input(workflow.screen(), "Search") == retainedQuery
                && input(workflow.screen(), "Reference ID") == retainedManual
                && retainedQuery.getValue().equals("Blue")
                && workflow.editor(DEFINITION.id()) == actual
                && sources.get() == 1
                && providers.get() == 1
                && prepares.get() == 0
                && Arrays.equals(bytes, source()));
        var blue = button(workflow.screen(), "Blue");
        check.accept(
            "task returned choice can be focused and revealed", focus(workflow.screen(), blue));
        click(workflow.screen(), blue);
        stage = 3;
      }
      case 16 -> {
        if (!pending.isDone() || sources.get() != 2 || providers.get() != 2) return false;
        var fresh = workflow.editor(DEFINITION.id());
        check.accept(
            "reopening a discarded task binds only the fresh owner and retained source",
            ((NavigationAttempt.Result) pending.join()).outcome()
                    == NavigationAttempt.Outcome.REVEALED
                && fresh != actual
                && !fresh.dirty()
                && sources.get() == 2
                && providers.get() == 2
                && raw("models").equals("fixture:default")
                && raw("artwork").equals("fixture:default")
                && prepares.get() == 1
                && references.isOpen()
                && Arrays.equals(bytes, source()));
        actual = fresh;
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 11;
      }
      case 17 -> {
        if (workflow.inspectedField().isEmpty()) return false;
        geometry(workflow.screen());
        check.accept(
            "task field information uses actual owner without staging or preparing",
            workflow.inspectedField().orElseThrow().path().equals(FieldPath.ROOT.field("models"))
                && workflow.editor(DEFINITION.id()) == actual
                && !actual.dirty()
                && prepares.get() == 0
                && Arrays.equals(bytes, source()));
        var close = button(workflow.screen(), "Close information");
        check.accept(
            "task information close is mounted and revealable", focus(workflow.screen(), close));
        click(workflow.screen(), close);
        stage = 18;
      }
      case 18 -> {
        if (workflow.inspectedField().isPresent()) return false;
        var use = button(workflow.screen(), "Use for Models");
        if (!use.active) return false;
        check.accept(
            "task returns from field inspection to the same staged-choice service",
            focus(workflow.screen(), use));
        click(workflow.screen(), use);
        stage = 4;
      }
      case 19 -> {
        if (workflow.screen().width > 400 || workflow.screen().width >= oldWidth) return false;
        var show = button(workflow.screen(), "Show preview");
        check.accept(
            "prepared narrow preview navigation remains keyboard revealable",
            focus(workflow.screen(), show));
        click(workflow.screen(), show);
        stage = 23;
      }
      case 23 -> {
        if (!previewFocused()) return false;
        assertViewportVisible();
        check.accept(
            "narrow preview reflow retains the exact prepared scene without compilation",
            ((GraphicPreparation.Ready) graphic().preparation().orElseThrow()).graphic() == ready
                && prepares.get() == 1);
        stage = 20;
        capture.accept(
            "task-preview-small.png",
            () -> {
              assertPatternPixels("task-preview-small.png");
              NativeClientProbe.resize(client, windowWidth[0], windowHeight[0]);
              stage = 21;
            });
      }
      case 20 -> {}
      case 21 -> {
        if (workflow.screen().width != oldWidth || workflow.screen().height != oldHeight)
          return false;
        var show = button(workflow.screen(), "Show preview");
        check.accept(
            "returned preview navigation remains keyboard revealable",
            focus(workflow.screen(), show));
        click(workflow.screen(), show);
        stage = 24;
      }
      case 24 -> {
        if (!previewFocused()) return false;
        assertViewportVisible();
        check.accept(
            "return from narrow preserves image focus and one complete unsaved preparation",
            ((GraphicPreparation.Ready) graphic().preparation().orElseThrow()).graphic() == ready
                && prepares.get() == 1
                && Arrays.equals(bytes, source()));
        stage = 20;
        capture.accept(
            "task-preview.png",
            () -> {
              assertPreviewPixels();
              var subject =
                  widgets(workflow.screen()).stream()
                      .filter(widget -> widget.getMessage().getString().contains("First subject"))
                      .findFirst()
                      .orElseThrow();
              check.accept(
                  "task subject can be focused and revealed", focus(workflow.screen(), subject));
              click(workflow.screen(), subject);
              stage = 13;
            });
      }
      default -> throw new AssertionError("Unknown registered task stage " + stage);
    }
    return false;
  }

  private boolean previewFocused() {
    return widgets(workflow.screen()).stream()
        .anyMatch(
            widget ->
                widget.getMessage().getString().equals("Draft preview") && widget.isFocused());
  }

  private void assertPreviewPixels() {
    try {
      var image =
          javax.imageio.ImageIO.read(
              client.gameDirectory.toPath().resolve("platform-task-preview.png").toFile());
      var before =
          javax.imageio.ImageIO.read(
              client.gameDirectory.toPath().resolve("platform-task-preview-pre-draw.png").toFile());
      var catalogue =
          javax.imageio.ImageIO.read(
              client.gameDirectory.toPath().resolve("platform-task-catalogue.png").toFile());
      var scrolled =
          javax.imageio.ImageIO.read(
              client
                  .gameDirectory
                  .toPath()
                  .resolve("platform-task-preview-before-prepare.png")
                  .toFile());
      var blue = button(workflow.screen(), "Blue");
      var initialGlyphs = glyphs(catalogue, blue);
      var scrollGlyphs = glyphs(scrolled, blue);
      var preparedGlyphs = glyphs(before, blue);
      var drawnGlyphs = glyphs(image, blue);
      if (!initialGlyphs.equals(scrollGlyphs)
          || !initialGlyphs.equals(preparedGlyphs)
          || !initialGlyphs.equals(drawnGlyphs))
        throw new AssertionError(
            "Task text changed outside preview: catalogue="
                + initialGlyphs.cardinality()
                + " scroll="
                + scrollGlyphs.cardinality()
                + " prepare="
                + preparedGlyphs.cardinality()
                + " draw="
                + drawnGlyphs.cardinality());
      check.accept(
          "private preview preserves unrelated native text pixels",
          initialGlyphs.cardinality() > 10);
      assertPatternPixels("task-preview.png");
      check.accept("root-mounted task preview displays all four expected texture quadrants", true);
    } catch (java.io.IOException failed) {
      throw new AssertionError("Task screenshot could not be inspected", failed);
    }
  }

  private static boolean white(int value) {
    return ((value >>> 16) & 255) > 239 && ((value >>> 8) & 255) > 239 && (value & 255) > 239;
  }

  private BitSet glyphs(
      java.awt.image.BufferedImage image,
      net.minecraft.client.gui.components.AbstractWidget widget) {
    var mask = new BitSet();
    double sx = image.getWidth() / (double) workflow.screen().width;
    double sy = image.getHeight() / (double) workflow.screen().height;
    int offset = 0;
    for (int y = (int) ((widget.getY() + 3) * sy);
        y < (widget.getY() + widget.getHeight() - 3) * sy;
        y++)
      for (int x = (int) ((widget.getX() + 3) * sx);
          x < (widget.getX() + widget.getWidth() - 3) * sx;
          x++) mask.set(offset++, white(image.getRGB(x, y)));
    return mask;
  }

  private void assertViewportVisible() {
    var task =
        observed(((Map<?, ?>) observed(workflow, "taskViews")).values().iterator().next(), "view");
    var regions =
        (design.inherent.reform.presentation.TaskPresentation.Regions)
            observed(observed(task, "panels"), "regions");
    var bounds = regions.detail().orElse(regions.primary());
    var widget =
        widgets(workflow.screen()).stream()
            .filter(value -> value.getMessage().getString().equals("Draft preview"))
            .findFirst()
            .orElseThrow();
    check.accept(
        "Show preview reveals the full native viewport in its actual pane",
        widget.getY() >= bounds.y()
            && widget.getBottom() <= bounds.y() + bounds.height()
            && prepares.get() == 1);
  }

  private void assertPatternPixels(String filename) {
    try {
      var image =
          javax.imageio.ImageIO.read(
              client.gameDirectory.toPath().resolve("platform-" + filename).toFile());
      var widget =
          widgets(workflow.screen()).stream()
              .filter(value -> value.getMessage().getString().equals("Draft preview"))
              .findFirst()
              .orElseThrow();
      var colors = new int[] {0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffff00};
      double step = Math.min(widget.getWidth(), widget.getHeight()) * 0.1;
      for (int i = 0; i < colors.length; i++) {
        int x =
            (int)
                ((widget.getX() + widget.getWidth() / 2.0 + (i % 2 == 0 ? -step : step))
                    * image.getWidth()
                    / workflow.screen().width);
        int y =
            (int)
                ((widget.getY() + widget.getHeight() / 2.0 + (i < 2 ? -step : step))
                    * image.getHeight()
                    / workflow.screen().height);
        int observed = image.getRGB(x, y);
        for (int shift : new int[] {16, 8, 0})
          if (Math.abs(((observed >>> shift) & 255) - ((colors[i] >>> shift) & 255)) > 4)
            throw new AssertionError(
                "Actual fitted graphic pixel differs in " + filename + " at " + x + "," + y);
      }
      check.accept("fitted graphic retains actual texture colors in " + filename, true);
    } catch (java.io.IOException failed) {
      throw new AssertionError("Native fitted viewport capture unavailable", failed);
    }
  }
}
