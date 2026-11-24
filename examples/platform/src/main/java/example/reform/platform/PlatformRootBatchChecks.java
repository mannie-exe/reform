package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real complete-owner review entry; each member keeps its source and actor. */
final class PlatformRootBatchChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private byte[] original;
  private long version;
  private int stage;

  PlatformRootBatchChecks(BiConsumer<String, Boolean> check) {
    this.check = check;
  }

  private byte[] source() throws Exception {
    return Files.readAllBytes(
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json"));
  }

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        original = source();
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Batch workspace"),
                List.of(
                    PlatformWorkflowExample.settings()
                        .form(Component.literal("Edited preferences")),
                    PlatformWorkspaceChecks.navigationTwin()));
        NativeScreens.open(client, workflow.screen());
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Document(route))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!navigation.isDone()) return false;
        check.accept(
            "batch fixture opens actual edited owner",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(route);
        if (editor.busy() || editor.form().fieldControl(FieldPath.ROOT.field("label")).isEmpty())
          return false;
        replace(
            workflow.screen(),
            (EditBox) editor.form().fieldControl(FieldPath.ROOT.field("label")).orElseThrow(),
            "Batch draft");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        version = editor.view().snapshot().orElseThrow().version();
        var control = button(workflow.screen(), "Review all documents");
        check.accept(
            "batch review entry belongs to actual native tree", focus(workflow.screen(), control));
        click(workflow.screen(), control);
        stage = 3;
      }
      case 3 -> {
        var current = NativeScreens.current(client);
        check.accept(
            "complete batch review keeps original native root: "
                + current.getClass().getSimpleName(),
            current == workflow.screen()
                && button(current, "Save reviewed documents") != null
                && workflow.editor(route) == editor
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(original, source()));
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 4;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }
}
