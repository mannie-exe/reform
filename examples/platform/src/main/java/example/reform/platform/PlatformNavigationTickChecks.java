package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A real mounted tick distinguishes an acknowledged reveal from a merely requested rebuild. */
public final class PlatformNavigationTickChecks implements AutoCloseable {
  private static Runnable armed;
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private ClientEditingWorkflow workflow;
  private NavigationAttempt attempt;
  private ClientEditor<?> target;
  private int stage, calls;
  private boolean completed, mountedAtCompletion, homeMountedAtCompletion, sourceUnchanged;
  private NavigationAttempt superseded, latest, closedAttempt, closedTargetAttempt;
  private byte[] original;

  PlatformNavigationTickChecks(BiConsumer<String, Boolean> check) {
    this.check = check;
  }

  public static void fire() {
    if (armed == null) return;
    var action = armed;
    armed = null;
    action.run();
  }

  com.google.gson.JsonObject report() {
    var value = new com.google.gson.JsonObject();
    value.addProperty("status", stage == 7 ? "PASS" : "RUNNING");
    value.addProperty("actualTickCalls", calls);
    value.addProperty("mountedAtCompletion", mountedAtCompletion);
    value.addProperty("homeMountedAtCompletion", homeMountedAtCompletion);
    value.addProperty("sourceUnchanged", sourceUnchanged);
    if (superseded != null)
      value.addProperty(
          "olderOutcome",
          superseded.result().map(result -> result.outcome().name()).orElse("PENDING"));
    if (latest != null)
      value.addProperty(
          "latestOutcome",
          latest.result().map(result -> result.outcome().name()).orElse("PENDING"));
    if (closedTargetAttempt != null)
      value.addProperty(
          "closedTargetOutcome",
          closedTargetAttempt.result().map(result -> result.outcome().name()).orElse("PENDING"));
    if (closedAttempt != null)
      value.addProperty(
          "closedOutcome",
          closedAttempt.result().map(result -> result.outcome().name()).orElse("PENDING"));
    return value;
  }

  private java.nio.file.Path path() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workspace_twin.json");
  }

  private static ClientWorkflowLayout.Group group(ClientForm form, String id) {
    return new ClientWorkflowLayout.Group(
        List.of(
            new ClientWorkflowLayout.Category(
                DocumentId.parse("reform_platform_example:" + id),
                form.title(),
                Component.empty())),
        List.of(
            new ClientWorkflowLayout.Placement(
                form.route(), form.route(), form.title(), Optional.empty())),
        List.of());
  }

  private boolean revealed() {
    if (attempt.result().isEmpty()) return false;
    check.accept(
        "tick witness enters an actual revealed owner",
        attempt.result().orElseThrow().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        original = Files.readAllBytes(path());
        var source = PlatformWorkflowExample.forms().getFirst();
        var destination = PlatformWorkspaceChecks.navigationTwin();
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Native tick navigation"),
                List.of(source, destination),
                new ClientWorkflowLayout(
                    List.of(group(source, "tick_source"), group(destination, "tick_destination")),
                    List.of()));
        NativeScreens.open(client, workflow.screen());
        attempt = workflow.navigation().open(new ClientDestination.Document(destination.route()));
        stage++;
      }
      case 1 -> {
        if (!revealed()) return false;
        target = workflow.editor(DocumentId.parse("reform_platform_example:workspace_twin"));
        check.accept(
            "tick witness caches a real mounted target control",
            target.form().fieldControl(FieldPath.ROOT.field("distance")).isPresent());
        attempt =
            workflow
                .navigation()
                .open(new ClientDestination.Document(PlatformWorkflowExample.DEFINITION.id()));
        stage++;
      }
      case 2 -> {
        if (!revealed()) return false;
        check.accept(
            "tick witness starts with target actually unmounted",
            target.form().fieldControl(FieldPath.ROOT.field("distance")).isEmpty());
        armed =
            () -> {
              if (NativeScreens.current(client) != workflow.screen())
                throw new AssertionError("Tick observation left its workflow");
              calls++;
              attempt =
                  workflow.navigation().open(new ClientDestination.Document(target.document()));
              attempt
                  .completion()
                  .thenAccept(
                      result -> {
                        completed = true;
                        var control = target.form().fieldControl(FieldPath.ROOT.field("distance"));
                        mountedAtCompletion =
                            result.outcome() == NavigationAttempt.Outcome.REVEALED
                                && control
                                    .filter(widget -> widgets(workflow.screen()).contains(widget))
                                    .isPresent();
                      });
            };
        stage++;
      }
      case 3 -> {
        if (!completed) return false;
        check.accept("actual native tick issued the reveal once", calls == 1);
        check.accept(
            "REVEALED acknowledges an actually mounted target, not a deferred rebuild: mountedAtCompletion="
                + mountedAtCompletion
                + ", mountedAfterTick="
                + target.form().fieldControl(FieldPath.ROOT.field("distance")).isPresent(),
            mountedAtCompletion);
        check.accept(
            "tick navigation preserves actual owner and source",
            target == workflow.editor(target.document())
                && !target.dirty()
                && Arrays.equals(original, Files.readAllBytes(path())));
        workflow
            .navigation()
            .open(new ClientDestination.Document(PlatformWorkflowExample.DEFINITION.id()));
        armed =
            () -> {
              calls++;
              superseded =
                  workflow.navigation().open(new ClientDestination.Document(target.document()));
              latest = workflow.navigation().open(new ClientDestination.Home());
              latest
                  .completion()
                  .thenAccept(
                      result ->
                          homeMountedAtCompletion =
                              result.outcome() == NavigationAttempt.Outcome.REVEALED
                                  && widgets(workflow.screen()).stream()
                                      .noneMatch(
                                          widget ->
                                              widget
                                                  .getMessage()
                                                  .getString()
                                                  .equals("Document actions…"))
                                  && target
                                      .form()
                                      .fieldControl(FieldPath.ROOT.field("distance"))
                                      .isEmpty());
            };
        stage++;
      }
      case 4 -> {
        if (latest == null || latest.result().isEmpty()) return false;
        check.accept(
            "latest in-tick Home coalesces the older selection without a hidden mount",
            superseded.result().orElseThrow().outcome() == NavigationAttempt.Outcome.SUPERSEDED
                && latest.result().orElseThrow().outcome() == NavigationAttempt.Outcome.REVEALED
                && homeMountedAtCompletion);
        workflow
            .navigation()
            .open(new ClientDestination.Document(PlatformWorkflowExample.DEFINITION.id()));
        armed =
            () -> {
              calls++;
              closedTargetAttempt =
                  workflow.navigation().open(new ClientDestination.Document(target.document()));
              target.close();
            };
        stage++;
      }
      case 5 -> {
        if (closedTargetAttempt == null || closedTargetAttempt.result().isEmpty()) return false;
        check.accept(
            "a target closed before tick drain retains CLOSED and the surviving origin",
            closedTargetAttempt.result().orElseThrow().outcome() == NavigationAttempt.Outcome.CLOSED
                && workflow.isOpen()
                && !target.isOpen()
                && workflow
                    .editor(PlatformWorkflowExample.DEFINITION.id())
                    .form()
                    .fieldControl(FieldPath.ROOT.field("distance"))
                    .isPresent());
        armed =
            () -> {
              calls++;
              closedAttempt = workflow.navigation().open(new ClientDestination.Home());
              workflow.close();
              NativeScreens.open(client, parent);
            };
        stage++;
      }
      case 6 -> {
        if (closedAttempt == null || closedAttempt.result().isEmpty()) return false;
        check.accept(
            "closing the actual tick context refuses the deferred mount and clears recovery",
            closedAttempt.result().orElseThrow().outcome() == NavigationAttempt.Outcome.CLOSED
                && !workflow.isOpen()
                && !target.isOpen()
                && NativeScreens.current(client) == parent
                && workflow
                    .navigation()
                    .inspect(new ClientDestination.Home())
                    .recovery()
                    .isEmpty());
        sourceUnchanged = Arrays.equals(original, Files.readAllBytes(path()));
        check.accept(
            "all actual tick selections retain source bytes", calls == 4 && sourceUnchanged);
        stage++;
        return true;
      }
      case 7 -> {
        return true;
      }
      default -> throw new AssertionError("Unknown native tick scene");
    }
    return false;
  }

  @Override
  public void close() {
    armed = null;
    if (workflow != null) workflow.close();
  }
}
