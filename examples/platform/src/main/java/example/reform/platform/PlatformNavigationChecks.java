package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.PolicyRefusal;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Packaged public links exercise actual delayed owner admission, never a substitute actor. */
final class PlatformNavigationChecks implements AutoCloseable {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final CompletableFuture<Screen> delayed = new CompletableFuture<>();
  private final CompletableFuture<Screen> rejected = new CompletableFuture<>();
  private final PolicyRefusal refusal =
      new PolicyRefusal("fixture.context_refused", "Actual test context refused entry");
  private ClientEditingWorkflow workflow;
  private NavigationAttempt pending, reveal, denied, closedPending;
  private SettingsScreen<?> lateScreen;
  private design.inherent.reform.operation.DocumentActions<?> lateActions;
  private final CompletableFuture<Screen> closing = new CompletableFuture<>();
  private ClientEditor<?> retained;
  private int stage, starts, cancels;
  private byte[] source;
  private final DocumentId route = PlatformWorkflowExample.DEFINITION.id();
  private final DocumentId refusedRoute = PlatformWorkflowSources.client().route();
  private final DocumentId action = DocumentId.parse("reform_platform_example:linked_action");
  private final FieldPath distance = FieldPath.ROOT.field("distance");

  PlatformNavigationChecks(BiConsumer<String, Boolean> check) {
    this.check = check;
  }

  private java.nio.file.Path sourcePath() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  private ClientForm delayedForm() {
    return ClientForm.asynchronous(
        route,
        Component.literal("Delayed preferences"),
        () -> new ClientForm.Availability(true, false, Component.empty()),
        ignored -> new ClientForm.PendingScreen(delayed, () -> cancels++));
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        source = Files.readAllBytes(sourcePath());
        var refused =
            ClientForm.asynchronous(
                refusedRoute,
                Component.literal("Refused context"),
                () -> new ClientForm.Availability(true, false, Component.empty()),
                ignored -> new ClientForm.PendingScreen(rejected, () -> {}));
        var operation =
            new ClientWorkflowLayout.Action<Object>(
                action,
                Component.literal("Inspect linked effect"),
                Component.literal("Explicit effect only"),
                Optional::<Component>empty,
                () -> {
                  starts++;
                  throw new AssertionError("Navigation started an effect");
                },
                () -> true,
                ignored -> {
                  throw new AssertionError("No result exists");
                });
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("reform_platform_example:links"),
                                Component.literal("Links"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                route,
                                route,
                                Component.literal("Delayed preferences"),
                                Optional.empty())),
                        List.of(operation)),
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("reform_platform_example:refused_links"),
                                Component.literal("Refused links"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                refusedRoute,
                                refusedRoute,
                                Component.literal("Refused context"),
                                Optional.empty())),
                        List.of())),
                List.of());
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Typed navigation"),
                List.of(delayedForm(), refused),
                layout);
        NativeScreens.open(client, workflow.screen());
        check.accept(
            "cached typed link inspection does not admit an owner",
            workflow.navigation().inspect(new ClientDestination.Field(route, distance)).kind()
                    == DestinationAvailability.Kind.UNOPENED
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        pending = workflow.navigation().open(new ClientDestination.Field(route, distance));
        check.accept(
            "async typed link has an identified pending attempt",
            pending.result().isEmpty()
                && workflow
                        .navigation()
                        .inspect(new ClientDestination.Field(route, distance))
                        .kind()
                    == DestinationAvailability.Kind.PENDING);
        check.accept(
            "pending owner admission preserves Home and source",
            workflow.screen().getNarrationMessage().getString().contains("0 opened")
                && Arrays.equals(source, Files.readAllBytes(sourcePath())));
        var newer = workflow.navigation().open(new ClientDestination.Home());
        check.accept(
            "newer Home supersedes the old attempt without cancelling its retained owner",
            pending.result().orElseThrow().outcome() == NavigationAttempt.Outcome.SUPERSEDED
                && newer.result().orElseThrow().outcome() == NavigationAttempt.Outcome.REVEALED
                && cancels == 0);
        delayed.complete(
            PlatformWorkflowExample.settings()
                .screen(workflow.screen(), Component.literal("Delayed preferences")));
        stage++;
      }
      case 1 -> {
        if (workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened))
          return false;
        check.accept(
            "late actual owner cannot steal the selected Home",
            widgets(workflow.screen()).stream()
                    .anyMatch(widget -> widget.getMessage().getString().equals("Links"))
                && widgets(workflow.screen()).stream()
                    .noneMatch(
                        widget -> widget.getMessage().getString().equals("Document actions…")));
        retained = workflow.editor(route);
        reveal = workflow.navigation().open(new ClientDestination.Field(route, distance));
        stage++;
      }
      case 2 -> {
        if (reveal.result().isEmpty()) return false;
        check.accept(
            "typed field reveal mounts the same admitted actor",
            reveal.result().orElseThrow().outcome() == NavigationAttempt.Outcome.REVEALED
                && workflow.editor(route) == retained
                && retained.form().fieldControl(distance).isPresent());
        var absent =
            new ClientDestination.Field(
                route, FieldPath.ROOT.field("removed").row(UUID.randomUUID()));
        check.accept(
            "removed stable target is missing without row-index substitution",
            workflow.navigation().inspect(absent).kind() == DestinationAvailability.Kind.MISSING
                && workflow.navigation().open(absent).result().orElseThrow().outcome()
                    == NavigationAttempt.Outcome.MISSING
                && workflow.editor(route) == retained);
        denied = workflow.navigation().open(new ClientDestination.Document(refusedRoute));
        check.accept(
            "second pending navigation retains actual origin control",
            retained.form().fieldControl(distance).isPresent());
        rejected.completeExceptionally(refusal);
        stage++;
      }
      case 3 -> {
        if (denied.result().isEmpty()) return false;
        var result = denied.result().orElseThrow();
        check.accept(
            "actual owner refusal retains code and cause",
            result.outcome() == NavigationAttempt.Outcome.REFUSED
                && result.cause().orElseThrow() == refusal
                && result.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("fixture.context_refused")));
        check.accept(
            "refused navigation preserves origin owner, body, and file",
            retained.form().fieldControl(distance).isPresent()
                && workflow.editor(route) == retained
                && Arrays.equals(source, Files.readAllBytes(sourcePath())));
        var operation = workflow.navigation().open(new ClientDestination.Operation(action));
        check.accept(
            "operation link reveals its actual effect without invoking it: " + operation.result(),
            operation.result().orElseThrow().outcome() == NavigationAttempt.Outcome.REVEALED
                && starts == 0);
        var missing =
            workflow
                .navigation()
                .open(new ClientDestination.OperationResult(action, UUID.randomUUID()));
        check.accept(
            "result links require a retained exact handle and never start",
            missing.result().orElseThrow().outcome() == NavigationAttempt.Outcome.MISSING
                && starts == 0);
        workflow.close();
        var closed = workflow.navigation().open(new ClientDestination.Home());
        check.accept(
            "closed navigation has an explicit terminal result",
            closed.result().orElseThrow().outcome() == NavigationAttempt.Outcome.CLOSED
                && workflow.navigation().inspect(new ClientDestination.Home()).kind()
                    == DestinationAvailability.Kind.CLOSED);
        NativeScreens.open(client, parent);
        var closingForm =
            ClientForm.asynchronous(
                route,
                Component.literal("Closing context"),
                () -> new ClientForm.Availability(true, false, Component.empty()),
                ignored -> new ClientForm.PendingScreen(closing, () -> cancels++));
        workflow =
            ClientEditingWorkflow.open(
                parent, Component.literal("Closing links"), List.of(closingForm));
        NativeScreens.open(client, workflow.screen());
        closedPending = workflow.navigation().open(new ClientDestination.Document(route));
        check.accept(
            "closing witness begins with actual pending owner admission",
            closedPending.result().isEmpty());
        workflow.close();
        check.accept(
            "workflow close settles its pending navigation without claiming effect cancellation",
            closedPending.result().orElseThrow().outcome() == NavigationAttempt.Outcome.CLOSED
                && cancels == 1);
        lateScreen =
            (SettingsScreen<?>)
                PlatformWorkflowExample.settings()
                    .screen(parent, Component.literal("Late closed editor"));
        lateActions = lateScreen.editingActions();
        closing.complete(lateScreen);
        NativeScreens.open(client, parent);
        stage++;
      }
      case 4 -> {
        boolean editorClosed = !lateActions.isOpen();
        boolean sameParent = NativeScreens.current(client) == parent;
        boolean sourceUnchanged = Arrays.equals(source, Files.readAllBytes(sourcePath()));
        check.accept(
            "late admitted editor is retired after context close without mounting: editorClosed="
                + editorClosed
                + ", sameParent="
                + sameParent
                + ", sourceUnchanged="
                + sourceUnchanged
                + ", current="
                + NativeScreens.current(client).getClass().getSimpleName()
                + ", parent="
                + parent.getClass().getSimpleName(),
            editorClosed && sameParent && sourceUnchanged);
        stage++;
        return true;
      }
      case 5 -> {
        return true;
      }
      default -> throw new AssertionError("Unknown link scene");
    }
    return false;
  }

  @Override
  public void close() {
    if (workflow != null) workflow.close();
  }
}
