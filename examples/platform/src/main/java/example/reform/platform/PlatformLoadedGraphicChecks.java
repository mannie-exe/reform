package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real shared loaded slot, native use/reload, no document entry or synthetic draft. */
public final class PlatformLoadedGraphicChecks {
  private static final DestinationId IMAGE = DestinationId.parse("fixture:loaded_image");
  private static final String TEXTURE = "reform_platform_example:textures/loaded.png";
  private static final GraphicLimits LIMITS =
      new GraphicLimits(1048576, 1048576, 1048576, 32, 8388608);
  private static PlatformLoadedGraphicChecks active;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private ClientGraphicHost host;
  private ClientViewState<Integer> state;
  private ClientLoadedGraphicProvider<String> normal, tiny;
  private CompletableFuture<NavigationAttempt.Result> entered;
  private CompletableFuture<Void> reload;
  private volatile GraphicScenePreparation prepared;
  private volatile Throwable failed;
  private volatile int preparations;
  private PreparedGraphic graphic;
  private int draws, stage, before, width, height;
  private final int[] nativeWidth = new int[1], nativeHeight = new int[1];
  private byte[] source;
  private Button retired;
  private ReferenceStamp resources;

  PlatformLoadedGraphicChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  public static void recordDraw(Object graphic, Object outcome) {
    if (active != null
        && graphic instanceof PreparedGraphic prepared
        && prepared.stamp().kind() == GraphicSourceStamp.Kind.LOADED
        && "RENDERED".equals(String.valueOf(outcome))) {
      active.draws++;
      active.graphic = prepared;
    }
  }

  private Component text(String value) {
    return Component.literal(value);
  }

  private ClientLoadedGraphicProvider<String> observed(GraphicLimits limits) {
    var delegate =
        ClientLoadedGraphics.textures(limits, new JsonLimits(65536, 32, 4096, 4096, 64, 1024));
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return delegate.id();
      }

      public ValueType<String> subjectType() {
        return delegate.subjectType();
      }

      public GraphicLimits limits() {
        return delegate.limits();
      }

      public ReferenceStamp resourceStamp() {
        return delegate.resourceStamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<String> request, ClientGraphicWork work) {
        preparations++;
        prepared = null;
        failed = null;
        check.accept(
            "loaded preparation has truthful provenance and no draft stamp",
            request.stamp().kind() == GraphicSourceStamp.Kind.LOADED);
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

  private void unchanged() throws Exception {
    check.accept(
        "loaded subjects leave the actual unused owner unopened and its source unchanged",
        workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened)
            && Arrays.equals(
                source,
                Files.readAllBytes(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_platform_example/workflow_preferences.json"))));
  }

  private void press(String title) {
    var button = button(workflow.screen(), title);
    check.accept(
        "loaded action is a current native focus target: " + title,
        button.active && focus(workflow.screen(), button));
    click(workflow.screen(), button);
  }

  private AbstractWidget image() {
    return widgets(workflow.screen()).stream()
        .filter(
            w -> w.getMessage().getString().equals("Loaded test image") && !(w instanceof Button))
        .findFirst()
        .orElseThrow();
  }

  private boolean released() {
    return host.nativeOwnership().bytes() == 0 && host.retainedOutput().bytes() == 0;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        active = this;
        source =
            Files.readAllBytes(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_platform_example/workflow_preferences.json"));
        normal = observed(LIMITS);
        tiny =
            observed(
                new GraphicLimits(
                    1,
                    LIMITS.outputBytes(),
                    LIMITS.decodedPixels(),
                    LIMITS.resources(),
                    LIMITS.nativeBytes()));
        var form = PlatformWorkflowExample.settings().form(text("Unused loaded-image owner"));
        var category =
            new ClientWorkflowLayout.Category(
                IMAGE.value(), text("Loaded image"), Component.empty());
        var settings =
            new ClientWorkflowLayout.Category(
                DocumentId.parse("fixture:loaded_settings"), text("Settings"), Component.empty());
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
                        DocumentId.parse("fixture:loaded_state"), ValueTypes.integer(0, 2))),
                0,
                (context, current) -> {
                  state = current;
                  return () ->
                      new ClientViewContent(
                          List.of(
                              new ClientViewContent.Action(
                                  "missing",
                                  text("Select missing image"),
                                  Component.empty(),
                                  () -> current.replace(1)),
                              new ClientViewContent.Action(
                                  "restore",
                                  text("Select test image"),
                                  Component.empty(),
                                  () -> current.replace(0)),
                              new ClientViewContent.Action(
                                  "tiny",
                                  text("Use tiny input allowance"),
                                  Component.empty(),
                                  () -> current.replace(2)),
                              new ClientViewContent.LoadedGraphic<>(
                                  "texture",
                                  text("Loaded test image"),
                                  text("Loaded pack artwork; no settings are used."),
                                  current.value() == 2 ? tiny : normal,
                                  current.value() == 1
                                      ? "reform_platform_example:textures/absent.png"
                                      : TEXTURE,
                                  LIMITS,
                                  2)));
                });
        var presentation =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:loaded_workspace"), text("Loaded resources")))
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
            "loaded slot mounts without a document or implicit preparation",
            entered.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && preparations == 0
                && released());
        unchanged();
        press("Prepare loaded image");
        stage = 2;
      }
      case 2 -> {
        if (!(prepared instanceof GraphicScenePreparation.Ready ready) || draws == 0) return false;
        var pixels = ready.graphic().scene().textures().values().iterator().next();
        check.accept(
            "loaded native still uses paired metadata and independently expected second-frame pixels",
            pixels.width() == 2
                && pixels.height() == 2
                && Arrays.equals(
                    pixels.argb(), new int[] {0xff0000ff, 0xffffffff, 0xff0000ff, 0xffffffff}));
        check.accept(
            "loaded image uses actual native allocation and source facts",
            host.nativeOwnership().bytes() > 0
                && ready.graphic().facts().stream()
                    .anyMatch(
                        f ->
                            f.title().equals("Source")
                                && f.value().equals("Loaded client resources")));
        unchanged();
        press("Show loaded image");
        stage = 3;
      }
      case 3 -> {
        GuiEventListener focus = workflow.screen().getFocused();
        while (focus instanceof ContainerEventHandler container && container.getFocused() != null)
          focus = container.getFocused();
        if (focus != image()) return false;
        check.accept(
            "Show loaded image settles focus on its actual current viewport", focus == image());
        stage = 90;
        capture.accept("platform-loaded-texture.png", () -> stage = 4);
      }
      case 4 -> {
        resources = normal.resourceStamp();
        before = preparations;
        reload = client.reloadResourcePacks();
        stage = 5;
      }
      case 5 -> {
        if (!reload.isDone() || !released()) return false;
        reload.join();
        check.accept(
            "actual reload replaces resource identity even when revision text is equal",
            !normal.resourceStamp().equals(resources)
                && normal.resourceStamp().revision().equals(resources.revision()));
        check.accept(
            "actual resource reload retires the old image without automatic preparation",
            graphic.releaseCompletion().toCompletableFuture().isDone()
                && graphic.state().released()
                && preparations == before);
        unchanged();
        before = draws;
        press("Prepare loaded image");
        stage = 6;
      }
      case 6 -> {
        if (draws <= before) return false;
        retired = button(workflow.screen(), "Prepare loaded image");
        before = preparations;
        press("Select missing image");
        stage = 7;
      }
      case 7 -> {
        if (!released()) return false;
        NativeClientProbe.pressRetired(retired);
        check.accept(
            "replaced loaded slot cannot replay a retired preparation callback",
            preparations == before);
        press("Prepare loaded image");
        stage = 8;
      }
      case 8 -> {
        if (!(prepared instanceof GraphicScenePreparation.Unavailable unavailable)) return false;
        check.accept(
            "missing loaded asset stays typed unavailable without fallback or native allocation",
            unavailable.reason().issues().stream()
                    .anyMatch(i -> i.code().equals("preview.asset_missing"))
                && released());
        unchanged();
        press("Select test image");
        press("Prepare loaded image");
        before = draws;
        stage = 9;
      }
      case 9 -> {
        if (draws <= before) return false;
        width = workflow.screen().width;
        height = workflow.screen().height;
        NativeClientProbe.size(client, nativeWidth, nativeHeight);
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 10;
      }
      case 10 -> {
        if (workflow.screen().width > 340) return false;
        press("Show loaded image");
        stage = 11;
      }
      case 11 -> {
        var image = image();
        GuiEventListener focus = workflow.screen().getFocused();
        while (focus instanceof ContainerEventHandler container && container.getFocused() != null)
          focus = container.getFocused();
        if (focus != image) return false;
        check.accept(
            "narrow loaded viewport stays within actual native screen bounds",
            image.getY() >= 0
                && image.getBottom() <= workflow.screen().height
                && image.getX() >= 0
                && image.getRight() <= workflow.screen().width);
        stage = 90;
        capture.accept("platform-loaded-texture-small.png", () -> stage = 12);
      }
      case 12 -> {
        NativeClientProbe.resize(client, nativeWidth[0], nativeHeight[0]);
        NativeClientProbe.reflow(client);
        stage = 13;
      }
      case 13 -> {
        if (workflow.screen().width != width || workflow.screen().height != height) return false;
        press("Use tiny input allowance");
        press("Prepare loaded image");
        stage = 14;
      }
      case 14 -> {
        if (failed == null) return false;
        Throwable cause = failed;
        while (cause instanceof CompletionException && cause.getCause() != null)
          cause = cause.getCause();
        check.accept(
            "loaded encoded-budget refusal happens before native image allocation",
            cause instanceof design.inherent.reform.owner.PolicyRefusal refused
                && refused.report().issues().stream()
                    .anyMatch(i -> i.code().equals("preview.budget"))
                && released());
        unchanged();
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 15;
      }
      case 15 -> {
        if (!released()) return false;
        check.accept(
            "loaded slot and original owner close without a write or retained allocation",
            !workflow.isOpen() && released());
        active = null;
        return true;
      }
      default -> {}
    }
    return false;
  }
}
