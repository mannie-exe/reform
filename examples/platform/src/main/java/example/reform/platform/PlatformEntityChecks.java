package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Real detached animals, native static adult/baby poses, context and vertex-budget boundaries. */
public final class PlatformEntityChecks {
  private static final GraphicLimits LIMITS = new GraphicLimits(65536, 131072, 16384, 64, 16777216);
  private static PlatformEntityChecks active;
  private final Minecraft client = Minecraft.getInstance();
  private final net.minecraft.client.gui.screens.Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private ClientGraphicHost host;
  private ClientViewState<Integer> selection;
  private CompletableFuture<NavigationAttempt.Result> entered;
  private ClientGraphicHost.Attempt attempt;
  private PreparedGraphic graphic;
  private Throwable drawFailure;
  private String drawOutcome;
  private int stage, mode, draws, before, nativeEntries, nativeBefore, preparations;
  private byte[] source;
  private List<ClientEntitySubject> subjects;
  private boolean joinedWorld;
  private Thread preparing;
  private final List<net.minecraft.world.entity.Entity> created = new ArrayList<>();

  PlatformEntityChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  public static void request(Object subject, ClientGraphicHost.Attempt attempt) {
    if (active != null && subject instanceof ClientEntitySubject) active.attempt = attempt;
  }

  public static void nativePrepare() {
    if (active != null) {
      active.nativeEntries++;
      active.preparing = Thread.currentThread();
    }
  }

  public static void nativePrepared() {
    if (active != null) active.preparing = null;
  }

  public static void constructed(Object value) {
    if (active != null
        && active.preparing == Thread.currentThread()
        && value instanceof net.minecraft.world.entity.Entity entity) active.created.add(entity);
  }

  public static void draw(Object value, Object outcome) {
    if (active != null
        && value instanceof PreparedGraphic p
        && p.stamp().provider().equals(DocumentId.parse("reform:curated_entity"))) {
      if ("RENDERED".equals(String.valueOf(outcome)) || "EMPTY".equals(String.valueOf(outcome))) {
        active.graphic = p;
        active.drawOutcome = String.valueOf(outcome);
        active.draws++;
      }
    }
  }

  public static void failure(Object value, Throwable failed) {
    if (active != null
        && value instanceof PreparedGraphic p
        && p.stamp().provider().equals(DocumentId.parse("reform:curated_entity")))
      active.drawFailure = failed;
  }

  private ClientLoadedGraphicProvider<ClientEntitySubject> provider(GraphicLimits limits) {
    var actual = ClientLoadedGraphics.entities(limits);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return actual.id();
      }

      public ValueType<ClientEntitySubject> subjectType() {
        return actual.subjectType();
      }

      public GraphicLimits limits() {
        return actual.limits();
      }

      public ReferenceStamp resourceStamp() {
        return actual.resourceStamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<ClientEntitySubject> request, ClientGraphicWork work) {
        preparations++;
        return actual.prepare(request, work);
      }
    };
  }

  private void press(String label) {
    var control = button(workflow.screen(), label);
    check.accept(
        "native animal action current and enabled: " + label,
        control.active && focus(workflow.screen(), control));
    click(workflow.screen(), control);
  }

  private boolean released() {
    return host.nativeOwnership().bytes() == 0
        && host.nativeOwnership().resources() == 0
        && host.retainedOutput().bytes() == 0;
  }

  private void unchanged() throws Exception {
    check.accept(
        "native animal preview preserves its actual context and settings source",
        (joinedWorld
                ? client.level != null && client.player != null
                : client.level == null && client.player == null)
            && (!workflow.isOpen() || workflow.documents().isEmpty())
            && Arrays.equals(
                source,
                Files.readAllBytes(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_platform_example/workflow_preferences.json"))));
  }

  private void detached() {
    check.accept(
        "actual constructed reference animals are never inserted or ticked",
        !created.isEmpty()
            && created.stream()
                .allMatch(
                    entity ->
                        entity.tickCount == 0
                            && entity.level() == client.level
                            && client.level.getEntity(entity.getId()) != entity
                            && entity.position().equals(net.minecraft.world.phys.Vec3.ZERO)));
  }

  String diagnostic() {
    return "animal stage="
        + stage
        + ",mode="
        + mode
        + ",entries="
        + nativeEntries
        + ",prepares="
        + preparations
        + ",draws="
        + draws
        + ",failure="
        + drawFailure;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        active = this;
        if (joinedWorld) {
          nativeEntries = 0;
          preparations = 0;
        }
        if (source == null)
          source =
              Files.readAllBytes(
                  client
                      .gameDirectory
                      .toPath()
                      .resolve("config/reform_platform_example/workflow_preferences.json"));
        subjects =
            List.of(
                new ClientEntitySubject(ClientEntitySubject.Kind.COW, false),
                new ClientEntitySubject(ClientEntitySubject.Kind.COW, true),
                new ClientEntitySubject(ClientEntitySubject.Kind.PIG, false),
                new ClientEntitySubject(ClientEntitySubject.Kind.PIG, true),
                new ClientEntitySubject(ClientEntitySubject.Kind.COW, true));
        var normal = provider(LIMITS);
        var tiny = provider(new GraphicLimits(65536, 1024, 16384, 64, 16777216));
        var id = DestinationId.parse("fixture:curated_entities");
        var category =
            new ClientWorkflowLayout.Category(id.value(), text("Animals"), Component.empty());
        var layout =
            new ClientWorkflowLayout(
                List.of(new ClientWorkflowLayout.Group(List.of(category), List.of(), List.of())),
                List.of());
        var view =
            new ClientCustomView<>(
                new ViewKey<>(
                    id,
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:entity_selection"), ValueTypes.integer(0, 4))),
                0,
                (context, state) -> {
                  selection = state;
                  return () ->
                      new ClientViewContent(
                          List.of(
                              new ClientViewContent.LoadedGraphic<>(
                                  "animal",
                                  text("Native reference animal"),
                                  text(
                                      "Detached static reference; no entity ticking or world insertion"),
                                  state.value() == 4 ? tiny : normal,
                                  subjects.get(state.value()),
                                  LIMITS,
                                  2)));
                });
        workflow =
            ClientEditingWorkflow.open(
                parent,
                List.of(),
                layout,
                ClientWorkspacePresentation.standard(
                        new WorkspaceIdentity(
                            DocumentId.parse("fixture:entity_workspace"),
                            text("Detached native animals")))
                    .withView(view));
        NativeScreens.open(client, workflow.screen());
        host = workflow.graphics(LIMITS, 2);
        entered =
            workflow
                .navigation()
                .open(new ClientDestination.View(id))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!entered.isDone()) return false;
        check.accept(
            "isolated native animal view remains lazy and document-free",
            entered.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && preparations == 0
                && released());
        unchanged();
        stage = 2;
      }
      case 2 -> {
        if (!released()) return false;
        nativeBefore = nativeEntries;
        before = draws;
        attempt = null;
        drawFailure = null;
        press("Prepare loaded image");
        stage = 3;
      }
      case 3 -> {
        if (attempt == null || !attempt.completion().toCompletableFuture().isDone()) return false;
        var result = attempt.completion().toCompletableFuture().join();
        check.accept(
            "animal request reaches exactly one actual native preparation: " + mode,
            nativeEntries == nativeBefore + 1 && preparations == mode + 1);
        if (!joinedWorld) {
          check.accept(
              "native animal without a joined world is unavailable without creation",
              result.state() == ClientGraphicHost.State.UNAVAILABLE
                  && result.report().issues().stream()
                      .anyMatch(i -> i.code().equals("preview.entity_context"))
                  && created.isEmpty()
                  && released());
          unchanged();
          workflow.close();
          NativeScreens.open(client, parent);
          NativeWorldProbe.start();
          stage = 10;
        } else if (mode == 4) {
          check.accept(
              "actual native animal refuses its emitted vertex budget",
              result.state() == ClientGraphicHost.State.REFUSED
                  && result.report().issues().stream()
                      .anyMatch(i -> i.code().equals("preview.budget"))
                  && released()
                  && draws == before);
          unchanged();
          stage = 6;
        } else {
          check.accept(
              "actual isolated animal native preparation is READY: " + mode,
              result.state() == ClientGraphicHost.State.READY);
          press("Show loaded image");
          stage = 4;
        }
      }
      case 4 -> {
        if (drawFailure != null) throw new AssertionError(diagnostic(), drawFailure);
        if (draws <= before) return false;
        check.accept(
            "native animal pose submits through its owned native lease: " + mode,
            graphic != null
                && graphic.stamp().kind() == GraphicSourceStamp.Kind.LOADED
                && "RENDERED".equals(drawOutcome));
        unchanged();
        detached();
        if (mode < 4) {
          stage = 90;
          capture.accept("curated-entity-" + mode + ".png", () -> stage = 5);
        } else stage = 5;
      }
      case 5 -> {
        if (mode == 0) {
          before = draws;
          press("Rotate right");
          press("Zoom in");
          press("Show loaded image");
          stage = 7;
        } else stage = 6;
      }
      case 7 -> {
        if (draws <= before) return false;
        check.accept(
            "animal camera keeps one native preparation and exact declared pose",
            nativeEntries == 1
                && preparations == 1
                && subjects
                    .get(0)
                    .equals(new ClientEntitySubject(ClientEntitySubject.Kind.COW, false)));
        stage = 6;
      }
      case 6 -> {
        if (mode == 4) {
          workflow.close();
          NativeScreens.open(client, parent);
          stage = 8;
        } else {
          mode++;
          selection.replace(mode);
          stage = 2;
        }
      }
      case 8 -> {
        if (!released()) return false;
        check.accept(
            "native animal scope retires all owned native and output resources",
            host.releaseFailureCount() == 0);
        unchanged();
        detached();
        created.clear();
        NativeWorldProbe.leave();
        stage = 11;
      }
      case 9 -> {
        return true;
      }
      case 10 -> {
        if (client.level == null
            || client.player == null
            || client.getSingleplayerServer() == null
            || NativeScreens.current(client) != null) return false;
        joinedWorld = true;
        stage = 0;
      }
      case 11 -> {
        if (client.level != null
            || client.player != null
            || client.getConnection() != null
            || client.getSingleplayerServer() != null) return false;
        joinedWorld = false;
        unchanged();
        active = null;
        stage = 9;
        return true;
      }
      default -> {}
    }
    return false;
  }

  private static Component text(String value) {
    return Component.literal(value);
  }
}
