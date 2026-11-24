package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.presentation.ViewKey;
import design.inherent.reform.presentation.ViewStateKey;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Actual loaded-item shutdown; observers never close a resource or invoke native cleanup. */
public final class PlatformNativeItemShutdownChecks {
  private static final GraphicLimits LIMITS = new GraphicLimits(65536, 131072, 16384, 64, 16777216);
  private static final Path SOURCE =
      Path.of("config/reform_platform_example/workflow_preferences.json");
  private static PlatformNativeItemShutdownChecks active;
  private final Minecraft client = Minecraft.getInstance();
  private final JsonObject mainReport;
  private final JsonObject facts = new JsonObject();
  private final byte[] source;
  private ClientEditingWorkflow workflow;
  private ClientGraphicHost host;
  private PreparedGraphic graphic;
  private CompletableFuture<NavigationAttempt.Result> entered;
  private int stage;
  private volatile JsonObject rendererSnapshot;

  PlatformNativeItemShutdownChecks(JsonObject mainReport) throws Exception {
    this.mainReport = mainReport;
    source = Files.readAllBytes(SOURCE);
    active = this;
    Runtime.getRuntime()
        .addShutdownHook(new Thread(this::epilogue, "reform-item-shutdown-evidence"));
    if (NativeItemFixtureContext.requiresWorld()) {
      NativeScreens.open(client, null);
      NativeWorldProbe.start();
      stage = 1;
    }
  }

  void tick() {
    if (stage == 1) {
      if (client.level == null || client.player == null || NativeScreens.current(client) != null)
        return;
      stage = 0;
    }
    if (stage == 0) {
      var id = DestinationId.parse("fixture:shutdown_item");
      var category = new ClientWorkflowLayout.Category(id.value(), text("Item"), Component.empty());
      var layout =
          new ClientWorkflowLayout(
              List.of(new ClientWorkflowLayout.Group(List.of(category), List.of(), List.of())),
              List.of());
      var subject = ClientItemSubject.copyOf(new ItemStack(Items.CHEST), LIMITS);
      var provider = ClientLoadedGraphics.items(LIMITS);
      var view =
          new ClientCustomView<>(
              new ViewKey<>(
                  id,
                  new ViewStateKey<>(
                      DocumentId.parse("fixture:shutdown_item_state"), ValueTypes.integer(0, 0))),
              0,
              (context, state) ->
                  () ->
                      new ClientViewContent(
                          List.of(
                              new ClientViewContent.LoadedGraphic<>(
                                  "item",
                                  text("Native shutdown item"),
                                  text("Explicit shutdown fixture"),
                                  provider,
                                  subject,
                                  LIMITS,
                                  2))));
      workflow =
          ClientEditingWorkflow.open(
              null,
              List.of(),
              layout,
              ClientWorkspacePresentation.standard(
                      new WorkspaceIdentity(
                          DocumentId.parse("fixture:item_shutdown"), text("Native item shutdown")))
                  .withView(view));
      NativeScreens.open(client, workflow.screen());
      host = workflow.graphics(LIMITS, 2);
      entered =
          workflow
              .navigation()
              .open(new ClientDestination.View(id))
              .completion()
              .toCompletableFuture();
      stage = 2;
    } else if (stage == 2 && entered.isDone()) {
      if (entered.join().outcome() != NavigationAttempt.Outcome.REVEALED)
        throw new AssertionError("Shutdown item view refused");
      press("Prepare loaded image");
      stage = 3;
    } else if (stage == 3) {
      var show =
          PlatformWidgets.widgets(workflow.screen()).stream()
              .filter(w -> w.active && w.getMessage().getString().equals("Show loaded image"))
              .findFirst();
      if (show.isEmpty()) return;
      press("Show loaded image");
      stage = 4;
    }
  }

  public static void recordDraw(Object value, Object outcome) {
    if (active == null
        || active.stage != 4
        || !(value instanceof PreparedGraphic prepared)
        || !prepared.stamp().provider().equals(DocumentId.parse("reform:loaded_item"))
        || !"RENDERED".equals(String.valueOf(outcome))
        || prepared.state().outstandingUses() < 1) return;
    active.graphic = prepared;
    active.facts.addProperty("workflowOpenAtStop", active.workflow.isOpen());
    active.facts.addProperty("outstandingUsesAtStop", prepared.state().outstandingUses());
    active.facts.addProperty("nativeBytesAtStop", active.host.nativeOwnership().bytes());
    active.facts.addProperty("outputBytesAtStop", active.host.retainedOutput().bytes());
    active.stage = 5;
    PlatformClient.nativeItemStop(active.facts);
  }

  /** Passive actual renderer RETURN; cache all native/client observations before JVM teardown. */
  public static void rendererClosed() {
    if (active == null || active.stage != 5) return;
    try {
      var observed = new JsonObject();
      observed.addProperty(
          "releaseCompleted", active.graphic.releaseCompletion().toCompletableFuture().isDone());
      observed.addProperty("released", active.graphic.state().released());
      observed.addProperty("outstandingUses", active.graphic.state().outstandingUses());
      observed.addProperty("workflowClosed", !active.workflow.isOpen());
      observed.addProperty("nativeBytes", active.host.nativeOwnership().bytes());
      observed.addProperty("nativeResources", active.host.nativeOwnership().resources());
      observed.addProperty("outputBytes", active.host.retainedOutput().bytes());
      observed.addProperty(
          "sourceUnchanged", Arrays.equals(active.source, Files.readAllBytes(SOURCE)));
      active.rendererSnapshot = observed;
    } catch (Exception | AssertionError failed) {
      var observed = new JsonObject();
      observed.addProperty("failure", failed.toString());
      active.rendererSnapshot = observed;
    }
  }

  private void epilogue() {
    try {
      var observed = rendererSnapshot;
      facts.add("rendererReturn", observed);
      boolean passed =
          stage == 5
              && observed != null
              && !observed.has("failure")
              && facts.get("workflowOpenAtStop").getAsBoolean()
              && facts.get("outstandingUsesAtStop").getAsInt() > 0
              && facts.get("nativeBytesAtStop").getAsLong() > 0
              && facts.get("outputBytesAtStop").getAsLong() > 0
              && observed.get("releaseCompleted").getAsBoolean()
              && observed.get("released").getAsBoolean()
              && observed.get("workflowClosed").getAsBoolean()
              && observed.get("sourceUnchanged").getAsBoolean()
              && observed.get("outstandingUses").getAsInt() == 0
              && observed.get("nativeBytes").getAsLong() == 0
              && observed.get("nativeResources").getAsInt() == 0
              && observed.get("outputBytes").getAsLong() == 0
              && graphic.releaseCompletion().toCompletableFuture().isDone()
              && Arrays.equals(source, Files.readAllBytes(SOURCE));
      facts.addProperty("epilogueConfirmed", passed);
      facts.addProperty("status", passed ? "PASS" : "FAIL");
      mainReport.add("nativeItemShutdown", facts);
      if (!"FAIL".equals(mainReport.get("status").getAsString()))
        mainReport.addProperty("status", passed ? "PASS" : "FAIL");
      Files.writeString(
          Path.of("platform-report.json"),
          new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(mainReport));
    } catch (Exception | AssertionError failed) {
      failed.printStackTrace(System.err);
    }
  }

  private void press(String label) {
    var control = PlatformWidgets.button(workflow.screen(), label);
    if (!control.active || !PlatformWidgets.focus(workflow.screen(), control))
      throw new AssertionError("Shutdown control unavailable: " + label);
    PlatformWidgets.click(workflow.screen(), control);
  }

  private static Component text(String value) {
    return Component.literal(value);
  }
}
