package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.DestinationId;
import design.inherent.reform.presentation.FormGeometry;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Native witness for lazy Home, stable header offers and one owned canvas lifecycle. */
final class PlatformShellChecks implements AutoCloseable {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private int stage, creates, closes, draws;
  private int resourceCleanups, lateCleanups;
  private final java.util.ArrayList<String> cleanupOrder = new java.util.ArrayList<>();
  private ClientViewContext lastScope;
  private final IllegalStateException factoryFailure =
      new IllegalStateException("Intentional header factory refusal");
  private final IllegalStateException cleanupFailure =
      new IllegalStateException("Intentional partial cleanup failure");
  private FormGeometry.Placement lastCanvas, homeCanvas;
  private byte[] source;
  private ClientEditor<?> opened;

  PlatformShellChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private java.nio.file.Path sourcePath() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  private ClientWorkspacePresentation presentation(boolean fail) {
    var header =
        new HeaderComposition(
            List.of(
                new HeaderComposition.Row(
                    List.of(HeaderComposition.Slot.IDENTITY, HeaderComposition.Slot.LOCATION)),
                new HeaderComposition.Row(
                    List.of(HeaderComposition.Slot.SUBTITLE, HeaderComposition.Slot.CANVAS))),
            Optional.of(
                new HeaderComposition.Canvas(
                    Component.literal("Cached header illustration"),
                    80,
                    160,
                    36,
                    scope -> {
                      creates++;
                      lastScope = scope;
                      scope.requireCurrent();
                      scope.onClose(
                          () -> {
                            scope.checkNativeThread();
                            cleanupOrder.add("resource");
                            resourceCleanups++;
                            if (fail) throw cleanupFailure;
                          });
                      if (fail) {
                        scope.onClose(() -> lateCleanups++);
                        throw factoryFailure;
                      }
                      return new ClientHeaderCanvas() {
                        @Override
                        public void render(
                            FormCanvas canvas,
                            Font font,
                            FormGeometry.Placement bounds,
                            design.inherent.reform.presentation.ThemeValues theme) {
                          draws++;
                          lastCanvas = bounds;
                          canvas.fill(
                              bounds.x(),
                              bounds.y(),
                              bounds.x() + bounds.width(),
                              bounds.y() + bounds.height(),
                              0xff203040);
                        }

                        @Override
                        public void close() {
                          scope.checkNativeThread();
                          cleanupOrder.add("canvas");
                          closes++;
                        }
                      };
                    })));
    return ClientWorkspacePresentation.standard(
            new WorkspaceIdentity(
                DocumentId.parse("reform_platform_example:shell"),
                Component.literal("Stable settings workspace"),
                Component.literal("Declared identity stays in place")))
        .withHeader(header);
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        source = Files.readAllBytes(sourcePath());
        var forms = PlatformWorkflowExample.forms();
        var metadata = presentation(false);
        check.accept(
            "shell metadata validates without entering a canvas or source",
            metadata.validate(forms, null).valid() && creates == 0);
        var missing =
            metadata.withInitialDestination(DestinationId.parse("reform_platform_example:missing"));
        check.accept(
            "missing initial destination has structured declaration issues",
            !missing.validate(forms, null).valid());
        try {
          ClientEditingWorkflow.open(parent, forms, null, missing);
          throw new AssertionError("Missing shell target was admitted");
        } catch (PolicyRefusal expected) {
          check.accept("invalid shell refuses before canvas or source entry", creates == 0);
        }
        workflow = ClientEditingWorkflow.open(parent, forms, null, metadata);
        check.accept(
            "constructing a shell does not enter owner or artwork factories",
            creates == 0
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        NativeScreens.open(client, workflow.screen());
        stage++;
      }
      case 1 -> {
        if (draws == 0) return false;
        check.accept(
            "actual Home mounts one cached artwork and no source owners",
            creates == 1
                && workflow.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        check.accept(
            "Home exposes no document save or defaults actions",
            widgets(workflow.screen()).stream()
                .noneMatch(
                    w ->
                        w instanceof Button
                            && List.of("Save", "Document actions…")
                                .contains(w.getMessage().getString())));
        check.accept(
            "Home narration reports zero cached owners",
            workflow.screen().getNarrationMessage().getString().contains("0 opened"));
        homeCanvas = lastCanvas;
        stage++;
        capture.accept(
            "shell-home.png",
            () -> click(workflow.screen(), button(workflow.screen(), "Client preferences")));
      }
      case 2 -> {
        var state =
            workflow.documents().stream()
                .filter(s -> s.document().equals(PlatformWorkflowExample.DEFINITION.id()))
                .findFirst()
                .orElseThrow();
        if (!state.opened() || state.busy()) return false;
        opened = workflow.editor(PlatformWorkflowExample.DEFINITION.id());
        if (opened.view().availability() != EditorActor.Availability.READY) return false;
        check.accept(
            "Home selection admits exactly the selected real owner",
            workflow.documents().stream()
                    .filter(ClientEditingWorkflow.DocumentState::opened)
                    .count()
                == 1);
        check.accept(
            "ordinary location changes retain header artwork bounds and instance",
            homeCanvas.equals(lastCanvas) && creates == 1);
        var distance = design.inherent.reform.definition.FieldPath.ROOT.field("distance");
        check.accept(
            "header navigation leaves the real input focusable",
            opened.form().focusField(distance));
        replace(
            workflow.screen(),
            (net.minecraft.client.gui.components.EditBox)
                opened.form().fieldControl(distance).orElseThrow(),
            "97");
        stage++;
        capture.accept("shell-document.png", workflow::showHome);
      }
      case 3 -> {
        if (opened.busy()) return false;
        check.accept(
            "returning Home retains the same actual editor",
            workflow.editor(PlatformWorkflowExample.DEFINITION.id()) == opened
                && homeCanvas.equals(lastCanvas)
                && creates == 1);
        check.accept(
            "Home retains the complete dirty owner draft without exposing document Save",
            opened.view().dirty()
                && ((design.inherent.reform.edit.DraftNode.Scalar)
                        opened
                            .view()
                            .snapshot()
                            .orElseThrow()
                            .draft()
                            .at(design.inherent.reform.definition.FieldPath.ROOT.field("distance")))
                    .raw()
                    .equals("97")
                && widgets(workflow.screen()).stream()
                    .noneMatch(
                        w ->
                            w instanceof Button
                                && List.of("Save", "Document actions…")
                                    .contains(w.getMessage().getString())));
        check.accept(
            "shell navigation does not persist or modify the source",
            Arrays.equals(source, Files.readAllBytes(sourcePath())));
        workflow.close();
        workflow.close();
        check.accept("canvas is released once at actual workflow retirement", closes == 1);
        check.accept(
            "returned canvas retires before its registered resources on the native thread",
            cleanupOrder.equals(List.of("canvas", "resource"))
                && resourceCleanups == 1
                && !lastScope.isOpen());
        try {
          lastScope.onClose(
              () -> {
                throw new AssertionError("Retired callback registered");
              });
          throw new AssertionError("Retired canvas accepted a new cleanup hook");
        } catch (PolicyRefusal expected) {
          check.accept("retired view context cannot acquire new ownership", true);
        }
        NativeScreens.open(client, parent);
        workflow =
            ClientEditingWorkflow.open(
                parent, PlatformWorkflowExample.forms(), null, presentation(true));
        NativeScreens.open(client, workflow.screen());
        stage++;
      }
      case 4 -> {
        check.accept(
            "failed optional artwork leaves Home and its destinations usable",
            creates == 2
                && workflow.isOpen()
                && button(workflow.screen(), "Client preferences").active);
        workflow.showHome();
        check.accept("failed artwork is not recreated during ordinary navigation", creates == 2);
        check.accept(
            "partial factory failure attempts every registered cleanup and retains its cause",
            resourceCleanups == 2
                && lateCleanups == 1
                && !lastScope.isOpen()
                && Arrays.stream(factoryFailure.getSuppressed())
                    .anyMatch(cause -> cause == cleanupFailure));
        check.accept(
            "optional artwork failure does not open an owner or write",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened)
                && Arrays.equals(source, Files.readAllBytes(sourcePath())));
        stage++;
        capture.accept(
            "shell-canvas-failure.png",
            () -> {
              workflow.close();
              NativeScreens.open(client, parent);
            });
      }
      case 5 -> {
        return true;
      }
      default -> throw new AssertionError("Unknown shell scene");
    }
    return false;
  }

  @Override
  public void close() {
    if (workflow != null) workflow.close();
  }
}
