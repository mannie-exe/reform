package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real custom body admission and return state; no alternate screen, document, or writer. */
final class PlatformCustomViewChecks {
  private static final DestinationId COUNTER = DestinationId.parse("fixture:custom_counter"),
      BROKEN = DestinationId.parse("fixture:custom_broken");
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private ClientEditingWorkflow workflow;
  private ClientViewState<Integer> state;
  private CompletableFuture<NavigationAttempt.Result> pending;
  private NavigationBookmark bookmark;
  private Button retired;
  private byte[] source;
  private int stage, opens, closes, partialCloses;
  private boolean failProjection;

  PlatformCustomViewChecks(BiConsumer<String, Boolean> check) {
    this.check = check;
  }

  private Component text(String value) {
    return Component.literal(value);
  }

  private ClientWorkflowLayout.Category category(String id, String title) {
    return new ClientWorkflowLayout.Category(DocumentId.parse(id), text(title), Component.empty());
  }

  private void enter(ClientDestination target) {
    pending = workflow.navigation().open(target).completion().toCompletableFuture();
  }

  private boolean ready() {
    if (!pending.isDone()) return false;
    check.accept(
        "custom body navigation mounts before reporting revealed",
        pending.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  private void press(String caption) {
    var button = button(workflow.screen(), caption);
    check.accept(
        "custom body action participates in native focus", focus(workflow.screen(), button));
    click(workflow.screen(), button);
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
        var form = PlatformWorkflowExample.settings().form(text("Unused custom owner"));
        var tools = category("fixture:custom_tools", "Tools");
        var layout =
            new ClientWorkflowLayout(
                List.of(
                    new ClientWorkflowLayout.Group(
                        List.of(tools, category(COUNTER.toString(), "Counter")),
                        List.of(),
                        List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(tools, category(BROKEN.toString(), "Broken")),
                        List.of(),
                        List.of()),
                    new ClientWorkflowLayout.Group(
                        List.of(category("fixture:custom_settings", "Settings")),
                        List.of(
                            new ClientWorkflowLayout.Placement(
                                form.route(), form.route(), form.title(), Optional.empty())),
                        List.of())),
                List.of());
        var key =
            new ViewKey<>(
                COUNTER,
                new ViewStateKey<>(
                    DocumentId.parse("fixture:counter_state"), ValueTypes.integer(0, 100)));
        var body =
            new ClientCustomView<>(
                key,
                0,
                (scope, current) -> {
                  opens++;
                  state = current;
                  scope.onClose(() -> closes++);
                  return () -> {
                    if (failProjection)
                      throw new IllegalArgumentException("fixture cached projection failure");
                    return new ClientViewContent(
                        List.of(
                            new ClientViewContent.Group(
                                "counter",
                                text("Retained counter"),
                                text("View state only"),
                                List.of(
                                    new ClientViewContent.Text(
                                        "value",
                                        text("Current count"),
                                        text("Count " + current.value())),
                                    new ClientViewContent.Action(
                                        "increment",
                                        text("Increment"),
                                        text("Changes only view state"),
                                        () -> current.replace(current.value() + 1)),
                                    new ClientViewContent.Link(
                                        "home",
                                        text("Return Home"),
                                        Component.empty(),
                                        new ClientDestination.Home())))));
                  };
                });
        var broken =
            new ClientCustomView<>(
                new ViewKey<>(BROKEN, key.state()),
                0,
                (scope, current) -> {
                  scope.onClose(() -> partialCloses++);
                  throw new IllegalStateException("fixture factory failure");
                });
        var metadata =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:custom_workspace"), text("Custom workspace")))
                .withView(body)
                .withView(broken);
        check.accept(
            "custom declarations validate without invoking either factory",
            metadata.validate(List.of(form), layout).valid() && opens == 0 && partialCloses == 0);
        workflow = ClientEditingWorkflow.open(parent, List.of(form), layout, metadata);
        NativeScreens.open(client, workflow.screen());
        check.accept(
            "custom Home remains lazy and admits no file owner",
            opens == 0
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        enter(new ClientDestination.View(COUNTER));
        stage = 1;
      }
      case 1 -> {
        if (!ready()) return false;
        geometry(workflow.screen());
        check.accept(
            "custom factory enters once in the existing native workflow",
            opens == 1 && NativeScreens.current(client) == workflow.screen());
        press("Increment");
        stage = 2;
      }
      case 2 -> {
        check.accept(
            "custom view state updates its measured native content",
            state.value() == 1 && measuredText(workflow.screen()).contains("Count 1"));
        retired = button(workflow.screen(), "Increment");
        bookmark = workflow.navigation().bookmark();
        focus(workflow.screen(), button(workflow.screen(), "Return Home"));
        pending = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 10;
      }
      case 10 -> {
        if (!ready()) return false;
        check.accept(
            "same-destination bookmark restores its captured native focus",
            button(workflow.screen(), "Increment").isFocused());
        enter(new ClientDestination.Home());
        stage = 3;
      }
      case 3 -> {
        if (!ready()) return false;
        NativeClientProbe.press(retired);
        check.accept("a hidden custom action cannot alter its retained state", state.value() == 1);
        check.accept("rooted Home has no Up destination", workflow.navigation().up().isEmpty());
        pending = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 4;
      }
      case 4 -> {
        if (!ready()) return false;
        check.accept(
            "public bookmark restores the same custom session and data",
            opens == 1
                && closes == 0
                && state.value() == 1
                && button(workflow.screen(), "Increment") != retired);
        boolean refused = false;
        try {
          state.replace(101);
        } catch (IllegalArgumentException expected) {
          refused = true;
        }
        check.accept(
            "invalid view state refuses without replacing the prior bookmark",
            refused && state.value() == 1);
        failProjection = true;
        state.refresh();
        stage = 5;
      }
      case 5 -> {
        check.accept(
            "failed cached custom projection retains content and useful error",
            measuredText(workflow.screen()).contains("fixture cached projection failure")
                && button(workflow.screen(), "Increment") != null);
        failProjection = false;
        state.refresh();
        pending = workflow.navigation().up().orElseThrow().completion().toCompletableFuture();
        stage = 6;
      }
      case 6 -> {
        if (!ready()) return false;
        check.accept(
            "Up follows the declared parent rather than browser history",
            workflow
                .navigation()
                .current()
                .orElseThrow()
                .equals(
                    new ClientDestination.Category(DestinationId.parse("fixture:custom_tools"))));
        pending = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 7;
      }
      case 7 -> {
        if (!ready()) return false;
        enter(new ClientDestination.View(BROKEN));
        stage = 8;
      }
      case 8 -> {
        if (!pending.isDone()) return false;
        check.accept(
            "failed custom factory cleans partial lifetime and keeps origin",
            pending.join().outcome() == NavigationAttempt.Outcome.FAILED
                && partialCloses == 1
                && workflow
                    .navigation()
                    .current()
                    .orElseThrow()
                    .equals(new ClientDestination.View(COUNTER))
                && button(workflow.screen(), "Increment") != null);
        geometry(workflow.screen());
        workflow.close();
        boolean closed = false;
        try {
          state.replace(2);
        } catch (design.inherent.reform.owner.PolicyRefusal expected) {
          closed = true;
        }
        check.accept(
            "custom lifetime closes once and rejects later state changes", closes == 1 && closed);
        check.accept(
            "custom views never open or write their unused file owner",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened)
                && Arrays.equals(
                    source,
                    Files.readAllBytes(
                        client
                            .gameDirectory
                            .toPath()
                            .resolve("config/reform_platform_example/workflow_preferences.json"))));
        NativeScreens.open(client, parent);
        stage = 9;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }
}
