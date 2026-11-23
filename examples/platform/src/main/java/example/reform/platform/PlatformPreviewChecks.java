package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.client.ClientEditingWorkflow;
import design.inherent.reform.minecraft.client.ClientEditor;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.DraftPreviewInput;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual retained native input participates in the public READ-only preview capture. */
final class PlatformPreviewChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final BiConsumer<String, Boolean> check;
  private final PlatformMeshChecks meshes;
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private Screen parent;
  private byte[] original;
  private DraftRevision initial;
  private CompletableFuture<? extends DraftPreviewInput<?>> captured;
  private int stage;

  PlatformPreviewChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    meshes = new PlatformMeshChecks(check, capture);
  }

  private byte[] bytes() throws Exception {
    return Files.readAllBytes(
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json"));
  }

  private boolean ready() {
    return !editor.busy()
        && editor.view().availability() == EditorActor.Availability.READY
        && editor.view().snapshot().isPresent();
  }

  private DraftRevision revision() {
    return DraftRevision.of(editor.view().snapshot().orElseThrow());
  }

  private void edit(String value) {
    var path = FieldPath.ROOT.field("distance");
    check.accept(
        "preview field is an actual mounted native control", editor.form().focusField(path));
    replace(workflow.screen(), (EditBox) editor.form().fieldControl(path).orElseThrow(), value);
  }

  private void refuses(DraftRevision expected, String code) {
    try {
      editor.capturePreview(EntryPoint.GUI, expected);
      throw new AssertionError("Preview input was not refused: " + code);
    } catch (PolicyRefusal refused) {
      check.accept(
          "native preview refuses " + code,
          refused.report().issues().stream().anyMatch(issue -> issue.code().equals(code)));
    }
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!meshes.tick()) return false;
        PlatformGraphicImageChecks.run(check);
        parent = NativeScreens.current(client);
        original = bytes();
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Preview input checks"),
                List.of(PlatformWorkflowExample.settings().form(Component.literal("Preferences"))));
        NativeScreens.open(client, workflow.screen());
        workflow.show(PlatformWorkflowExample.DEFINITION.id());
        editor = workflow.editor(PlatformWorkflowExample.DEFINITION.id());
        stage++;
      }
      case 1 -> {
        if (!ready()) return false;
        initial = revision();
        captured =
            editor.capturePreview(EntryPoint.GUI, initial).completion().toCompletableFuture();
        stage++;
      }
      case 2 -> {
        if (!captured.isDone()) return false;
        var input = captured.join();
        check.accept(
            "native preview captures complete actual candidate",
            input.candidate().value() instanceof PlatformWorkflowExample.Preferences
                && input.stamp().draft().equals(initial));
        check.accept("native preview does not persist", Arrays.equals(original, bytes()));
        edit("broken");
        refuses(initial, "preview.input_unaccepted");
        stage++;
      }
      case 3 -> {
        if (!ready() || editor.view().validation().valid()) return false;
        check.accept(
            "invalid native preview input remains visible",
            ((EditBox) editor.form().fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("broken"));
        edit("128");
        stage++;
      }
      case 4 -> {
        if (!ready() || !editor.view().validation().valid() || !editor.canSubmit()) return false;
        refuses(initial, "preview.stale");
        captured =
            editor.capturePreview(EntryPoint.GUI, revision()).completion().toCompletableFuture();
        stage++;
      }
      case 5 -> {
        if (!captured.isDone()) return false;
        var input = captured.join();
        check.accept(
            "repaired native preview uses dirty candidate, not loaded fallback",
            input.candidate().value() instanceof PlatformWorkflowExample.Preferences value
                && value.distance() == 128
                && editor.view().dirty());
        check.accept("repaired preview leaves source unchanged", Arrays.equals(original, bytes()));
        workflow.close();
        NativeScreens.open(client, parent);
        check.accept(
            "preview lifetime closes without a save",
            !editor.isOpen() && Arrays.equals(original, bytes()));
        stage++;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }

  com.google.gson.JsonObject graphicsReport() {
    return meshes.report();
  }

  void close() {
    meshes.close();
    if (workflow != null) workflow.close();
  }
}
