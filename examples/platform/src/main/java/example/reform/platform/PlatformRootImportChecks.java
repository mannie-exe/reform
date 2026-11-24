package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonParser;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.DocumentImports;
import design.inherent.reform.presentation.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual dropped file, complete import comparison, retained owner, and draft-only staging. */
final class PlatformRootImportChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final FieldPath label = FieldPath.ROOT.field("label"),
      distance = FieldPath.ROOT.field("distance");
  private final int[] width = new int[1], height = new int[1];
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private ClientImportInspection inspection;
  private DocumentImports.Preview reviewed;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private NavigationBookmark bookmark;
  private Path saved, file;
  private byte[] source;
  private String originalDistance;
  private int stage, afterPreview, previewWait;

  PlatformRootImportChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private void press(Button control) {
    var host = NativeScreens.current(client);
    check.accept("import action belongs to actual native focus tree", focus(host, control));
    click(host, control);
  }

  private EditBox field(FieldPath path) {
    return (EditBox) editor.form().fieldControl(path).orElseThrow();
  }

  private boolean mountedField(FieldPath path) {
    return editor
        .form()
        .fieldControl(path)
        .filter(control -> widgets(workflow.screen()).contains(control))
        .isPresent();
  }

  private boolean revealed() {
    if (!navigation.isDone()) return false;
    check.accept(
        "root import acknowledges its actual mounted destination: " + navigation.join(),
        navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  private void previewWhenMounted(int next) {
    afterPreview = next;
    previewWait = 0;
    stage = 30;
  }

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        saved =
            client
                .gameDirectory
                .toPath()
                .resolve("config/reform_platform_example/workflow_preferences.json");
        source = Files.readAllBytes(saved);
        var values =
            JsonParser.parseString(new String(source, java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("values");
        values.addProperty("enabled", !values.get("enabled").getAsBoolean());
        values.addProperty("distance", values.get("distance").getAsInt() == 7 ? 8 : 7);
        values.addProperty("label", "Root imported");
        file = client.gameDirectory.toPath().resolve("import-fixture/root-values.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, values.toString());
        var visual = List.of(label, FieldPath.ROOT.field("enabled"), distance);
        var presentation =
            FormPresentation.EMPTY
                .withCatalog(
                    new FormCatalog(
                        List.of(
                            new FormCatalog.Section(
                                label, "example.import-label", "Display name", ""),
                            new FormCatalog.Section(
                                visual.get(1), "example.import-enabled", "Feature enabled", ""),
                            new FormCatalog.Section(
                                distance, "example.import-distance", "Travel distance", ""))))
                .withLayout(
                    new FormLayout(
                        List.of(),
                        List.of(
                            new FormLayout.Group(
                                "import-order",
                                "example.import-order",
                                "Authored order",
                                "",
                                visual))));
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Import workspace"),
                List.of(
                    PlatformWorkflowExample.settings()
                        .form(Component.literal("Whole preferences"), presentation)));
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
        if (!revealed()) return false;
        editor = workflow.editor(route);
        if (editor.busy() || !mountedField(label)) return false;
        originalDistance = field(distance).getValue();
        replace(workflow.screen(), field(label), "Earlier draft");
        replace(workflow.screen(), field(distance), "bad");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy()) return false;
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Import(route))
                .completion()
                .toCompletableFuture();
        stage = 3;
      }
      case 3 -> {
        if (!revealed()) return false;
        var raw =
            (design.inherent.reform.edit.DraftNode.Scalar)
                editor.view().snapshot().orElseThrow().draft().at(distance);
        check.accept(
            "import accepts complete invalid actor input rather than treating it as pending native text: "
                + raw.raw(),
            raw.raw().equals("bad")
                && !editor.view().validation().valid()
                && workflow.importInspection().isPresent()
                && workflow.editor(route) == editor
                && Arrays.equals(source, Files.readAllBytes(saved)));
        workflow.screen().onFilesDrop(List.of(file));
        previewWhenMounted(33);
      }
      case 33 -> {
        var input = workflow.importInspection().orElseThrow().reviewedInput();
        if (input.isEmpty() || !button(workflow.screen(), "Preview import").active) return false;
        var compared = input.orElseThrow();
        check.accept(
            "invalid current draft remains diagnostic rather than an empty successful typed comparison",
            !compared.currentValidation().valid()
                && compared.importedValidation().valid()
                && !compared.comparisonAvailable()
                && compared.stageable()
                && Arrays.equals(source, Files.readAllBytes(saved)));
        press(button(workflow.screen(), "Stage draft"));
        stage = 34;
      }
      case 34 -> {
        if (editor.busy() || workflow.importInspection().isPresent() || !mountedField(label))
          return false;
        check.accept(
            "explicit import repairs the current invalid draft without a source write",
            field(label).getValue().equals("Root imported")
                && Arrays.equals(source, Files.readAllBytes(saved)));
        press(ownerAction(workflow.screen(), "Undo"));
        stage = 35;
      }
      case 35 -> {
        if (editor.busy() || !mountedField(distance)) return false;
        check.accept(
            "one import Undo restores the actual invalid value and valid sibling",
            field(distance).getValue().equals("bad")
                && field(label).getValue().equals("Earlier draft")
                && !editor.view().validation().valid()
                && Arrays.equals(source, Files.readAllBytes(saved)));
        replace(workflow.screen(), field(distance), originalDistance);
        stage = 4;
      }
      case 4 -> {
        if (editor.busy() || !editor.canStage(label)) return false;
        press(ownerAction(workflow.screen(), "Import…"));
        stage = 5;
      }
      case 5 -> {
        inspection = workflow.importInspection().orElse(null);
        if (inspection == null) return false;
        check.accept(
            "ordinary Import mounts in same root without a second actor or source write",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && workflow
                    .navigation()
                    .current()
                    .orElseThrow()
                    .equals(new ClientDestination.Import(route))
                && inspection.reviewedInput().isEmpty()
                && Arrays.equals(source, Files.readAllBytes(saved)));
        workflow.screen().onFilesDrop(List.of(file));
        check.accept(
            "explicit native file drop admits a source without an implicit preview",
            inspection.reviewedInput().isEmpty());
        previewWhenMounted(6);
      }
      case 6 -> {
        if (inspection.reviewedInput().isEmpty()
            || !button(workflow.screen(), "Preview import").active) return false;
        reviewed = inspection.reviewedInput().orElseThrow();
        check.accept(
            "root import retains actual value-format source and complete stageable comparison",
            reviewed.stageable()
                && reviewed.sourceSchemaVersion().isEmpty()
                && reviewed.format().id().toString().equals("reform:import/value_json")
                && reviewed.changes().size() == 3
                && reviewed.target().document().equals(route)
                && Arrays.equals(source, Files.readAllBytes(saved)));
        var labels =
            measuredText(workflow.screen()).stream()
                .filter(
                    text ->
                        Set.of("Display name", "Feature enabled", "Travel distance").contains(text))
                .toList();
        check.accept(
            "import comparison uses actual authored field captions and visual order",
            labels.equals(List.of("Display name", "Feature enabled", "Travel distance")));
        geometry(workflow.screen());
        NativeClientProbe.size(client, width, height);
        bookmark = workflow.navigation().bookmark();
        stage = 90;
        capture.accept("form-root-import-wide.png", () -> stage = 7);
      }
      case 7 -> {
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 8;
      }
      case 8 -> {
        if (workflow.screen().width > 340) return false;
        check.accept(
            "narrow import reflow retains actual source comparison and original owner",
            workflow.editor(route) == editor
                && inspection.reviewedInput().orElseThrow() == reviewed
                && Arrays.equals(source, Files.readAllBytes(saved)));
        geometry(workflow.screen());
        stage = 90;
        capture.accept("form-root-import-narrow.png", () -> stage = 9);
      }
      case 9 -> {
        NativeClientProbe.resize(client, width[0], height[0]);
        NativeClientProbe.reflow(client);
        press(button(workflow.screen(), "Back to settings"));
        navigation = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 10;
      }
      case 10 -> {
        if (!revealed()) return false;
        check.accept(
            "typed return restores identical import capture without another read",
            workflow.importInspection().orElseThrow() == inspection
                && inspection.reviewedInput().orElseThrow() == reviewed
                && Arrays.equals(source, Files.readAllBytes(saved)));
        press(button(workflow.screen(), "Back to settings"));
        stage = 101;
      }
      case 101 -> {
        if (!mountedField(label)) return false;
        replace(workflow.screen(), field(label), "Newer draft");
        stage = 11;
      }
      case 11 -> {
        if (editor.busy() || !editor.canStage(label)) return false;
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Import(route))
                .completion()
                .toCompletableFuture();
        stage = 12;
      }
      case 12 -> {
        if (!revealed()) return false;
        check.accept(
            "stale target keeps original comparison and refuses Stage without replacing newer draft",
            inspection.reviewedInput().orElseThrow() == reviewed
                && !button(workflow.screen(), "Stage draft").active
                && Arrays.equals(source, Files.readAllBytes(saved)));
        previewWhenMounted(13);
      }
      case 13 -> {
        var refreshed = inspection.reviewedInput();
        if (refreshed.isEmpty()
            || refreshed.orElseThrow() == reviewed
            || !button(workflow.screen(), "Stage draft").active) return false;
        check.accept(
            "explicit import refresh captures current target without saving",
            Arrays.equals(source, Files.readAllBytes(saved)));
        press(button(workflow.screen(), "Stage draft"));
        stage = 14;
      }
      case 14 -> {
        if (editor.busy() || workflow.importInspection().isPresent() || !mountedField(label))
          return false;
        check.accept(
            "Stage changes only original complete draft and returns to same editor",
            workflow.editor(route) == editor
                && editor.dirty()
                && field(label).getValue().equals("Root imported")
                && Arrays.equals(source, Files.readAllBytes(saved)));
        press(ownerAction(workflow.screen(), "Undo"));
        stage = 15;
      }
      case 15 -> {
        if (editor.busy() || !mountedField(label)) return false;
        check.accept(
            "one native Undo restores earlier work after import without saving",
            field(label).getValue().equals("Newer draft")
                && Arrays.equals(source, Files.readAllBytes(saved)));
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 16;
        return true;
      }
      case 30 -> {
        var actual = button(workflow.screen(), "Preview import");
        if (!actual.active) {
          if (++previewWait > 120)
            throw new AssertionError(
                "Import source did not become previewable: " + measuredText(workflow.screen()));
          return false;
        }
        press(actual);
        stage = afterPreview;
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
