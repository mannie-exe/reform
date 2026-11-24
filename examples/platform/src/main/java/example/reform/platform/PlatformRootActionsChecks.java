package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual whole-owner menu, responsive native controls, and the original retained draft. */
final class PlatformRootActionsChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final FieldPath label = FieldPath.ROOT.field("label");
  private final int[] width = new int[1], height = new int[1];
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private byte[] source;
  private Button retired;
  private long version;
  private ClientDestination destination;
  private int stage;

  PlatformRootActionsChecks(
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

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        source = source();
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Document actions workspace"),
                List.of(
                    PlatformWorkflowExample.settings()
                        .form(Component.literal("Whole preferences"))));
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
            "document-action owner acknowledges actual mounting",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(route);
        if (editor.busy() || editor.form().fieldControl(label).isEmpty()) return false;
        var field = (EditBox) editor.form().fieldControl(label).orElseThrow();
        replace(workflow.screen(), field, "Menu draft");
        field.setCursorPosition(3);
        field.setHighlightPos(1);
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        version = editor.view().snapshot().orElseThrow().version();
        destination = workflow.navigation().current().orElseThrow();
        var open = button(workflow.screen(), "Document actions…");
        check.accept(
            "document-action entry is an actual focus target", focus(workflow.screen(), open));
        click(workflow.screen(), open);
        stage = 3;
      }
      case 3 -> {
        check.accept(
            "whole-document actions remain in the same native root and actor",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && workflow.screen().getNarrationMessage().getString().contains("Document actions")
                && editor.dirty()
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        check.accept(
            "menu opening does not add an effect or a separate navigation owner",
            workflow.navigation().current().orElseThrow().equals(destination));
        retired = button(workflow.screen(), "Defaults");
        check.accept(
            "root action menu has the existing whole-document callbacks",
            retired.active && button(workflow.screen(), "Undo").active);
        geometry(workflow.screen());
        NativeClientProbe.size(client, width, height);
        stage = 90;
        capture.accept("form-root-actions-wide.png", () -> stage = 4);
      }
      case 4 -> {
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 5;
      }
      case 5 -> {
        if (workflow.screen().width > 340) return false;
        NativeClientProbe.pressRetired(retired);
        check.accept(
            "action-menu reflow retains source/draft and retires prior native callbacks",
            workflow.editor(route) == editor
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        geometry(workflow.screen());
        stage = 90;
        capture.accept("form-root-actions-narrow.png", () -> stage = 6);
      }
      case 6 -> {
        NativeClientProbe.resize(client, width[0], height[0]);
        NativeClientProbe.reflow(client);
        var back = button(workflow.screen(), "Back to settings");
        check.accept(
            "root menu return remains a native focus target", focus(workflow.screen(), back));
        click(workflow.screen(), back);
        stage = 7;
      }
      case 7 -> {
        var field = (EditBox) editor.form().fieldControl(label).orElseThrow();
        check.accept(
            "returning from document actions retains the exact draft and input selection",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && field.getValue().equals("Menu draft")
                && field.getCursorPosition() == 3
                && field.getHighlighted().equals("en")
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 8;
        return true;
      }
      case 90 -> {
        return false;
      }
      default -> {
        return true;
      }
    }
    return false;
  }
}
