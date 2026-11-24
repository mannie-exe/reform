package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonParser;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual external source, retained manual choices, and draft-only resolution inside one root. */
final class PlatformRootConflictChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final FieldPath label = FieldPath.ROOT.field("label");
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private ClientConflictInspection conflict;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private UUID comparison;
  private Path path;
  private byte[] original, external;
  private int stage;

  PlatformRootConflictChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private void press(Button button) {
    var host = NativeScreens.current(client);
    check.accept(
        "root conflict action belongs to its actual native focus tree", focus(host, button));
    click(host, button);
  }

  private void choose(FieldPath path) {
    for (int attempt = 0; attempt < 4; attempt++) {
      var control = (CycleButton<?>) conflict.choiceControl(path).orElseThrow();
      if (control.getMessage().getString().contains("Your draft")) return;
      check.accept(
          "root conflict choice is an actual native control", focus(workflow.screen(), control));
      click(workflow.screen(), control);
    }
    throw new AssertionError("Native conflict choice did not reach Your draft: " + path);
  }

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        path =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/workflow_preferences.json");
        original = Files.readAllBytes(path);
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Conflict workspace"),
                List.of(
                    PlatformWorkflowExample.settings()
                        .form(Component.literal("Retained preferences"))));
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
            "root conflict owner is acknowledged only after mounting",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(route);
        if (editor.busy() || editor.form().fieldControl(label).isEmpty()) return false;
        replace(
            workflow.screen(),
            (EditBox) editor.form().fieldControl(label).orElseThrow(),
            "Conflict draft");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        var json =
            JsonParser.parseString(new String(original, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject();
        json.getAsJsonObject("values").addProperty("label", "External replacement");
        json.getAsJsonObject("values").addProperty("distance", 256);
        external = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, external);
        press(ownerAction(workflow.screen(), "Reload saved"));
        stage = 3;
      }
      case 3 -> {
        if (editor.busy() || editor.view().review().isEmpty()) return false;
        if (workflow.conflictInspection().isEmpty()) {
          var review = button(workflow.screen(), "Review conflict…");
          if (!review.active) return false;
          press(review);
        }
        stage = 4;
      }
      case 4 -> {
        conflict = workflow.conflictInspection().orElse(null);
        if (conflict == null || conflict.reviewedInput().isEmpty()) return false;
        comparison = conflict.reviewedInput().orElseThrow().id();
        check.accept(
            "actual source comparison mounts inside the same root and actor",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && workflow
                    .navigation()
                    .current()
                    .orElseThrow()
                    .equals(new ClientDestination.Conflict(route))
                && Arrays.equals(external, Files.readAllBytes(path)));
        check.accept(
            "root conflict requires an explicit choice for every actual field",
            conflict.reviewedInput().orElseThrow().entries().size() == 3
                && !button(workflow.screen(), "Resolve draft").active);
        choose(label);
        check.accept(
            "one native choice neither resolves other fields nor writes the source",
            !button(workflow.screen(), "Resolve draft").active
                && Arrays.equals(external, Files.readAllBytes(path)));
        geometry(workflow.screen());
        stage = 40;
        capture.accept("form-root-conflict.png", () -> stage = 5);
      }
      case 5 -> {
        var back =
            widgets(workflow.screen()).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(widget -> widget.getMessage().getString().equals("Back"))
                .max(Comparator.comparingInt(Button::getY))
                .orElseThrow();
        press(back);
        stage = 6;
      }
      case 6 -> {
        if (workflow.conflictInspection().isPresent()) return false;
        check.accept(
            "leaving conflict review preserves draft, comparison and source",
            workflow.editor(route) == editor
                && editor.dirty()
                && editor.view().review().isPresent()
                && Arrays.equals(external, Files.readAllBytes(path)));
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Conflict(route))
                .completion()
                .toCompletableFuture();
        stage = 7;
      }
      case 7 -> {
        if (!navigation.isDone()) return false;
        check.accept(
            "returning to conflict restores the same comparison and chosen native value",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED
                && workflow.conflictInspection().orElseThrow() == conflict
                && conflict.reviewedInput().orElseThrow().id().equals(comparison)
                && conflict
                    .choiceControl(label)
                    .orElseThrow()
                    .getMessage()
                    .getString()
                    .contains("Your draft"));
        for (var entry : conflict.reviewedInput().orElseThrow().entries()) choose(entry.path());
        var resolve = button(workflow.screen(), "Resolve draft");
        if (!resolve.active) return false;
        press(resolve);
        stage = 8;
      }
      case 8 -> {
        if (editor.busy()
            || workflow.conflictInspection().isPresent()
            || editor.form().fieldControl(label).isEmpty()) return false;
        check.accept(
            "resolution changes only the original draft and returns to that editor",
            workflow.editor(route) == editor
                && editor.dirty()
                && editor.view().review().isEmpty()
                && ((EditBox) editor.form().fieldControl(label).orElseThrow())
                    .getValue()
                    .equals("Conflict draft")
                && Arrays.equals(external, Files.readAllBytes(path)));
        check.accept(
            "resolved comparison cannot expose old native choice controls",
            conflict.reviewedInput().isEmpty() && conflict.choiceControl(label).isEmpty());
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Conflict(route))
                .completion()
                .toCompletableFuture();
        stage = 9;
      }
      case 9 -> {
        if (!navigation.isDone()) return false;
        check.accept(
            "a resolved conflict link is missing rather than another resolution or save",
            navigation.join().outcome() == NavigationAttempt.Outcome.MISSING
                && Arrays.equals(external, Files.readAllBytes(path)));
        workflow.discard(route);
        workflow.close();
        NativeScreens.open(client, parent);
        Files.write(path, original);
        check.accept(
            "disposable external source is restored exactly after closing all owned views",
            !workflow.isOpen() && Arrays.equals(original, Files.readAllBytes(path)));
        stage = 10;
        return true;
      }
      case 10 -> {
        return true;
      }
      case 40 -> {}
      default -> throw new AssertionError("Unknown root conflict stage " + stage);
    }
    return false;
  }
}
