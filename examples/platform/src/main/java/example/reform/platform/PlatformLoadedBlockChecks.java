package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.mojang.serialization.JsonOps;
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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

/** Actual static state models, default tint/materials, empty state and native decoding refusals. */
public final class PlatformLoadedBlockChecks {
  private static final GraphicLimits LIMITS = new GraphicLimits(65536, 131072, 16384, 64, 16777216);
  private static PlatformLoadedBlockChecks active;
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
  private List<ClientBlockSubject> subjects;

  PlatformLoadedBlockChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  public static void request(Object subject, ClientGraphicHost.Attempt attempt) {
    if (active != null && subject instanceof ClientBlockSubject) active.attempt = attempt;
  }

  public static void nativePrepare() {
    if (active != null) active.nativeEntries++;
  }

  public static void draw(Object value, Object outcome) {
    if (active != null
        && value instanceof PreparedGraphic p
        && p.stamp().provider().equals(DocumentId.parse("reform:loaded_block"))) {
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
        && p.stamp().provider().equals(DocumentId.parse("reform:loaded_block")))
      active.drawFailure = failed;
  }

  private ClientLoadedGraphicProvider<ClientBlockSubject> provider(GraphicLimits limits) {
    var actual = ClientLoadedGraphics.blocks(limits);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return actual.id();
      }

      public ValueType<ClientBlockSubject> subjectType() {
        return actual.subjectType();
      }

      public GraphicLimits limits() {
        return actual.limits();
      }

      public ReferenceStamp resourceStamp() {
        return actual.resourceStamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<ClientBlockSubject> request, ClientGraphicWork work) {
        preparations++;
        return actual.prepare(request, work);
      }
    };
  }

  private void press(String label) {
    var control = button(workflow.screen(), label);
    check.accept(
        "native block action current and enabled: " + label,
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
        "native static block preview reads no world and preserves its settings source",
        client.level == null
            && client.player == null
            && (!workflow.isOpen() || workflow.documents().isEmpty())
            && Arrays.equals(
                source,
                Files.readAllBytes(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_platform_example/workflow_preferences.json"))));
  }

  String diagnostic() {
    return "block stage="
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
        source =
            Files.readAllBytes(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_platform_example/workflow_preferences.json"));
        var stairs =
            ClientBlockSubject.copyOf(
                Blocks.OAK_STAIRS
                    .defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                    .setValue(BlockStateProperties.HALF, Half.TOP),
                LIMITS);
        var bad =
            ClientBlockSubject.type()
                .codec()
                .encodeStart(JsonOps.INSTANCE, stairs)
                .result()
                .orElseThrow()
                .getAsJsonObject();
        bad.getAsJsonObject("Properties").addProperty("facing", "invalid");
        var malformed =
            ClientBlockSubject.type().codec().parse(JsonOps.INSTANCE, bad).result().orElseThrow();
        var absent =
            ClientBlockSubject.type()
                .codec()
                .parse(
                    JsonOps.INSTANCE,
                    com.google.gson.JsonParser.parseString("{\"Name\":\"fixture:missing_block\"}"))
                .result()
                .orElseThrow();
        subjects =
            List.of(
                ClientBlockSubject.copyOf(Blocks.STONE.defaultBlockState(), LIMITS),
                stairs,
                ClientBlockSubject.copyOf(Blocks.GLASS.defaultBlockState(), LIMITS),
                ClientBlockSubject.copyOf(Blocks.GRASS_BLOCK.defaultBlockState(), LIMITS),
                ClientBlockSubject.copyOf(Blocks.AIR.defaultBlockState(), LIMITS),
                ClientBlockSubject.copyOf(Blocks.CHEST.defaultBlockState(), LIMITS),
                absent,
                malformed,
                stairs);
        var normal = provider(LIMITS);
        var tiny = provider(new GraphicLimits(65536, 1024, 16384, 64, 16777216));
        var id = DestinationId.parse("fixture:static_blocks");
        var category =
            new ClientWorkflowLayout.Category(id.value(), text("Blocks"), Component.empty());
        var layout =
            new ClientWorkflowLayout(
                List.of(new ClientWorkflowLayout.Group(List.of(category), List.of(), List.of())),
                List.of());
        var view =
            new ClientCustomView<>(
                new ViewKey<>(
                    id,
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:block_selection"), ValueTypes.integer(0, 8))),
                0,
                (context, state) -> {
                  selection = state;
                  return () ->
                      new ClientViewContent(
                          List.of(
                              new ClientViewContent.LoadedGraphic<>(
                                  "block",
                                  text("Native test block"),
                                  text("Static state; no block-entity rendering"),
                                  state.value() == 8 ? tiny : normal,
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
                            DocumentId.parse("fixture:block_workspace"),
                            text("Native static blocks")))
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
            "native static block view remains lazy and document-free",
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
        press("Prepare loaded preview");
        stage = 3;
      }
      case 3 -> {
        if (attempt == null || !attempt.completion().toCompletableFuture().isDone()) return false;
        var result = attempt.completion().toCompletableFuture().join();
        check.accept(
            "block request reaches exactly one actual native preparation: " + mode,
            nativeEntries == nativeBefore + 1 && preparations == mode + 1);
        boolean emptyChestBase =
            mode == 5
                && Blocks.CHEST.defaultBlockState().getRenderShape()
                    == net.minecraft.world.level.block.RenderShape.MODEL;
        if (mode >= 5 && !emptyChestBase) {
          String code =
              mode == 5
                  ? "preview.block_entity"
                  : mode == 8 ? "preview.budget" : "preview.block_input";
          check.accept(
              "actual native block refuses " + code,
              result.state()
                      == (mode == 5
                          ? ClientGraphicHost.State.UNAVAILABLE
                          : ClientGraphicHost.State.REFUSED)
                  && result.report().issues().stream().anyMatch(i -> i.code().equals(code))
                  && released()
                  && draws == before);
          unchanged();
          stage = 6;
        } else {
          check.accept(
              "actual static block native preparation is READY: " + mode,
              result.state() == ClientGraphicHost.State.READY);
          press("Show loaded preview");
          stage = 4;
        }
      }
      case 4 -> {
        if (drawFailure != null) throw new AssertionError(diagnostic(), drawFailure);
        if (draws <= before) return false;
        check.accept(
            "native static state submits through its owned native lease: " + mode,
            graphic != null
                && graphic.stamp().kind() == GraphicSourceStamp.Kind.LOADED
                && (mode == 4 || mode == 5
                    ? "EMPTY".equals(drawOutcome)
                    : "RENDERED".equals(drawOutcome)));
        unchanged();
        if (mode == 0 || mode == 1 || mode == 3) {
          stage = 90;
          capture.accept("loaded-block-" + mode + ".png", () -> stage = 5);
        } else stage = 5;
      }
      case 5 -> {
        if (mode == 0) {
          before = draws;
          press("Rotate right");
          press("Zoom in");
          press("Show loaded preview");
          stage = 7;
        } else stage = 6;
      }
      case 7 -> {
        if (draws <= before) return false;
        check.accept(
            "block camera keeps one native preparation and exact copied state",
            nativeEntries == 1
                && preparations == 1
                && subjects
                    .get(0)
                    .equals(ClientBlockSubject.copyOf(Blocks.STONE.defaultBlockState(), LIMITS)));
        stage = 6;
      }
      case 6 -> {
        if (mode == 8) {
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
            "native block scope retires all owned native and output resources",
            host.releaseFailureCount() == 0);
        unchanged();
        active = null;
        stage = 9;
        return true;
      }
      case 9 -> {
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
