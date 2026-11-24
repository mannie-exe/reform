package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.*;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual named effect and retained-result presentation, with no settings owner admitted. */
final class PlatformOperationPresentationChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private ClientEditingWorkflow workflow;
  private OwnerContext owner;
  private CompletableFuture<String> result;
  private UUID identity;
  private Button retiredInspect;
  private byte[] source;
  private int stage, starts, entered, oldWidth, oldHeight;
  private boolean blocked, navigateAtStart, observable = true;
  private OperationHandle<String> latest;
  private CompletableFuture<NavigationAttempt.Result> navigation;

  PlatformOperationPresentationChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private OperationScreen<?> operation() {
    return (OperationScreen<?>) NativeScreens.current(client);
  }

  private void press(Screen screen, Button control) {
    check.accept("operation action is mounted and keyboard revealable", focus(screen, control));
    click(screen, control);
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        source =
            Files.readAllBytes(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_platform_example/workflow_preferences.json"));
        owner =
            new OwnerContext(
                client::execute,
                Runnable::run,
                () -> {
                  if (!client.isSameThread())
                    throw new AssertionError("Operation owner left client thread");
                },
                OwnerContext.Limits.STANDARD);
        var scope =
            new OperationScope(
                owner, owner.access(() -> true, () -> false), OperationScope.Limits.STANDARD);
        var descriptor =
            new OperationDescriptor<String, String>(
                DocumentId.parse("fixture:inspect_values"),
                ValueTypes.text(64),
                OwnerContext.Right.READ,
                Set.of(OperationDescriptor.Effect.INSPECT),
                Set.of(OperationDescriptor.EntryPoint.GUI));
        scope.register(
            descriptor,
            (execution, request) -> {
              entered++;
              return result.thenApply(
                  value -> {
                    execution.checkpoint();
                    return value;
                  });
            });
        scope.freeze();
        var action =
            new ClientWorkflowLayout.Action<String>(
                descriptor.id(),
                Component.literal("Inspect values"),
                Component.literal("Read values without saving settings."),
                () ->
                    blocked
                        ? Optional.of(Component.literal("Inspection is temporarily unavailable."))
                        : Optional.empty(),
                () -> {
                  starts++;
                  result = new CompletableFuture<>();
                  latest = scope.submit(descriptor, OperationDescriptor.EntryPoint.GUI, "values");
                  if (navigateAtStart)
                    navigation =
                        workflow
                            .navigation()
                            .open(new ClientDestination.Home())
                            .completion()
                            .toCompletableFuture();
                  return latest;
                },
                () -> observable,
                value ->
                    new OperationScreen.Outcome(
                        Component.literal(value),
                        java.util.stream.IntStream.range(0, 20)
                            .mapToObj(
                                i ->
                                    new OperationScreen.Detail(
                                        Component.literal("Value " + i),
                                        Component.literal("Captured " + i)))
                            .toList()));
        var form = PlatformWorkflowExample.settings().form(Component.literal("Unused preferences"));
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("fixture:operation_settings"),
                                Component.literal("Settings"),
                                Component.empty())),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                form.route(), form.route(), form.title(), Optional.empty())),
                        List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(
                            new ClientWorkflowLayout.Category(
                                DocumentId.parse("fixture:operations"),
                                Component.literal("Actions"),
                                Component.empty())),
                        List.of(),
                        List.of(action))),
                List.of());
        workflow =
            ClientEditingWorkflow.open(
                parent, Component.literal("Operation workspace"), List.of(form), layout);
        NativeScreens.open(client, workflow.screen());
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Category(DestinationId.parse("fixture:operations")))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!navigation.isDone()) return false;
        check.accept(
            "revealing actions neither starts an effect nor opens a settings owner",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && starts == 0
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        press(workflow.screen(), button(workflow.screen(), "Inspect values"));
        stage = 2;
      }
      case 2 -> {
        var state = operation().observedOperation().orElse(null);
        if (state == null || entered != 1) return false;
        identity = state.id();
        check.accept(
            "named action dispatches once and exposes the actual pending operation",
            starts == 1 && !state.terminal());
        result.complete("Values inspected. No settings were saved.");
        stage = 3;
      }
      case 3 -> {
        var screen = operation();
        var state = screen.observedOperation().orElseThrow();
        if (!state.terminal()) return false;
        check.accept(
            "result receipt and ordinary narration retain the actual effect without technical IDs",
            state.id().equals(identity)
                && state.state() == OperationSnapshot.State.COMPLETED
                && screen.getNarrationMessage().getString().contains("No settings were saved")
                && !screen.getNarrationMessage().getString().contains(identity.toString()));
        check.accept(
            "wide operation separates summary from captured details",
            screen.children().stream().filter(ScrollFrame.class::isInstance).count() == 2);
        geometry(screen);
        press(screen, button(screen, "Next details"));
        oldWidth = screen.width;
        oldHeight = screen.height;
        NativeClientProbe.size(client, windowWidth, windowHeight);
        stage = 19;
        capture.accept(
            "operation-wide.png",
            () -> {
              NativeClientProbe.resize(client, 320, 240);
              stage = 4;
            });
      }
      case 4 -> {
        var screen = operation();
        if (screen.width >= oldWidth || screen.width > 400) return false;
        check.accept(
            "narrow operation keeps the exact handle, result page and one complete flow",
            screen.children().stream().filter(ScrollFrame.class::isInstance).count() == 1
                && screen.observedOperation().orElseThrow().id().equals(identity)
                && button(screen, "Previous details").active
                && starts == 1);
        geometry(screen);
        stage = 19;
        capture.accept(
            "operation-small.png",
            () -> {
              NativeClientProbe.resize(client, windowWidth[0], windowHeight[0]);
              stage = 5;
            });
      }
      case 5 -> {
        var screen = operation();
        if (screen.width != oldWidth || screen.height != oldHeight) return false;
        check.accept(
            "returned operation restores adjacent details without restarting or losing its page",
            screen.children().stream().filter(ScrollFrame.class::isInstance).count() == 2
                && screen.observedOperation().orElseThrow().id().equals(identity)
                && button(screen, "Previous details").active
                && starts == 1);
        press(screen, button(screen, "Show technical details"));
        check.accept(
            "technical disclosure exposes the same operation identity without another effect",
            screen.getNarrationMessage().getString().contains(identity.toString()) && starts == 1);
        press(screen, button(screen, "Back"));
        blocked = true;
        stage = 6;
      }
      case 6 -> {
        if (NativeScreens.current(client) != workflow.screen()) return false;
        if (button(workflow.screen(), "Inspect values").active) return false;
        check.accept(
            "effect refusal does not disable inspection of the retained result",
            button(workflow.screen(), "Inspect result").active && starts == 1);
        retiredInspect = button(workflow.screen(), "Inspect result");
        press(workflow.screen(), retiredInspect);
        stage = 7;
      }
      case 7 -> {
        check.accept(
            "result navigation does not repeat the effect or replace its identity",
            operation().observedOperation().orElseThrow().id().equals(identity) && starts == 1);
        press(operation(), button(operation(), "Back"));
        observable = false;
        blocked = false;
        stage = 8;
      }
      case 8 -> {
        if (NativeScreens.current(client) != workflow.screen()) return false;
        var inspect = button(workflow.screen(), "Inspect result");
        if (inspect.active || !button(workflow.screen(), "Inspect values").active) return false;
        check.accept(
            "observation admission and starting a new effect remain independent", starts == 1);
        observable = true;
        stage = 9;
      }
      case 9 -> {
        if (!button(workflow.screen(), "Inspect result").active) return false;
        NativeClientProbe.press(retiredInspect);
        check.accept(
            "retired result callbacks cannot reopen a view",
            NativeScreens.current(client) == workflow.screen());
        press(workflow.screen(), button(workflow.screen(), "Inspect values"));
        stage = 10;
      }
      case 10 -> {
        var state = operation().observedOperation().orElse(null);
        if (state == null || entered != 2) return false;
        check.accept(
            "explicit repeat starts a distinct operation once",
            starts == 2 && !state.id().equals(identity));
        press(operation(), button(operation(), "Request cancellation"));
        stage = 11;
      }
      case 11 -> {
        var state = operation().observedOperation().orElseThrow();
        if (!state.cancellationRequested()) return false;
        check.accept(
            "cancellation request retains pending work until its provider drains",
            !state.terminal());
        result.complete("Drained");
        stage = 12;
      }
      case 12 -> {
        var state = operation().observedOperation().orElseThrow();
        if (!state.terminal()) return false;
        check.accept(
            "cancelled operation remains an actual cancellation rather than a save receipt",
            state.state() == OperationSnapshot.State.CANCELLED && starts == 2);
        press(operation(), button(operation(), "Back"));
        observable = false;
        press(workflow.screen(), button(workflow.screen(), "Inspect values"));
        check.accept(
            "accepted operation request remains retained when inspection refuses",
            starts == 3 && NativeScreens.current(client) == workflow.screen());
        stage = 13;
      }
      case 13 -> {
        if (entered != 3) return false;
        check.accept(
            "inspection refusal truthfully acknowledges the entered request and safe next step",
            workflow.screen().getNarrationMessage().getString().contains("operation was requested")
                && workflow
                    .screen()
                    .getNarrationMessage()
                    .getString()
                    .contains("before requesting it again"));
        result.complete("Previously unobserved request completed.");
        observable = true;
        stage = 14;
      }
      case 14 -> {
        var inspect =
            widgets(workflow.screen()).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(
                    widget ->
                        widget.getMessage().getString().equals("Inspect result") && widget.active)
                .findFirst();
        if (inspect.isEmpty()) return false;
        press(workflow.screen(), inspect.orElseThrow());
        stage = 15;
      }
      case 15 -> {
        var state = operation().observedOperation().orElse(null);
        if (state == null || !state.terminal()) return false;
        check.accept(
            "restored inspection observes that same request without repeating its effect",
            starts == 3
                && state.state() == OperationSnapshot.State.COMPLETED
                && state.result().orElseThrow().equals("Previously unobserved request completed."));
        press(operation(), button(operation(), "Back"));
        navigateAtStart = true;
        press(workflow.screen(), button(workflow.screen(), "Inspect values"));
        stage = 16;
      }
      case 16 -> {
        if (!navigation.isDone() || entered != 4) return false;
        check.accept(
            "reentrant named-action navigation wins without an obsolete inspector",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && NativeScreens.current(client) == workflow.screen()
                && button(workflow.screen(), "Actions") != null
                && starts == 4);
        result.complete("Reentrant request completed.");
        navigation =
            workflow
                .navigation()
                .open(
                    new ClientDestination.OperationResult(
                        DocumentId.parse("fixture:inspect_values"), latest.snapshot().id()))
                .completion()
                .toCompletableFuture();
        stage = 17;
      }
      case 17 -> {
        if (!navigation.isDone() || operation().observedOperation().isEmpty()) return false;
        check.accept(
            "explicit result navigation retains the reentrant request without another start",
            navigation.join().outcome() == NavigationAttempt.Outcome.HANDED_OFF
                && operation().observedOperation().orElseThrow().id().equals(latest.snapshot().id())
                && starts == 4);
        workflow.close();
        owner.close();
        NativeScreens.open(client, parent);
        check.accept(
            "operation inspection leaves settings and unopened owners unchanged",
            !workflow.isOpen()
                && Arrays.equals(
                    source,
                    Files.readAllBytes(
                        client
                            .gameDirectory
                            .toPath()
                            .resolve("config/reform_platform_example/workflow_preferences.json"))));
        stage = 18;
        return true;
      }
      case 18 -> {
        return true;
      }
      case 19 -> {}
      default -> throw new AssertionError("Unknown operation presentation stage " + stage);
    }
    return false;
  }
}
