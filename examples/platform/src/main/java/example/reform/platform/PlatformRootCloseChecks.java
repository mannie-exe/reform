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

/** Native unsaved decisions retain the root and actual owner, without an implicit save. */
final class PlatformRootCloseChecks {
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
  private Button retiredDiscard;
  private ClientInspection inspection;
  private design.inherent.reform.operation.DocumentReview reviewed;
  private long version;
  private int stage;

  PlatformRootCloseChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
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

  private EditBox field() {
    return (EditBox) editor.form().fieldControl(label).orElseThrow();
  }

  private void press(String caption) {
    var host = NativeScreens.current(client);
    var control = button(host, caption);
    check.accept("close decision control belongs to actual native tree", focus(host, control));
    click(host, control);
  }

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        source = source();
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Close workspace"),
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
            "close fixture opens actual owner",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(route);
        if (editor.busy() || editor.form().fieldControl(label).isEmpty()) return false;
        replace(workflow.screen(), field(), "Close draft");
        field().setCursorPosition(4);
        field().setHighlightPos(1);
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        version = editor.view().snapshot().orElseThrow().version();
        press("Done");
        stage = 3;
      }
      case 3 -> {
        check.accept(
            "unsaved decision stays in original native root and actual owner",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && editor.dirty()
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        retiredDiscard = button(workflow.screen(), "Discard draft");
        check.accept(
            "close review is distinct from saving",
            button(workflow.screen(), "Review changes").active);
        geometry(workflow.screen());
        NativeClientProbe.size(client, width, height);
        stage = 90;
        capture.accept("form-root-close-wide.png", () -> stage = 4);
      }
      case 4 -> {
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 5;
      }
      case 5 -> {
        if (workflow.screen().width > 340) return false;
        check.accept(
            "narrow close decision preserves unsaved values and source",
            workflow.editor(route) == editor
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        geometry(workflow.screen());
        stage = 90;
        capture.accept("form-root-close-narrow.png", () -> stage = 6);
      }
      case 6 -> {
        NativeClientProbe.resize(client, width[0], height[0]);
        NativeClientProbe.reflow(client);
        check.accept(
            "Escape keeps editing through native input",
            NativeClientProbe.key(workflow.screen(), "key.keyboard.escape", true));
        stage = 7;
      }
      case 7 -> {
        if (editor
            .form()
            .fieldControl(label)
            .filter(control -> widgets(workflow.screen()).contains(control))
            .isEmpty()) return false;
        check.accept(
            "Keep editing restores same draft and exact native selection",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && field().getValue().equals("Close draft")
                && field().getCursorPosition() == 4
                && field().getHighlighted().equals("los")
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        click(workflow.screen(), retiredDiscard);
        check.accept(
            "retired close callback cannot discard the retained owner",
            workflow.isOpen()
                && editor.isOpen()
                && workflow.editor(route) == editor
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Inspection(route))
                .completion()
                .toCompletableFuture();
        stage = 71;
      }
      case 71 -> {
        if (!navigation.isDone()) return false;
        check.accept(
            "close parent inspection is actually revealed",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        inspection = workflow.inspection().orElseThrow();
        if (inspection.reviewedInput().isEmpty()) return false;
        reviewed = inspection.reviewedInput().orElseThrow();
        press("Done");
        stage = 72;
      }
      case 72 -> {
        check.accept(
            "inspection close decision stays in original root without a source write",
            NativeScreens.current(client) == workflow.screen() && Arrays.equals(source, source()));
        press("Keep editing");
        stage = 73;
      }
      case 73 -> {
        check.accept(
            "Keep editing restores the same parent inspection capture and owner",
            workflow.inspection().orElseThrow() == inspection
                && inspection.reviewedInput().orElseThrow() == reviewed
                && workflow.editor(route) == editor
                && workflow
                    .screen()
                    .getNarrationMessage()
                    .getString()
                    .toLowerCase(Locale.ROOT)
                    .contains("inspection")
                && editor.view().snapshot().orElseThrow().version() == version
                && Arrays.equals(source, source()));
        press("Done");
        stage = 8;
      }
      case 8 -> {
        press("Discard draft");
        check.accept(
            "explicit discard closes actual owners without writing",
            !workflow.isOpen()
                && !editor.isOpen()
                && NativeScreens.current(client) == parent
                && Arrays.equals(source, source()));
        stage = 9;
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
