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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Actual stock items, camera controls, empty subjects and the single-resource returned lease. */
public final class PlatformLoadedItemChecks {
  private static final GraphicLimits LIMITS = new GraphicLimits(65536, 131072, 16384, 64, 16777216);
  private static final DestinationId ITEM = DestinationId.parse("fixture:loaded_item_view");
  private static PlatformLoadedItemChecks active;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private ClientGraphicHost host;
  private ClientViewState<Integer> selection;
  private CompletableFuture<NavigationAttempt.Result> entered;
  private volatile GraphicScenePreparation prepared;
  private volatile Throwable failed, drawFailure;
  private PreparedGraphic graphic;
  private int stage, mode, submissions, before, preparations, releases;
  private boolean showing;
  private byte[] source;
  private ItemStack original;
  private ClientItemSubject copied;
  private ClientGraphicHost.Attempt contextAttempt;
  private boolean joinedWorld;
  private boolean resized;
  private final int[] nativeWidth = new int[1], nativeHeight = new int[1];
  private int screenWidth, screenHeight;
  private PreparedGraphic retainedGraphic;
  private int nativeEntries, pendingControl, nativeEntriesBefore;
  private boolean resetObserved, faultObserved, deferredChecked, pendingCloseObserved, resetChecked;
  private static final IllegalStateException NATIVE_FAULT =
      new IllegalStateException("fixture native item feature fault");

  /**
   * Verification-only control at actual GUI consumption, after extraction and before feature work.
   */
  public static void beforeNativeConsumption(Runnable reset) {
    if (active != null && active.pendingControl == 1) {
      active.pendingControl = 0;
      active.check.accept(
          "native reset starts with an outstanding extracted item use",
          active.graphic != null && active.graphic.state().outstandingUses() > 0);
      active.nativeEntriesBefore = active.nativeEntries;
      reset.run();
      active.resetObserved = true;
    } else if (active != null && active.pendingControl == 3) {
      active.pendingControl = 0;
      active.check.accept(
          "workflow close starts with an outstanding extracted native item use",
          active.graphic != null && active.graphic.state().outstandingUses() > 0);
      active.workflow.close();
      NativeScreens.open(active.client, active.parent);
      active.pendingCloseObserved = true;
    }
  }

  /**
   * Exact call-return observation; subsequent frames may legitimately recover before the next tick.
   */
  public static void afterNativeConsumption() {
    if (active != null && active.resetObserved && !active.resetChecked) {
      active.check.accept(
          "actual reset GUI consumption never enters its discarded native callback",
          active.nativeEntries == active.nativeEntriesBefore);
      active.resetChecked = true;
    }
  }

  /** Observes the actual native item feature callback, never a copied rendering implementation. */
  public static void nativeFeatureEntered() {
    if (active == null) return;
    active.nativeEntries++;
    if (active.pendingControl == 2) {
      active.pendingControl = 0;
      active.faultObserved = true;
      throw NATIVE_FAULT;
    }
  }

  private void contextWorkspace() {
    workflow = ClientEditingWorkflow.open(parent, text("Item registry context"), List.of());
    NativeScreens.open(client, workflow.screen());
    host = workflow.graphics(LIMITS, 2);
  }

  private void contextRefusal() {
    contextWorkspace();
    var empty = ClientItemSubject.copyOf(ItemStack.EMPTY, LIMITS);
    var wire =
        ClientItemSubject.type()
            .codec()
            .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, empty)
            .result()
            .orElseThrow()
            .getAsJsonObject();
    // Effect-free wire admission is distinct from native registry decoding. Preserve the actual
    // captured title context, and ask for an item whose defaults require a joined world.
    var item = new com.google.gson.JsonObject();
    item.addProperty("id", "minecraft:stone");
    item.addProperty("count", 2);
    wire.add("stack", item);
    var subject =
        ClientItemSubject.type()
            .codec()
            .parse(com.mojang.serialization.JsonOps.INSTANCE, wire)
            .result()
            .orElseThrow();
    var view = host.openLoaded(ClientLoadedGraphics.items(LIMITS));
    view.visible(true);
    contextAttempt = view.request(subject);
  }

  PlatformLoadedItemChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  public static void recordDraw(Object value, Object outcome) {
    if (active != null
        && value instanceof PreparedGraphic graphic
        && graphic.stamp().provider().equals(DocumentId.parse("reform:loaded_item"))
        && ("RENDERED".equals(String.valueOf(outcome))
            || "EMPTY".equals(String.valueOf(outcome)))) {
      active.submissions++;
      active.graphic = graphic;
    }
  }

  public static void recordFailure(Object value, Throwable failure) {
    if (active != null
        && value instanceof PreparedGraphic graphic
        && graphic.stamp().provider().equals(DocumentId.parse("reform:loaded_item")))
      active.drawFailure = failure;
  }

  private static Component text(String value) {
    return Component.literal(value);
  }

  private ClientLoadedGraphicProvider<ClientItemSubject> provider(boolean oneResource) {
    var delegate =
        ClientLoadedGraphics.items(
            oneResource ? new GraphicLimits(65536, 131072, 16384, 1, 16777216) : LIMITS);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return delegate.id();
      }

      public ValueType<ClientItemSubject> subjectType() {
        return delegate.subjectType();
      }

      public GraphicLimits limits() {
        return delegate.limits();
      }

      public ReferenceStamp resourceStamp() {
        return delegate.resourceStamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<ClientItemSubject> request, ClientGraphicWork work) {
        preparations++;
        prepared = null;
        failed = null;
        drawFailure = null;
        CompletionStage<GraphicScenePreparation> result;
        if (!oneResource) result = delegate.prepare(request, work);
        else {
          var adapter =
              new NativeGraphicAdapter<ClientItemSubject>() {
                public DocumentId id() {
                  return DocumentId.parse("fixture:one_returned_resource");
                }

                public ValueType<ClientItemSubject> inputType() {
                  return ClientItemSubject.type();
                }

                public Prepared prepare(
                    ClientItemSubject input, NativeGraphicPreparationContext context) {
                  return new Prepared() {
                    public ClientMeshGraphic.DrawOutcome draw(NativeGraphicDrawContext draw) {
                      return ClientMeshGraphic.DrawOutcome.EMPTY;
                    }

                    public void release(NativeGraphicReleaseContext release) {
                      releases++;
                    }
                  };
                }
              };
          result =
              CompletableFuture.completedFuture(
                  new NativeGraphicContribution<>(
                      adapter,
                      request.subject(),
                      request.stamp(),
                      GraphicCamera.STANDARD,
                      List.of()));
        }
        return result.whenComplete(
            (value, error) -> {
              prepared = value;
              failed = error;
            });
      }
    };
  }

  private void press(String label) {
    var control = button(workflow.screen(), label);
    check.accept(
        "native item action is current and enabled: " + label,
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
        "loaded item source and copied input stay unchanged without an open settings owner",
        (!workflow.isOpen() || workflow.documents().isEmpty())
            && original.getCount() == 7
            && copied.equals(ClientItemSubject.copyOf(new ItemStack(Items.STONE, 2), LIMITS))
            && Arrays.equals(
                source,
                Files.readAllBytes(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_platform_example/workflow_preferences.json"))));
  }

  String diagnostic() {
    return "item stage="
        + stage
        + ", mode="
        + mode
        + ", preparations="
        + preparations
        + ", submissions="
        + submissions
        + ", failure="
        + failed
        + ", draw="
        + drawFailure
        + ", nativeEntries="
        + nativeEntries
        + ", before="
        + nativeEntriesBefore
        + ", reset="
        + resetObserved
        + "/"
        + resetChecked;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        active = this;
        if (NativeItemFixtureContext.requiresWorld() && !joinedWorld) {
          contextRefusal();
          stage = 10;
          return false;
        }
        try {
          ClientEditingWorkflow.open(
              parent,
              List.of(),
              ClientWorkspacePresentation.standard(
                      new WorkspaceIdentity(
                          DocumentId.parse("fixture:no_destination"), text("Empty")))
                  .withoutHome());
          throw new AssertionError("Destination-free workflow admitted");
        } catch (design.inherent.reform.owner.PolicyRefusal expected) {
          check.accept(
              "a document-free workflow requires an actual declared destination",
              expected.report().issues().stream()
                  .anyMatch(issue -> issue.code().equals("presentation.invalid_declaration")));
        }
        source =
            Files.readAllBytes(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_platform_example/workflow_preferences.json"));
        original = new ItemStack(Items.STONE, 2);
        copied = ClientItemSubject.copyOf(original, LIMITS);
        original.setCount(7);
        var choices =
            List.of(
                copied,
                ClientItemSubject.copyOf(new ItemStack(Items.DIAMOND_SWORD), LIMITS),
                ClientItemSubject.copyOf(new ItemStack(Items.CHEST), LIMITS),
                ClientItemSubject.copyOf(ItemStack.EMPTY, LIMITS));
        var normal = provider(false);
        var one = provider(true);
        var category =
            new ClientWorkflowLayout.Category(ITEM.value(), text("Items"), Component.empty());
        var layout =
            new ClientWorkflowLayout(
                List.of(new ClientWorkflowLayout.Group(List.of(category), List.of(), List.of())),
                List.of());
        var view =
            new ClientCustomView<>(
                new ViewKey<>(
                    ITEM,
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:item_choice"), ValueTypes.integer(0, 5))),
                0,
                (context, state) -> {
                  selection = state;
                  return () ->
                      new ClientViewContent(
                          List.of(
                              new ClientViewContent.LoadedGraphic<>(
                                  "item",
                                  text("Loaded test item"),
                                  text("Prepared native GUI model"),
                                  state.value() == 4 ? one : normal,
                                  choices.get(state.value() == 5 ? 2 : Math.min(3, state.value())),
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
                            DocumentId.parse("fixture:item_workspace"), text("Loaded items")))
                    .withView(view));
        NativeScreens.open(client, workflow.screen());
        host = workflow.graphics(LIMITS, 2);
        entered =
            workflow
                .navigation()
                .open(new ClientDestination.View(ITEM))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!entered.isDone()) return false;
        check.accept(
            "native item view reveals without preparing or admitting a document",
            entered.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && preparations == 0
                && released());
        unchanged();
        stage = 2;
      }
      case 2 -> {
        if (!released()) return false;
        showing = false;
        before = submissions;
        press("Prepare loaded image");
        stage = 3;
      }
      case 3 -> {
        if (failed != null || drawFailure != null)
          throw new AssertionError(diagnostic(), failed != null ? failed : drawFailure);
        if (prepared == null) return false;
        check.accept(
            "loaded item prepares a typed native contribution, not a projected mesh",
            prepared instanceof NativeGraphicContribution<?> && preparations == mode + 1);
        if (!showing) {
          showing = true;
          if (mode == 5) {
            pendingControl = 3;
            nativeEntriesBefore = nativeEntries;
          }
          press("Show loaded image");
          return false;
        }
        if (mode == 5) {
          if (pendingCloseObserved) stage = 40;
          return false;
        }
        if (submissions <= before) return false;
        check.accept(
            "actual prepared item submits through shared native-use lifetime: " + mode,
            graphic != null && graphic.stamp().kind() == GraphicSourceStamp.Kind.LOADED);
        unchanged();
        if (mode == 0) {
          stage = 90;
          capture.accept("loaded-item-stone.png", () -> stage = 4);
        } else stage = 6;
      }
      case 4 -> {
        before = submissions;
        press("Rotate right");
        press("Zoom in");
        press("Show loaded image");
        stage = 5;
      }
      case 5 -> {
        if (submissions <= before) return false;
        check.accept(
            "item camera changes retain one prepared native contribution", preparations == 1);
        stage = 90;
        capture.accept("loaded-item-stone-orbit.png", () -> stage = 6);
      }
      case 6 -> {
        if (mode == 0 && !resized) {
          retainedGraphic = graphic;
          screenWidth = workflow.screen().width;
          screenHeight = workflow.screen().height;
          NativeClientProbe.size(client, nativeWidth, nativeHeight);
          NativeClientProbe.resize(client, 320, 240);
          NativeClientProbe.reflow(client);
          before = submissions;
          stage = 20;
          return false;
        }
        if (mode == 2 && NativeItemFixtureContext.requiresWorld() && !deferredChecked) {
          nativeEntriesBefore = nativeEntries;
          pendingControl = 1;
          resetObserved = false;
          press("Rotate right");
          press("Show loaded image");
          stage = 30;
          return false;
        }
        if (mode == 1 || mode == 2) {
          stage = 90;
          capture.accept("loaded-item-" + mode + ".png", () -> stage = 7);
        } else stage = 7;
      }
      case 7 -> {
        if (mode == 4 && !NativeItemFixtureContext.requiresWorld()) {
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
            "returned native resource fits one reservation and releases exactly once",
            releases == 1 && host.releaseFailureCount() == 0);
        unchanged();
        if (joinedWorld) {
          NativeWorldProbe.leave();
          stage = 12;
          return false;
        }
        active = null;
        stage = 9;
        return true;
      }
      case 9 -> {
        return true;
      }
      case 10 -> {
        if (!contextAttempt.completion().toCompletableFuture().isDone() || !released())
          return false;
        var result = contextAttempt.completion().toCompletableFuture().join();
        check.accept(
            "native item registry absence is Unavailable without binding synthetic components",
            result.state() == ClientGraphicHost.State.UNAVAILABLE
                && result.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("preview.item_context")));
        workflow.close();
        NativeScreens.open(client, parent);
        NativeWorldProbe.start();
        stage = 11;
      }
      case 11 -> {
        if (client.level == null
            || client.player == null
            || client.getSingleplayerServer() == null
            || NativeScreens.current(client) != null) return false;
        check.accept("native item qualification uses an actual joined disposable world", true);
        joinedWorld = true;
        stage = 0;
      }
      case 12 -> {
        if (client.level != null
            || client.player != null
            || client.getConnection() != null
            || client.getSingleplayerServer() != null) return false;
        contextWorkspace();
        var view = host.openLoaded(ClientLoadedGraphics.items(LIMITS));
        view.visible(true);
        contextAttempt = view.request(copied);
        stage = 13;
      }
      case 13 -> {
        if (!contextAttempt.completion().toCompletableFuture().isDone() || !released())
          return false;
        var result = contextAttempt.completion().toCompletableFuture().join();
        check.accept(
            "a copied item from the disconnected native registry refuses stale reuse",
            result.state() == ClientGraphicHost.State.REFUSED
                && result.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("preview.stale")));
        workflow.close();
        NativeScreens.open(client, parent);
        active = null;
        stage = 9;
        return true;
      }
      case 40 -> {
        if (!released()) return false;
        check.accept(
            "closed workflow skips deferred native item feature and drains its actual use",
            !workflow.isOpen()
                && nativeEntries == nativeEntriesBefore
                && graphic.releaseCompletion().toCompletableFuture().isDone()
                && graphic.state().released()
                && graphic.state().outstandingUses() == 0);
        unchanged();
        stage = 8;
      }
      case 30 -> {
        if (!resetObserved) return false;
        check.accept(
            "actual native GUI reset discards pending item feature work",
            resetChecked && drawFailure == null);
        before = submissions;
        stage = 31;
      }
      case 31 -> {
        if (nativeEntries <= nativeEntriesBefore || submissions <= before) return false;
        check.accept(
            "reset item target is rendered again before its frame is cached",
            preparations == 3
                && graphic.releaseCompletion().toCompletableFuture().isDone() == false);
        stage = 90;
        capture.accept("loaded-item-2.png", () -> stage = 33);
      }
      case 33 -> {
        nativeEntriesBefore = nativeEntries;
        pendingControl = 2;
        press("Rotate right");
        stage = 32;
      }
      case 32 -> {
        if (!faultObserved || drawFailure == null) return false;
        check.accept(
            "deferred feature fault is retained through the actual native draw guard",
            nativeEntries == nativeEntriesBefore + 1
                && drawFailure instanceof design.inherent.reform.owner.PolicyRefusal
                && drawFailure.getCause() == NATIVE_FAULT);
        deferredChecked = true;
        drawFailure = null;
        mode++;
        selection.replace(mode);
        stage = 2;
      }
      case 20 -> {
        if (workflow.screen().width > 340) return false;
        press("Show loaded image");
        stage = 21;
      }
      case 21 -> {
        if (submissions <= before) return false;
        var image =
            widgets(workflow.screen()).stream()
                .filter(
                    w ->
                        w.getMessage().getString().equals("Loaded test item")
                            && !(w instanceof net.minecraft.client.gui.components.Button))
                .findFirst()
                .orElseThrow();
        check.accept(
            "native item resize retains prepared identity and fits the real narrow viewport",
            graphic == retainedGraphic
                && preparations == 1
                && image.getY() >= 0
                && image.getBottom() <= workflow.screen().height
                && image.getX() >= 0
                && image.getRight() <= workflow.screen().width);
        unchanged();
        stage = 90;
        capture.accept("loaded-item-small.png", () -> stage = 22);
      }
      case 22 -> {
        NativeClientProbe.resize(client, nativeWidth[0], nativeHeight[0]);
        NativeClientProbe.reflow(client);
        before = submissions;
        stage = 23;
      }
      case 23 -> {
        if (workflow.screen().width != screenWidth || workflow.screen().height != screenHeight)
          return false;
        press("Show loaded image");
        stage = 24;
      }
      case 24 -> {
        if (submissions <= before) return false;
        check.accept(
            "native item restored viewport keeps one preparation and the same native lease",
            graphic == retainedGraphic && preparations == 1 && drawFailure == null);
        unchanged();
        resized = true;
        stage = 90;
        capture.accept("loaded-item-returned.png", () -> stage = 6);
      }
      default -> {}
    }
    return false;
  }
}
