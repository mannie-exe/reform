package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.ClientEditingWorkflow;
import design.inherent.reform.neoforge.client.ClientEditor;
import design.inherent.reform.neoforge.client.ClientWorkflowLayout;
import design.inherent.reform.neoforge.client.KeyBindingScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.client.UnsavedChangesScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import example.reform.ClientExample;
import example.reform.KeyExample;
import example.reform.NativeExample;
import example.reform.RegisteredExample;
import example.reform.WorkflowExample;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Real JSON, native CLIENT/COMMON and Options owners across retained generated views. */
final class NativeWorkflowControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final DocumentId preferences = NativeExample.DEFINITION.id();
  private ClientEditingWorkflow workflow, independent, shutdown;
  private ClientEditingWorkflow hierarchy;
  private int hierarchyStage;
  private SettingsScreen<?> hierarchyForm;
  private ClientEditor<?> json, keys, nativeClient, defaults, competing;
  private SettingsScreen<?> form;
  private CompletableFuture<?> waiting;
  private byte[] initialJson, initialOptions, initialNative, initialDefaults;
  private String initialDistance;
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private int guiScale;
  private int stage;
  private boolean complete;

  NativeWorkflowControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.add("checks", checks);
    report.addProperty("workflowClosed", workflow != null && !workflow.isOpen());
    report.addProperty("independentClosed", independent == null || !independent.isOpen());
    report.addProperty("shutdownClosed", shutdown != null && !shutdown.isOpen());
    return report;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private void press(Screen screen, String label) {
    click(screen, button(screen, label));
  }

  private void show(DocumentId document) {
    workflow.show(document);
    form = (SettingsScreen<?>) client.screen;
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Back to overview").active;
  }

  private void edit(String field, String value) {
    var path = FieldPath.ROOT.field(field);
    check("composed form can focus " + field, form.focusField(path));
    replace(form, (EditBox) form.fieldControl(path).orElseThrow(), value);
  }

  private String raw(ClientEditor<?> editor, String field) {
    return ((DraftNode.Scalar)
            editor.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private void back() {
    press(form, "Back to overview");
    check("native Back returns to the same retained overview", client.screen == workflow.screen());
  }

  private byte[] jsonBytes() throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_native_example/preferences.json"));
  }

  private byte[] optionsBytes() throws Exception {
    return Files.readAllBytes(client.options.getFile().toPath());
  }

  private boolean settled(ClientEditor<?> editor) {
    return !editor.busy() && editor.view().lastReceipt().isPresent() && !editor.view().dirty();
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (ClientExample.settings().runtime().isEmpty()
            || KeyExample.settings().definition().isEmpty()
            || RegisteredExample.client().definition().isEmpty()
            || RegisteredExample.common().definition().isEmpty()) return false;
        if (!reopen && !hierarchyControls()) return false;
        var forms = WorkflowExample.forms();
        for (var invalid :
            List.of(
                List.<design.inherent.reform.neoforge.client.ClientForm>of(),
                List.of(forms.getFirst(), forms.getFirst()),
                java.util.Collections.nCopies(33, forms.getFirst()))) {
          try {
            ClientEditingWorkflow.open(parent, Component.literal("Rejected workflow"), invalid);
            throw new AssertionError("Invalid workflow declaration admitted");
          } catch (IllegalArgumentException expected) {
            check("empty/duplicate/over-budget workflows refuse before opening sources", true);
          }
        }
        var metadata =
            java.util.stream.IntStream.range(0, ClientEditingWorkflow.MAXIMUM_DOCUMENTS)
                .mapToObj(
                    index ->
                        design.inherent.reform.neoforge.client.ClientForm.of(
                            DocumentId.parse("reform_native_example:unavailable_" + index),
                            Component.literal("Unavailable " + index),
                            () ->
                                new design.inherent.reform.neoforge.client.ClientForm.Availability(
                                    false, false, Component.literal("Absent optional source")),
                            ignored -> {
                              throw new AssertionError(
                                  "Metadata admission opened an absent source");
                            },
                            java.util.Optional.empty()))
                .toList();
        try (var maximum =
            ClientEditingWorkflow.open(parent, Component.literal("Bounded metadata"), metadata)) {
          check(
              "complete workflow metadata bound preserves all unavailable entries without opening sources",
              maximum.documents().size() == ClientEditingWorkflow.MAXIMUM_DOCUMENTS
                  && maximum.documents().stream()
                      .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        }
        initialJson = jsonBytes();
        initialOptions = optionsBytes();
        initialNative = Files.readAllBytes(RegisteredExample.clientConfig().getFullPath());
        initialDefaults = Files.readAllBytes(RegisteredExample.commonConfig().getFullPath());
        workflow = WorkflowExample.open(parent);
        check(
            "declaration opens no hidden editor",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened));
        check(
            "composed declaration keeps the actual native provider singular",
            KeyExample.selections() == 1);
        client.setScreen(workflow.screen());
        var search = input(workflow.screen(), "Search documents");
        replace(workflow.screen(), search, "Controls");
        check(
            "search retains native keyboard focus across every typed rebuild",
            input(workflow.screen(), "Search documents").getValue().equals("Controls"));
        check(
            "search exposes only the matching actual document",
            widgets(workflow.screen()).stream()
                    .filter(
                        widget ->
                            widget instanceof Button
                                && widget.getMessage().getString().equals("Open"))
                    .count()
                == 1);
        for (int remaining = 8;
            // Native typing above checked filtering; clear it through actual Backspace events.
            remaining > 0 && !input(workflow.screen(), "Search documents").getValue().isEmpty();
            remaining--) {
          var current = input(workflow.screen(), "Search documents");
          check("clearing search keeps native focus", focus(workflow.screen(), current));
          current.moveCursorToEnd(false);
          current.setHighlightPos(current.getValue().length());
          check(
              "native search backspace is consumed",
              workflow.screen().keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0));
        }
        check(
            "repeated row actions narrate their actual document",
            NativeStructuredControls.narration(button(workflow.screen(), "Open"))
                .contains("Client preferences"));
        check(
            "cleared search restores every declared document",
            input(workflow.screen(), "Search documents").getValue().isEmpty()
                && widgets(workflow.screen()).stream()
                        .filter(
                            widget ->
                                widget instanceof Button
                                    && widget.getMessage().getString().equals("Open"))
                        .count()
                    == 4);
        geometry(workflow.screen());
        next("composed ordinary owners enter native overview");
        capture.accept("form-workflow-wide.png", () -> show(preferences));
      }
      case 1 -> {
        if (!ready()) return false;
        json = workflow.editor(preferences);
        initialDistance = raw(json, "distance");
        check("workflow reuses the displayed actor", json.screen() == form);
        if (reopen) {
          check("fresh JVM observes only completed JSON save", raw(json, "distance").equals("256"));
          check(
              "fresh JVM observes native Controls save",
              KeyExample.PRIMARY.getKey().getName().equals("key.keyboard.4"));
          check("fresh JVM observes native COMMON save", RegisteredExample.defaults() == 10);
          check("discarded native CLIENT draft was never saved", RegisteredExample.live() == 4);
          back();
          press(workflow.screen(), "Done");
          shutdown = WorkflowExample.open(parent);
          complete = true;
          return true;
        }
        edit("distance", "broken");
        next("invalid JSON draft is retained");
      }
      case 2 -> {
        if (!ready() || form.editorView().validation().valid()) return false;
        check("invalid text remains actual actor data", raw(json, "distance").equals("broken"));
        back();
        check(
            "overview identifies invalid dirty document",
            workflow.documents().stream()
                .filter(state -> state.document().equals(preferences))
                .allMatch(ClientEditingWorkflow.DocumentState::dirty));
        check("navigation does not persist invalid JSON", Arrays.equals(initialJson, jsonBytes()));
        show(KeyExample.DOCUMENT);
        next("native key view shares workflow lifetime");
      }
      case 3 -> {
        if (!ready()) return false;
        keys = workflow.editor(KeyExample.DOCUMENT);
        var path = FieldPath.ROOT.field(KeyExample.PRIMARY.getName());
        check("composed native Controls editor is reachable", form.focusField(path));
        click(form, (Button) form.fieldControl(path).orElseThrow());
        var popup = (KeyBindingScreen) client.screen;
        click(popup, (Button) popup.control(KeyBindingScreen.Control.CAPTURE).orElseThrow());
        popup.keyPressed(GLFW.GLFW_KEY_4, 0, 0);
        popup.keyReleased(GLFW.GLFW_KEY_4, 0, 0);
        click(popup, (Button) popup.control(KeyBindingScreen.Control.APPLY).orElseThrow());
        next("composed native key popup returns to retained actor");
      }
      case 4 -> {
        if (!ready() || !keys.view().dirty()) return false;
        check(
            "key draft leaves actual Options untouched",
            Arrays.equals(initialOptions, optionsBytes()));
        back();
        show(RegisteredExample.client().document());
        next("native client TOML shares workflow");
      }
      case 5 -> {
        if (!ready()) return false;
        nativeClient = workflow.editor(RegisteredExample.client().document());
        edit("live", "14");
        next("native CLIENT draft stages independently");
      }
      case 6 -> {
        if (!ready() || !raw(nativeClient, "live").equals("14")) return false;
        back();
        show(RegisteredExample.common().document());
        next("native COMMON defaults retain separate owner");
      }
      case 7 -> {
        if (!ready()) return false;
        defaults = workflow.editor(RegisteredExample.common().document());
        edit("level", "10");
        next("native COMMON draft stages independently");
      }
      case 8 -> {
        if (!ready() || !raw(defaults, "level").equals("10")) return false;
        back();
        check(
            "four owners retain four independent drafts",
            workflow.documents().stream().filter(ClientEditingWorkflow.DocumentState::dirty).count()
                == 4);
        show(preferences);
        check(
            "returning uses identical form and actual actor",
            form == json.screen() && workflow.editor(preferences) == json);
        check(
            "invalid input survives owner navigation",
            ((EditBox) form.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("broken"));
        press(form, "Undo");
        next("retained undo history repairs invalid input");
      }
      case 9 -> {
        if (!ready() || !raw(json, "distance").equals(initialDistance)) return false;
        check("undo preserves redo across views", json.view().canRedo());
        edit("distance", "128");
        next("JSON valid draft can save separately");
      }
      case 10 -> {
        if (!ready() || !raw(json, "distance").equals("128")) return false;
        press(form, "Save and apply");
        back();
        check("entered save remains pending across Back", json.busy());
        workflow.screen().onClose();
        check(
            "normal workflow exit waits for actual pending save",
            client.screen == workflow.screen());
        try {
          workflow.discard(preferences);
          throw new AssertionError("Pending editor was discarded");
        } catch (PolicyRefusal refused) {
          check(
              "per-document discard refuses the specific pending operation",
              refused.report().issues().stream()
                  .anyMatch(issue -> issue.code().equals("client.editor_busy")));
        }
        next("one owner save completes while overview remains active");
      }
      case 11 -> {
        if (!settled(json)) return false;
        check(
            "JSON receipt records actual save",
            json.view().lastReceipt().orElseThrow().persistence()
                == CommitReceipt.Persistence.SAVED);
        check(
            "other three drafts remain unsaved",
            workflow.documents().stream().filter(ClientEditingWorkflow.DocumentState::dirty).count()
                == 3);
        check(
            "JSON save did not write Options or native TOML",
            Arrays.equals(initialOptions, optionsBytes())
                && Arrays.equals(
                    initialNative,
                    Files.readAllBytes(RegisteredExample.clientConfig().getFullPath()))
                && Arrays.equals(
                    initialDefaults,
                    Files.readAllBytes(RegisteredExample.commonConfig().getFullPath())));
        show(KeyExample.DOCUMENT);
        press(form, "Save and apply");
        back();
        next("retained key save uses actual Options writer");
      }
      case 12 -> {
        if (!settled(keys)) return false;
        check(
            "key binding is actually applied",
            KeyExample.PRIMARY.getKey().getName().equals("key.keyboard.4"));
        show(RegisteredExample.common().document());
        press(form, "Save native settings");
        back();
        next("retained native COMMON save uses native callbacks");
      }
      case 13 -> {
        if (!settled(defaults)) return false;
        check("native default value is actually saved", RegisteredExample.defaults() == 10);
        workflow.discard(RegisteredExample.client().document());
        check(
            "discard does not write native CLIENT draft",
            Arrays.equals(
                initialNative, Files.readAllBytes(RegisteredExample.clientConfig().getFullPath())));
        check(
            "discard retires only selected actor",
            !nativeClient.isOpen() && json.isOpen() && keys.isOpen() && defaults.isOpen());
        show(preferences);
        edit("distance", "192");
        next("independent workflow will compete with retained draft");
      }
      case 14 -> {
        if (!ready() || !raw(json, "distance").equals("192")) return false;
        back();
        independent = WorkflowExample.open(parent);
        competing = independent.editor(preferences);
        check("independent workflow has a different actual actor", competing != json);
        waiting =
            DocumentActions.result(competing.actions().ensureLoaded(EntryPoint.GUI))
                .toCompletableFuture();
        check(
            "custom-view source operation is still pending on its actual native owner",
            !waiting.isDone());
        check("custom-view operation contributes to retained editor busy state", competing.busy());
        next("independent actor loads same actual JSON source");
      }
      case 15 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        waiting =
            DocumentActions.result(
                    competing
                        .actions()
                        .edit(
                            EntryPoint.GUI,
                            new DraftEdit(
                                competing
                                    .view()
                                    .snapshot()
                                    .orElseThrow()
                                    .target(FieldPath.ROOT.field("distance")),
                                new DraftEdit.SetRaw("256"))))
                .toCompletableFuture();
        next("independent actor edits its own draft");
      }
      case 16 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        waiting =
            DocumentActions.result(competing.actions().commit(EntryPoint.GUI))
                .toCompletableFuture();
        next("independent actor saves competing actual source");
      }
      case 17 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        check(
            "independent actor actually saved its own candidate",
            competing.view().lastReceipt().orElseThrow().persistence()
                    == CommitReceipt.Persistence.SAVED
                && ClientExample.settings()
                        .runtime()
                        .orElseThrow()
                        .view()
                        .value()
                        .orElseThrow()
                        .distance()
                    == 256);
        independent.close();
        check(
            "closing another workflow leaves original actors alive",
            json.isOpen() && keys.isOpen());
        show(preferences);
        press(form, "Save and apply");
        next("original actor retains native source conflict checks");
      }
      case 18 -> {
        if (!ready() || json.view().operationReport().valid()) return false;
        check(
            "managed-owner competing save returns its specific revision refusal",
            json.view().operationReport().issues().stream()
                .anyMatch(issue -> issue.code().equals("owner.revision")));
        check(
            "source conflict preserves original unsaved draft",
            raw(json, "distance").equals("192"));
        back();
        check(
            "overview distinguishes prior receipt from current revision refusal",
            workflow.documents().stream()
                .filter(state -> state.document().equals(preferences))
                .findFirst()
                .orElseThrow()
                .summary()
                .getString()
                .contains("Recorded receipt:"));
        GLFW.glfwGetWindowSize(client.getWindow().getWindow(), windowWidth, windowHeight);
        guiScale = client.options.guiScale().get();
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        next("native compact overview resize requested");
      }
      case 19 -> {
        if (workflow.screen().width > 340) return false;
        geometry(workflow.screen());
        check(
            "compact composed overview remains narratable",
            workflow.screen().getNarrationMessage().getString().contains("separately"));
        next("compact overview retains separate receipt and refusal feedback");
        capture.accept(
            "form-workflow-narrow.png",
            () -> {
              client.options.guiScale().set(guiScale);
              GLFW.glfwSetWindowSize(
                  client.getWindow().getWindow(), windowWidth[0], windowHeight[0]);
              client.resizeDisplay();
              press(workflow.screen(), "Done");
            });
      }
      case 20 -> {
        check(
            "dirty workflow exit requests an explicit decision",
            client.screen instanceof UnsavedChangesScreen);
        var decision = (UnsavedChangesScreen) client.screen;
        waiting = DocumentActions.result(keys.actions().load(EntryPoint.GUI)).toCompletableFuture();
        check(
            "public native source load remains active during a close decision",
            !waiting.isDone() && keys.busy());
        press(decision, "Discard draft");
        check(
            "workflow rechecks pending operations at the actual discard decision",
            workflow.isOpen() && client.screen == workflow.screen() && json.isOpen());
        next("late custom-view operation invalidates earlier discard decision");
      }
      case 21 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        press(workflow.screen(), "Done");
        var decision = (UnsavedChangesScreen) client.screen;
        press(decision, "Keep editing");
        check(
            "keep editing preserves same overview and actor",
            client.screen == workflow.screen() && json.isOpen());
        press(workflow.screen(), "Done");
        press(client.screen, "Discard draft");
        check(
            "normal discard closes all workflow actors",
            !workflow.isOpen() && !json.isOpen() && !keys.isOpen() && !defaults.isOpen());
        check("normal close returns to original parent", client.screen == parent);
        try {
          workflow.editor(preferences);
          throw new AssertionError("Closed workflow reopened a hidden actor");
        } catch (PolicyRefusal expected) {
          check("closed workflow refuses reuse", true);
        }
        independent = WorkflowExample.open(parent);
        client.setScreen(independent.screen());
        var stale = independent.editor(preferences);
        stale.actions().close();
        try {
          independent.editor(preferences);
          throw new AssertionError("Closed actual editor was silently replaced");
        } catch (PolicyRefusal refused) {
          check(
              "retired editor requires explicit discard before replacement",
              refused.report().issues().stream()
                  .anyMatch(issue -> issue.code().equals("client.editor_unavailable")));
        }
        independent.discard(preferences);
        competing = independent.editor(preferences);
        check("explicit discard permits a fresh actor", competing != stale && competing.isOpen());
        independent.show(KeyExample.DOCUMENT);
        form = (SettingsScreen<?>) client.screen;
        next("forced native navigation will release the entire workflow");
      }
      case 22 -> {
        if (!ready()) return false;
        press(form, "Back to overview");
        replace(
            independent.screen(), input(independent.screen(), "Search documents"), "preferences");
        press(independent.screen(), "Discard draft");
        waiting =
            DocumentActions.result(competing.actions().load(EntryPoint.GUI)).toCompletableFuture();
        check(
            "per-document decision can observe a newly queued source operation",
            !waiting.isDone() && competing.busy());
        press(client.screen, "Discard draft");
        check(
            "late per-document discard reports refusal and retains actual actor",
            competing.isOpen() && client.screen == independent.screen());
        next("per-document decision rechecks live pending work without throwing");
      }
      case 23 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        var auxiliary = independent.editor(KeyExample.DOCUMENT);
        independent.show(KeyExample.DOCUMENT);
        client.setScreen(parent);
        check(
            "unexpected child removal closes all workflow editors",
            !independent.isOpen() && !auxiliary.isOpen() && !competing.isOpen());
        shutdown = WorkflowExample.open(parent);
        shutdown.editor(preferences);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown composed workflow stage " + stage);
    }
    return false;
  }

  private DocumentId hierarchyId(String path) {
    return DocumentId.parse("reform_native_example:hierarchy/" + path);
  }

  private CycleButton<?> workflowView() {
    return widgets(hierarchy.screen()).stream()
        .filter(CycleButton.class::isInstance)
        .map(widget -> (CycleButton<?>) widget)
        .findFirst()
        .orElseThrow();
  }

  private boolean hierarchyControls() {
    switch (hierarchyStage) {
      case 0 -> {
        var form = ClientExample.settings().form(Component.literal("Shared preferences"));
        var path =
            List.of(
                new ClientWorkflowLayout.Category(
                    hierarchyId("interface"), Component.literal("Interface"), Component.empty()),
                new ClientWorkflowLayout.Category(
                    hierarchyId("map"), Component.literal("Map module"), Component.empty()),
                new ClientWorkflowLayout.Category(
                    hierarchyId("planning"), Component.literal("Map planning"), Component.empty()));
        var rows = new ArrayList<ClientWorkflowLayout.Placement>();
        rows.add(
            new ClientWorkflowLayout.Placement(
                hierarchyId("distance"),
                form.route(),
                Component.literal("Map distance"),
                Optional.of(FieldPath.ROOT.field("distance")),
                Component.literal("The far horizon planning threshold"),
                Set.of(ApplicationEffects.Timing.GAME_RESTART)));
        for (int i = 0; i < 130; i++)
          rows.add(
              new ClientWorkflowLayout.Placement(
                  hierarchyId("label/" + i),
                  form.route(),
                  Component.literal("Map label " + i),
                  Optional.of(FieldPath.ROOT.field("label"))));
        hierarchy =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Hierarchical workflow"),
                List.of(form),
                new ClientWorkflowLayout(
                    List.of(new ClientWorkflowLayout.Group(path, rows, List.of())), List.of()));
        client.setScreen(hierarchy.screen());
        for (int i = 0; i < 3; i++) press(hierarchy.screen(), "Open");
        check(
            "native three-level workflow reached a large shared-owner placement list",
            widgets(hierarchy.screen()).stream()
                        .filter(
                            w -> w instanceof Button && w.getMessage().getString().equals("Open"))
                        .count()
                    == 131
                && hierarchy.documents().stream()
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened));
        replace(
            hierarchy.screen(), input(hierarchy.screen(), "Search documents"), "horizon threshold");
        check(
            "description search across groups stays scoped to the actual matching placement",
            widgets(hierarchy.screen()).stream()
                    .filter(w -> w instanceof Button && w.getMessage().getString().equals("Open"))
                    .count()
                == 1);
        press(hierarchy.screen(), "Open");
        hierarchyForm = (SettingsScreen<?>) client.screen;
        hierarchyStage++;
      }
      case 1 -> {
        if (hierarchyForm.editorView().availability() != EditorActor.Availability.READY
            || !button(hierarchyForm, "Back to overview").active) return false;
        var control =
            (EditBox) hierarchyForm.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow();
        check("selected catalog placement reveals its existing field", control.isFocused());
        replace(hierarchyForm, control, "bad");
        hierarchyStage++;
      }
      case 2 -> {
        if (hierarchyForm.editorView().validation().valid()
            || !button(hierarchyForm, "Back to overview").active) return false;
        press(hierarchyForm, "Back to overview");
        var root = hierarchy.screen();
        check(
            "overview retains its earlier query when returning from a field",
            input(root, "Search documents").getValue().equals("horizon threshold"));
        input(root, "Search documents").setValue("");
        check(
            "changing the query clears the exact active native input",
            input(root, "Search documents").getValue().isEmpty());
        for (String expected : List.of("MODIFIED", "INVALID", "RESTART")) {
          workflowView().onPress();
          check(
              "expected " + expected + " but observed " + workflowView().getValue(),
              workflowView().getValue().toString().equals(expected));
          check(
              "modified, invalid and restart filters each show only the relevant cached placement",
              widgets(root).stream()
                      .filter(
                          w -> w instanceof Button && w.getMessage().getString().equals("Resume"))
                      .count()
                  == 1);
        }
        workflowView().onPress();
        var resumed =
            widgets(root).stream()
                .filter(w -> w instanceof Button && w.getMessage().getString().equals("Resume"))
                .count();
        var retained = hierarchy.documents().getFirst().dirty();
        check(
            "clearing filters returns to the selected category and keeps the original draft ("
                + workflowView().getValue()
                + ", resumed="
                + resumed
                + ", dirty="
                + retained
                + ")",
            workflowView().getValue().toString().equals("ALL") && resumed == 131 && retained);
        hierarchy.close();
        client.setScreen(parent);
        hierarchyStage++;
        return true;
      }
      default -> throw new IllegalStateException("Hierarchy controls already completed");
    }
    return false;
  }
}
