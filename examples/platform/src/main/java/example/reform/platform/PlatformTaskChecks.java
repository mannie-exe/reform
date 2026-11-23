package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.*;
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
  private CompletableFuture<?> pending;
  private byte[] bytes;
  private ClientEditor<?> actual;
  private PreparedGraphic ready;
  private int stage;

  PlatformTaskChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private byte[] source() throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_platform_example/task_profiles.json"));
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
                      scope.onClose(sourceCloses::incrementAndGet);
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
                                "second", Component.literal("Second subject"), 2)),
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
                                            GraphicCamera.STANDARD,
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
                new ClientFormOptions<>(FormPresentation.EMPTY, Map.of(SOURCE, references)));
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
        actual = workflow.editor(DEFINITION.id());
        var ownerNotice = (Component) observed(actual.form(), "notice");
        check.accept(
            "task owner scope does not attempt to focus hidden settings",
            ownerNotice == null || !ownerNotice.getString().contains("That destination is unavailable"));
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
        click(workflow.screen(), button(workflow.screen(), "Blue"));
        stage++;
      }
      case 3 -> {
        var use = button(workflow.screen(), "Use for Models");
        if (!use.active) return false;
        check.accept(
            "inspection never stages either actual field",
            raw("models").equals("fixture:default")
                && raw("artwork").equals("fixture:default")
                && !actual.dirty());
        click(workflow.screen(), use);
        stage++;
      }
      case 4 -> {
        if (actual.busy() || !raw("models").equals("fixture:blue")) return false;
        check.accept(
            "choosing Models preserves independent Artwork and original file",
            raw("artwork").equals("fixture:default") && Arrays.equals(bytes, source()));
        click(workflow.screen(), button(workflow.screen(), "Inherited"));
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
              var prepare = button(workflow.screen(), "Prepare draft preview");
              check.accept(
                  "task preview action can be focused and revealed",
                  focus(workflow.screen(), prepare));
              click(workflow.screen(), prepare);
              stage = 7;
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
            focus(workflow.screen(), button(workflow.screen(), "Reset view")));
        stage++;
      }
      case 8 -> {
        if (draws == 0) return false;
        check.accept(
            "registered preview widget actually submits its own prepared graphic",
            draws > 0 && actual == workflow.editor(DEFINITION.id()));
        stage = 20;
        capture.accept(
            "task-preview.png",
            () -> {
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
        workflow.close();
        NativeScreens.open(client, parent);
        stage++;
      }
      case 11 -> {
        check.accept(
            "workflow retirement closes all task factory scopes but not borrowed source",
            !actual.isOpen()
                && sourceCloses.get() == 1
                && providerCloses.get() == 1
                && references.isOpen()
                && ready.releaseCompletion().toCompletableFuture().isDone()
                && Arrays.equals(bytes, source()));
        watched = null;
        stage++;
        return true;
      }
      case 12 -> {
        return true;
      }
      case 13 -> {
        check.accept(
            "changing subject invalidates old preview without preparing another",
            graphic().snapshot().state() == ClientGraphicHost.State.STALE
                && prepares.get() == 1
                && ready.state().closing());
        workflow.showHome();
        stage = 9;
      }
      case 20 -> {}
      default -> throw new AssertionError("Unknown registered task stage " + stage);
    }
    return false;
  }
}
