package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.DocumentReview;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual same-root inspection, original actor/capture, no private view or comparison service. */
final class PlatformRootInspectionChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final FieldPath label = FieldPath.ROOT.field("label"),
      distance = FieldPath.ROOT.field("distance");
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private SettingsForm<?> retainedForm;
  private ClientInspection inspection;
  private DocumentReview reviewed;
  private CompletableFuture<NavigationAttempt.Result> pending;
  private NavigationBookmark bookmark;
  private byte[] source;
  private String originalDistance;
  private EditBox originalLabelControl;
  private int stage;

  PlatformRootInspectionChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private byte[] source() throws Exception {
    return Files.readAllBytes(
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json"));
  }

  private void enter(ClientDestination target) {
    pending = workflow.navigation().open(target).completion().toCompletableFuture();
  }

  private boolean revealed() {
    if (!pending.isDone()) return false;
    check.accept(
        "root inspection link acknowledges the actual mounted destination",
        pending.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        source = source();
        var form =
            PlatformWorkflowExample.settings().form(Component.literal("Retained preferences"));
        workflow =
            ClientEditingWorkflow.open(
                parent, Component.literal("Inspection workspace"), List.of(form));
        NativeScreens.open(client, workflow.screen());
        enter(new ClientDestination.Document(route));
        stage = 1;
      }
      case 1 -> {
        if (!revealed()) return false;
        editor = workflow.editor(route);
        retainedForm = editor.form();
        if (editor.busy() || retainedForm.fieldControl(label).isEmpty()) return false;
        originalLabelControl = (EditBox) retainedForm.fieldControl(label).orElseThrow();
        originalDistance = ((EditBox) retainedForm.fieldControl(distance).orElseThrow()).getValue();
        replace(
            workflow.screen(),
            (EditBox) retainedForm.fieldControl(label).orElseThrow(),
            "Retained inspection draft");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        var inspect = ownerAction(workflow.screen(), "Inspect…");
        if (!inspect.active) return false;
        click(workflow.screen(), inspect);
        stage = 3;
      }
      case 3 -> {
        var actual = workflow.inspection().orElse(null);
        if (actual == null || actual.reviewedInput().isEmpty()) return false;
        inspection = actual;
        reviewed = actual.reviewedInput().orElseThrow();
        check.accept(
            "ordinary Inspect mounts its shared body inside the original native root",
            NativeScreens.current(client) == workflow.screen()
                && !(NativeScreens.current(client) instanceof ReviewScreen<?>));
        check.accept(
            "root inspection retains the exact original form actor and unsaved draft",
            workflow.editor(route) == editor
                && editor.form() == retainedForm
                && editor.dirty()
                && reviewed.target().document().equals(route)
                && Arrays.equals(source, source()));
        check.accept(
            "root inspection replaces editable controls without exposing ordinary owner Save",
            !widgets(workflow.screen()).contains(originalLabelControl)
                && widgets(workflow.screen()).stream()
                    .noneMatch(w -> w.getMessage().getString().equals("Save")));
        check.accept(
            "typed current location identifies the real inspection route",
            workflow
                .navigation()
                .current()
                .orElseThrow()
                .equals(new ClientDestination.Inspection(route)));
        geometry(workflow.screen());
        NativeClientProbe.size(client, windowWidth, windowHeight);
        stage = 30;
        capture.accept("form-root-inspection-wide.png", () -> stage = 32);
      }
      case 32 -> {
        var query = input(workflow.screen(), "Find a field");
        replace(workflow.screen(), query, "a");
        query.setCursorPosition(1);
        query.setHighlightPos(0);
        check.accept(
            "root inspection field is a non-default native focus target",
            focus(workflow.screen(), button(workflow.screen(), "Label")));
        bookmark = workflow.navigation().bookmark();
        replace(workflow.screen(), query, "");
        check.accept(
            "root inspection footer is a distinct native focus target",
            focus(workflow.screen(), button(workflow.screen(), "Validate")));
        pending = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 31;
      }
      case 31 -> {
        if (!revealed()) return false;
        net.minecraft.client.gui.components.events.GuiEventListener focused =
            workflow.screen().getFocused();
        while (focused
                instanceof
                net.minecraft.client.gui.components.events.ContainerEventHandler container
            && container.getFocused() != null) focused = container.getFocused();
        check.accept(
            "inspection bookmark restores its own actual mounted field focus",
            focused == button(workflow.screen(), "Label")
                && input(workflow.screen(), "Find a field").getValue().equals("a")
                && input(workflow.screen(), "Find a field").getCursorPosition() == 1);
        stage = 4;
      }
      case 4 -> {
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 5;
      }
      case 5 -> {
        if (workflow.screen().width > 340) return false;
        check.accept(
            "root inspection narrow reflow retains the same capture and owner",
            NativeScreens.current(client) == workflow.screen()
                && workflow.inspection().orElseThrow() == inspection
                && inspection.reviewedInput().orElseThrow() == reviewed
                && editor.form() == retainedForm
                && Arrays.equals(source, source()));
        geometry(workflow.screen());
        stage = 30;
        capture.accept("form-root-inspection-narrow.png", () -> stage = 6);
      }
      case 6 -> {
        NativeClientProbe.resize(client, windowWidth[0], windowHeight[0]);
        NativeClientProbe.reflow(client);
        var back =
            widgets(workflow.screen()).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(w -> w.getMessage().getString().equals("Back"))
                .max(Comparator.comparingInt(Button::getY))
                .orElseThrow();
        check.accept(
            "root inspection Back is an actual native focus target",
            focus(workflow.screen(), back));
        click(workflow.screen(), back);
        stage = 7;
      }
      case 7 -> {
        if (workflow.inspection().isPresent()) return false;
        check.accept(
            "inspection Back restores original editing without replacing the native root",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && editor.form() == retainedForm
                && editor.dirty());
        enter(new ClientDestination.Inspection(route));
        stage = 8;
      }
      case 8 -> {
        if (!revealed()) return false;
        check.accept(
            "typed inspection reuses its admitted capture without an implicit refresh",
            workflow.inspection().orElseThrow() == inspection
                && inspection.reviewedInput().orElseThrow() == reviewed
                && Arrays.equals(source, source()));
        pending = workflow.navigation().up().orElseThrow().completion().toCompletableFuture();
        stage = 9;
      }
      case 9 -> {
        if (!revealed() || workflow.inspection().isPresent()) return false;
        replace(
            workflow.screen(), (EditBox) retainedForm.fieldControl(distance).orElseThrow(), "bad");
        enter(new ClientDestination.Inspection(route));
        stage = 10;
      }
      case 10 -> {
        if (!pending.isDone()) return false;
        check.accept(
            "typed inspection refuses rejected native text before moving the origin",
            pending.join().outcome() == NavigationAttempt.Outcome.REFUSED
                && pending.join().report().issues().stream()
                    .anyMatch(i -> i.code().equals("inspection.input_unaccepted"))
                && workflow.inspection().isEmpty()
                && NativeScreens.current(client) == workflow.screen()
                && ((EditBox) retainedForm.fieldControl(distance).orElseThrow())
                    .getValue()
                    .equals("bad"));
        replace(
            workflow.screen(),
            (EditBox) retainedForm.fieldControl(distance).orElseThrow(),
            originalDistance);
        stage = 11;
      }
      case 11 -> {
        if (editor.busy()) return false;
        workflow.discard(route);
        check.accept(
            "explicit owner retirement disposes its retained inspection capture",
            inspection.reviewedInput().isEmpty());
        enter(new ClientDestination.Document(route));
        stage = 12;
      }
      case 12 -> {
        if (!revealed()) return false;
        check.accept(
            "discard opens a fresh owner only through explicit document navigation",
            workflow.editor(route) != editor && !workflow.editor(route).dirty());
        enter(new ClientDestination.Inspection(route));
        stage = 13;
      }
      case 13 -> {
        if (!revealed()) return false;
        var fresh = workflow.inspection().orElseThrow();
        if (fresh.reviewedInput().isEmpty()) return false;
        check.accept(
            "new owner inspection never reuses a retired session capture",
            fresh != inspection
                && fresh
                    .reviewedInput()
                    .orElseThrow()
                    .target()
                    .equals(
                        design.inherent.reform.edit.DraftRevision.of(
                            workflow.editor(route).view().snapshot().orElseThrow()))
                && Arrays.equals(source, source()));
        workflow.close();
        check.accept(
            "root closure releases cached inspection without writing saved settings",
            fresh.reviewedInput().isEmpty() && Arrays.equals(source, source()));
        var closed = workflow.navigation().restore(bookmark).result().orElseThrow();
        check.accept(
            "closed inspection bookmark reports closed without resurrecting its owner",
            closed.outcome() == NavigationAttempt.Outcome.CLOSED);
        NativeScreens.open(client, parent);
        stage = 14;
        return true;
      }
      case 30 -> {
        return false;
      }
      default -> {
        return true;
      }
    }
    return false;
  }
}
