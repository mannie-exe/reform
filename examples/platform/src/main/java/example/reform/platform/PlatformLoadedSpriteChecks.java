package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.PolicyRefusal;
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
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual loaded GUI/block atlases through the production authored slot and native renderer. */
public final class PlatformLoadedSpriteChecks {
  private static final GraphicLimits LIMITS = new GraphicLimits(65536, 65536, 16384, 32, 1048576);
  private static final DestinationId IMAGE = DestinationId.parse("fixture:loaded_sprite");
  private static PlatformLoadedSpriteChecks active;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private ClientGraphicHost host;
  private CompletableFuture<NavigationAttempt.Result> entered;
  private volatile GraphicScenePreparation prepared;
  private volatile Throwable failed;
  private PreparedGraphic graphic;
  private int stage, draws, before, preparations;
  private byte[] bytes;
  private Button retired;

  PlatformLoadedSpriteChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  public static void recordDraw(Object value, Object outcome) {
    if (active != null
        && value instanceof PreparedGraphic graphic
        && graphic.stamp().provider().equals(DocumentId.parse("reform:loaded_sprite"))
        && "RENDERED".equals(String.valueOf(outcome))) {
      active.draws++;
      active.graphic = graphic;
    }
  }

  private static Component text(String value) {
    return Component.literal(value);
  }

  private ClientLoadedGraphicProvider<ClientSpriteSubject> provider(boolean tiny) {
    var delegate =
        ClientLoadedGraphics.sprites(
            tiny
                ? new GraphicLimits(
                    1,
                    LIMITS.outputBytes(),
                    LIMITS.decodedPixels(),
                    LIMITS.resources(),
                    LIMITS.nativeBytes())
                : LIMITS);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return delegate.id();
      }

      public ValueType<ClientSpriteSubject> subjectType() {
        return delegate.subjectType();
      }

      public GraphicLimits limits() {
        return delegate.limits();
      }

      public ReferenceStamp resourceStamp() {
        return delegate.resourceStamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<ClientSpriteSubject> request, ClientGraphicWork work) {
        preparations++;
        prepared = null;
        failed = null;
        return delegate
            .prepare(request, work)
            .whenComplete(
                (value, error) -> {
                  prepared = value;
                  failed = error;
                });
      }
    };
  }

  private void press(String title) {
    var button = button(workflow.screen(), title);
    check.accept(
        "sprite action is mounted and enabled: " + title,
        button.active && focus(workflow.screen(), button));
    click(workflow.screen(), button);
  }

  private void unchanged() throws Exception {
    check.accept(
        "sprite capture never admits its unused settings owner or writes its source",
        workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened)
            && Arrays.equals(
                bytes,
                Files.readAllBytes(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_platform_example/workflow_preferences.json"))));
  }

  private boolean released() {
    return host.nativeOwnership().bytes() == 0 && host.retainedOutput().bytes() == 0;
  }

  private boolean drawn(int first, int second) {
    if (!(prepared instanceof GraphicScenePreparation.Ready ready) || draws <= before) return false;
    var pixels = ready.graphic().scene().textures().values().iterator().next();
    check.accept(
        "native atlas frame has independent expected colors and extent",
        pixels.width() == 2
            && pixels.height() == 2
            && pixels.pixel(0, 0) == first
            && pixels.pixel(1, 0) == second
            && pixels.pixel(0, 1) == first
            && pixels.pixel(1, 1) == second);
    check.accept(
        "sprite provenance stays loaded with actual atlas and physical frame facts",
        ready.graphic().stamp().kind() == GraphicSourceStamp.Kind.LOADED
            && ready.graphic().facts().stream()
                .anyMatch(f -> f.id().equals(DocumentId.parse("reform:graphic/atlas")))
            && ready.graphic().facts().stream()
                .anyMatch(f -> f.value().contains("static reference")));
    return true;
  }

  private void refused(String code) {
    Throwable cause = failed;
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    check.accept(
        "native sprite refuses " + code,
        cause instanceof PolicyRefusal refused
            && refused.report().issues().stream().anyMatch(issue -> issue.code().equals(code))
            && released());
  }

  String diagnostic() {
    return "sprite stage=" + stage + ", preparations=" + preparations + ", failure=" + failed;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        active = this;
        bytes =
            Files.readAllBytes(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_platform_example/workflow_preferences.json"));
        var normal = provider(false);
        var tiny = provider(true);
        var category =
            new ClientWorkflowLayout.Category(IMAGE.value(), text("Sprite"), Component.empty());
        var settings =
            new ClientWorkflowLayout.Category(
                DocumentId.parse("fixture:sprite_settings"), text("Settings"), Component.empty());
        var form = PlatformWorkflowExample.settings().form(text("Unused sprite owner"));
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(List.of(category), List.of(), List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(settings),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                form.route(), form.route(), form.title(), Optional.empty())),
                        List.of())),
                List.of());
        var body =
            new ClientCustomView<>(
                new ViewKey<>(
                    IMAGE,
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:sprite_state"), ValueTypes.integer(0, 5))),
                0,
                (context, state) ->
                    () -> {
                      int mode = state.value();
                      var subject =
                          new ClientSpriteSubject(
                              mode == 2
                                  ? ClientSpriteSubject.Atlas.BLOCKS
                                  : ClientSpriteSubject.Atlas.GUI,
                              mode == 3
                                  ? "reform_platform_example:absent"
                                  : mode == 2
                                      ? "reform_platform_example:block/loaded"
                                      : "reform_platform_example:loaded",
                              mode == 1 ? 1 : mode == 4 ? 2 : 0);
                      return new ClientViewContent(
                          List.of(
                              new ClientViewContent.Action(
                                  "first",
                                  text("GUI first frame"),
                                  Component.empty(),
                                  () -> state.replace(0)),
                              new ClientViewContent.Action(
                                  "second",
                                  text("GUI second frame"),
                                  Component.empty(),
                                  () -> state.replace(1)),
                              new ClientViewContent.Action(
                                  "block",
                                  text("Block sprite"),
                                  Component.empty(),
                                  () -> state.replace(2)),
                              new ClientViewContent.Action(
                                  "missing",
                                  text("Missing sprite"),
                                  Component.empty(),
                                  () -> state.replace(3)),
                              new ClientViewContent.Action(
                                  "outside",
                                  text("Outside frame"),
                                  Component.empty(),
                                  () -> state.replace(4)),
                              new ClientViewContent.Action(
                                  "tiny",
                                  text("Tiny sprite allowance"),
                                  Component.empty(),
                                  () -> state.replace(5)),
                              new ClientViewContent.LoadedGraphic<>(
                                  "sprite",
                                  text("Loaded test sprite"),
                                  text("A physical atlas frame; no animation is implied."),
                                  mode == 5 ? tiny : normal,
                                  subject,
                                  LIMITS,
                                  2)));
                    });
        var presentation =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:sprite_workspace"), text("Loaded sprites")))
                .withView(body);
        workflow = ClientEditingWorkflow.open(parent, List.of(form), layout, presentation);
        NativeScreens.open(client, workflow.screen());
        host = workflow.graphics(LIMITS, 2);
        entered =
            workflow
                .navigation()
                .open(new ClientDestination.View(IMAGE))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!entered.isDone()) return false;
        check.accept(
            "sprite custom view reveals without preparing or allocating",
            entered.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && preparations == 0
                && released());
        unchanged();
        check.accept(
            "idle sprite cancellation is disabled",
            !button(workflow.screen(), "Cancel preparation").active);
        retired = button(workflow.screen(), "Prepare loaded image");
        before = draws;
        press("Prepare loaded image");
        stage = 2;
      }
      case 2 -> {
        if (!drawn(0xffff0000, 0xff00ff00)) return false;
        capture.accept("loaded-sprite-gui.png", () -> {});
        unchanged();
        press("GUI second frame");
        int count = preparations;
        NativeClientProbe.pressRetired(retired);
        check.accept(
            "retired sprite callback cannot prepare its replaced slot", preparations == count);
        stage = 3;
      }
      case 3, 5, 7, 9, 11 -> {
        if (!released()) return false;
        check.accept(
            "sprite replacement retires the previous native and scene payload",
            graphic == null || graphic.releaseCompletion().toCompletableFuture().isDone());
        before = draws;
        press("Prepare loaded image");
        stage++;
      }
      case 4 -> {
        if (!drawn(0xff0000ff, 0xffffffff)) return false;
        press("Block sprite");
        stage = 5;
      }
      case 6 -> {
        if (!drawn(0xffff0000, 0xff00ff00)) return false;
        capture.accept("loaded-sprite-block.png", () -> {});
        press("Missing sprite");
        stage = 7;
      }
      case 8 -> {
        if (prepared == null) return false;
        check.accept(
            "missing atlas sprite stays unavailable without drawing the game's missing texture",
            prepared instanceof GraphicScenePreparation.Unavailable && released());
        press("Outside frame");
        stage = 9;
      }
      case 10 -> {
        if (failed == null) return false;
        refused("preview.sprite_frame");
        press("Tiny sprite allowance");
        stage = 11;
      }
      case 12 -> {
        if (failed == null) return false;
        refused("preview.budget");
        unchanged();
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 13;
      }
      case 13 -> {
        check.accept(
            "sprite scope closure leaves no owned native or scene allocations",
            !workflow.isOpen() && released());
        active = null;
        stage = 14;
        return true;
      }
      case 14 -> {
        return true;
      }
      default -> {}
    }
    return false;
  }
}
