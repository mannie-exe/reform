package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientEditingWorkflow;
import design.inherent.reform.minecraft.client.ClientEditor;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.ClientWorkflowLayout;
import design.inherent.reform.minecraft.client.KeyBindingScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsForm;
import design.inherent.reform.minecraft.client.UnsavedChangesScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
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

/** Real JSON, client-source/COMMON and Options owners across retained generated views. */
final class PlatformWorkflowChecks implements PlatformFormProbe.Scene {
  private static PlatformWorkflowChecks active;

  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "WORKFLOW",
        driver -> {
          active = new PlatformWorkflowChecks(reopen, driver::progress, driver::capture);
          return active;
        });
  }

  static JsonObject stopped(JsonObject prior) {
    var report = active.report();
    report.add("captures", prior.get("captures"));
    return report;
  }

  public String diagnostic() {
    return "workflow stage="
        + stage
        + ", hierarchy="
        + hierarchyStage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName();
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private JsonObject cachedSearchCost;
  private final DocumentId preferences = PlatformWorkflowExample.DEFINITION.id();
  private ClientEditingWorkflow workflow, independent, shutdown;
  private ClientEditingWorkflow hierarchy;
  private int hierarchyStage;
  private SettingsForm<?> hierarchyForm;
  private ClientEditor<?> json, keys, nativeClient, defaults, competing;
  private SettingsForm<?> form;
  private ClientEditor<?> displayedEditor;
  private Screen host;
  private CompletableFuture<?> waiting;
  private byte[] initialJson, initialOptions, initialNative, initialDefaults;
  private String initialDistance;
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private int guiScale;
  private int stage;
  private boolean complete;

  PlatformWorkflowChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  public JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.add("checks", checks);
    report.addProperty("sourceKind", PlatformWorkflowSources.sourceKind());
    report.add("sourceEvidence", PlatformWorkflowSources.sourceReport());
    if (cachedSearchCost != null) report.add("cachedSearchCost", cachedSearchCost);
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
    if (label.equals("Settings")) {
      returnToSettings(screen);
      return;
    }
    var target = ownerAction(screen, label);
    var host = actionHost(screen);
    focus(host, target);
    click(host, target);
  }

  private Button navigation(Screen screen, String label) {
    return widgets(screen).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(
            widget ->
                widget.getMessage().getString().strip().replaceFirst("^[+−] ", "").equals(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing native navigation: " + label));
  }

  private void show(DocumentId document) {
    show(workflow, document);
  }

  private void show(ClientEditingWorkflow context, DocumentId document) {
    context.show(document);
    host = context.screen();
    displayedEditor = context.editor(document);
    form = displayedEditor.form();
    check(
        "document navigation retains the shared native screen",
        NativeScreens.current(client) == host);
  }

  private boolean ready() {
    return NativeScreens.current(client) == host
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && !displayedEditor.busy()
        && button(host, "Done").active;
  }

  private void edit(String field, String value) {
    var path = FieldPath.ROOT.field(field);
    check("composed form can focus " + field, form.focusField(path));
    replace(host, (EditBox) form.fieldControl(path).orElseThrow(), value);
  }

  private String raw(ClientEditor<?> editor, String field) {
    return ((DraftNode.Scalar)
            editor.view().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private void retained() {
    check(
        "owner operations retain the same native workspace and actual form",
        NativeScreens.current(client) == workflow.screen() && form == displayedEditor.form());
  }

  private byte[] jsonBytes() throws Exception {
    return Files.readAllBytes(
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json"));
  }

  private byte[] optionsBytes() throws Exception {
    return Files.readAllBytes(client.options.getFile().toPath());
  }

  private boolean settled(ClientEditor<?> editor) {
    return !editor.busy() && editor.view().lastReceipt().isPresent() && !editor.view().dirty();
  }

  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (PlatformWorkflowExample.settings().runtime().isEmpty()
            || PlatformWorkflowExample.keys().definition().isEmpty()
            || !PlatformWorkflowSources.ready()) return false;
        if (!reopen && !hierarchyControls()) return false;
        var forms = PlatformWorkflowExample.forms();
        for (var invalid :
            List.of(
                List.<design.inherent.reform.minecraft.client.ClientForm>of(),
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
                        design.inherent.reform.minecraft.client.ClientForm.of(
                            DocumentId.parse("reform_platform_example:unavailable_" + index),
                            Component.literal("Unavailable " + index),
                            () ->
                                new design.inherent.reform.minecraft.client.ClientForm.Availability(
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
        initialNative = Files.readAllBytes(PlatformWorkflowSources.clientPath());
        initialDefaults = Files.readAllBytes(PlatformWorkflowSources.defaultsPath());
        workflow = PlatformWorkflowExample.open(parent);
        check(
            "declaration opens no hidden editor",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::opened));
        check(
            "composed declaration keeps the actual native provider singular",
            PlatformWorkflowExample.selections() == 1);
        NativeScreens.open(client, workflow.screen());
        var search = input(workflow.screen(), "Search settings");
        replace(workflow.screen(), search, "Controls");
        check(
            "search retains native keyboard focus across every typed rebuild",
            input(workflow.screen(), "Search settings").getValue().equals("Controls"));
        check(
            "search exposes the matching native document without opening its source",
            navigation(workflow.screen(), "Native Controls") != null
                && workflow.documents().stream()
                    .filter(state -> state.document().equals(PlatformWorkflowExample.KEYS))
                    .noneMatch(ClientEditingWorkflow.DocumentState::opened)
                && widgets(workflow.screen()).stream()
                    .noneMatch(
                        widget ->
                            widget instanceof Button
                                && widget
                                    .getMessage()
                                    .getString()
                                    .strip()
                                    .equals("Client preferences")));
        for (int remaining = 8;
            // Native typing above checked filtering; clear it through actual Backspace events.
            remaining > 0 && !input(workflow.screen(), "Search settings").getValue().isEmpty();
            remaining--) {
          var current = input(workflow.screen(), "Search settings");
          check("clearing search keeps native focus", focus(workflow.screen(), current));
          current.moveCursorToEnd(false);
          current.setHighlightPos(current.getValue().length());
          check(
              "native search backspace is consumed",
              NativeClientProbe.key(workflow.screen(), "key.keyboard.backspace", true));
        }
        check(
            "repeated row actions narrate their actual document",
            NativeNarrationProbe.read(navigation(workflow.screen(), "Client preferences"))
                .contains("Client preferences"));
        check(
            "cleared search restores every declared document without opening other sources",
            input(workflow.screen(), "Search settings").getValue().isEmpty()
                && forms.stream()
                    .allMatch(
                        declared ->
                            navigation(workflow.screen(), declared.title().getString()) != null)
                && workflow.documents().stream()
                        .filter(ClientEditingWorkflow.DocumentState::opened)
                        .count()
                    == 1);
        geometry(workflow.screen());
        next("composed ordinary owners enter native overview");
        capture.accept("form-workflow-wide.png", () -> show(preferences));
      }
      case 1 -> {
        if (!ready()) return false;
        json = workflow.editor(preferences);
        initialDistance = raw(json, "distance");
        check(
            "workflow reuses the displayed actor",
            json.form() == form && json.screen() == workflow.screen());
        if (reopen) {
          check("fresh JVM observes only completed JSON save", raw(json, "distance").equals("256"));
          check(
              "fresh JVM observes native Controls save",
              PlatformWorkflowExample.PRIMARY.saveString().equals("key.keyboard.4"));
          check(
              "fresh JVM observes installation defaults save",
              PlatformWorkflowSources.level() == 10);
          check(
              "discarded client-source draft was never saved", PlatformWorkflowSources.live() == 4);
          retained();
          press(workflow.screen(), "Done");
          shutdown = PlatformWorkflowExample.open(parent);
          complete = true;
          return true;
        }
        edit("distance", "broken");
        next("invalid JSON draft is retained");
      }
      case 2 -> {
        if (!ready() || form.editorView().validation().valid()) return false;
        check("invalid text remains actual actor data", raw(json, "distance").equals("broken"));
        retained();
        check(
            "overview identifies invalid dirty document",
            workflow.documents().stream()
                .filter(state -> state.document().equals(preferences))
                .allMatch(ClientEditingWorkflow.DocumentState::dirty));
        check("navigation does not persist invalid JSON", Arrays.equals(initialJson, jsonBytes()));
        show(PlatformWorkflowExample.KEYS);
        next("native key view shares workflow lifetime");
      }
      case 3 -> {
        if (!ready()) return false;
        keys = workflow.editor(PlatformWorkflowExample.KEYS);
        var path = FieldPath.ROOT.field(PlatformWorkflowExample.PRIMARY.getName());
        check("composed native Controls editor is reachable", form.focusField(path));
        click(host, (Button) form.fieldControl(path).orElseThrow());
        var popup = (KeyBindingScreen) NativeScreens.current(client);
        click(popup, (Button) popup.control(KeyBindingScreen.Control.CAPTURE).orElseThrow());
        NativeClientProbe.key(popup, "key.keyboard.4", true);
        NativeClientProbe.key(popup, "key.keyboard.4", false);
        click(popup, (Button) popup.control(KeyBindingScreen.Control.APPLY).orElseThrow());
        next("composed native key popup returns to retained actor");
      }
      case 4 -> {
        if (!ready() || !keys.view().dirty()) return false;
        check(
            "key draft leaves actual Options untouched",
            Arrays.equals(initialOptions, optionsBytes()));
        retained();
        show(PlatformWorkflowSources.client().document());
        next("client-owned source shares workflow");
      }
      case 5 -> {
        if (!ready()) return false;
        nativeClient = workflow.editor(PlatformWorkflowSources.client().document());
        edit("live", "14");
        next("client-source draft stages independently");
      }
      case 6 -> {
        if (!ready() || !raw(nativeClient, "live").equals("14")) return false;
        retained();
        show(PlatformWorkflowSources.defaults().document());
        next("installation defaults defaults retain separate owner");
      }
      case 7 -> {
        if (!ready()) return false;
        defaults = workflow.editor(PlatformWorkflowSources.defaults().document());
        var defaultsContext = host.getNarrationMessage().getString();
        System.out.println(
            "REFORM_WORKFLOW_DEFAULTS "
                + PlatformWorkflowSources.sourceKind()
                + " / "
                + defaultsContext);
        check(
            "installation-local context does not claim the removed COMMON type",
            !PlatformWorkflowSources.sourceKind().contains("/LOCAL ")
                || !defaultsContext.contains("COMMON"));
        edit("level", "10");
        next("installation defaults draft stages independently");
      }
      case 8 -> {
        if (!ready() || !raw(defaults, "level").equals("10")) return false;
        retained();
        check(
            "four owners retain four independent drafts",
            workflow.documents().stream().filter(ClientEditingWorkflow.DocumentState::dirty).count()
                == 4);
        show(preferences);
        check(
            "returning uses identical form and actual actor",
            form == json.form() && workflow.editor(preferences) == json);
        check(
            "invalid input survives owner navigation",
            ((EditBox) form.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("broken"));
        press(host, "Undo");
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
        press(host, "Save and apply");
        show(PlatformWorkflowExample.KEYS);
        retained();
        check("entered save remains pending across native owner navigation", json.busy());
        workflow.screen().onClose();
        check(
            "normal workflow exit waits for actual pending save",
            NativeScreens.current(client) == workflow.screen());
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
                    initialNative, Files.readAllBytes(PlatformWorkflowSources.clientPath()))
                && Arrays.equals(
                    initialDefaults, Files.readAllBytes(PlatformWorkflowSources.defaultsPath())));
        show(PlatformWorkflowExample.KEYS);
        press(host, "Save and apply");
        retained();
        next("retained key save uses actual Options writer");
      }
      case 12 -> {
        if (!settled(keys)) return false;
        check(
            "key binding is actually applied",
            PlatformWorkflowExample.PRIMARY.saveString().equals("key.keyboard.4"));
        show(PlatformWorkflowSources.defaults().document());
        press(host, PlatformWorkflowSources.saveLabel());
        retained();
        next("retained installation defaults use their actual source writer");
      }
      case 13 -> {
        if (!settled(defaults)) return false;
        check("native default value is actually saved", PlatformWorkflowSources.level() == 10);
        check(
            "source-specific publication or native callback is observed",
            PlatformWorkflowSources.defaultSaveObserved());
        workflow.discard(PlatformWorkflowSources.client().document());
        check(
            "discard does not write client-source draft",
            Arrays.equals(initialNative, Files.readAllBytes(PlatformWorkflowSources.clientPath())));
        check(
            "discard retires only selected actor",
            !nativeClient.isOpen() && json.isOpen() && keys.isOpen() && defaults.isOpen());
        show(preferences);
        edit("distance", "192");
        next("independent workflow will compete with retained draft");
      }
      case 14 -> {
        if (!ready() || !raw(json, "distance").equals("192")) return false;
        retained();
        independent = PlatformWorkflowExample.open(parent);
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
                && PlatformWorkflowExample.settings()
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
        press(host, "Save and apply");
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
        retained();
        check(
            "overview distinguishes prior receipt from current revision refusal",
            workflow.documents().stream()
                .filter(state -> state.document().equals(preferences))
                .findFirst()
                .orElseThrow()
                .summary()
                .getString()
                .contains("Recorded receipt:"));
        NativeClientProbe.size(client, windowWidth, windowHeight);
        guiScale = client.options.guiScale().get();
        client.options.guiScale().set(3);
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        next("native compact overview resize requested");
      }
      case 19 -> {
        if (workflow.screen().width > 340) return false;
        geometry(workflow.screen());
        var narrated = workflow.screen().getNarrationMessage().getString();
        check(
            "compact workspace narration identifies the current save scope: " + narrated,
            narrated.contains("Composed settings") && narrated.contains("Client preferences"));
        check(
            "compact presentation retains the actual source refusal separately from prior saved receipt",
            json.view().operationReport().issues().stream()
                    .anyMatch(issue -> issue.code().equals("owner.revision"))
                && json.view().lastReceipt().orElseThrow().persistence()
                    == CommitReceipt.Persistence.SAVED);
        next("compact overview retains separate receipt and refusal feedback");
        capture.accept(
            "form-workflow-narrow.png",
            () -> {
              client.options.guiScale().set(guiScale);
              NativeClientProbe.resize(client, windowWidth[0], windowHeight[0]);
              NativeClientProbe.reflow(client);
              press(workflow.screen(), "Done");
            });
      }
      case 20 -> {
        check(
            "dirty workflow exit requests an explicit decision",
            NativeScreens.current(client) instanceof UnsavedChangesScreen);
        var decision = (UnsavedChangesScreen) NativeScreens.current(client);
        waiting = DocumentActions.result(keys.actions().load(EntryPoint.GUI)).toCompletableFuture();
        check(
            "public native source load remains active during a close decision",
            !waiting.isDone() && keys.busy());
        press(decision, "Discard draft");
        check(
            "workflow rechecks pending operations at the actual discard decision",
            workflow.isOpen()
                && NativeScreens.current(client) == workflow.screen()
                && json.isOpen());
        next("late custom-view operation invalidates earlier discard decision");
      }
      case 21 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        press(workflow.screen(), "Done");
        var decision = (UnsavedChangesScreen) NativeScreens.current(client);
        press(decision, "Keep editing");
        check(
            "keep editing preserves same overview and actor",
            NativeScreens.current(client) == workflow.screen() && json.isOpen());
        press(workflow.screen(), "Done");
        press(NativeScreens.current(client), "Discard draft");
        check(
            "normal discard closes all workflow actors",
            !workflow.isOpen() && !json.isOpen() && !keys.isOpen() && !defaults.isOpen());
        check("normal close returns to original parent", NativeScreens.current(client) == parent);
        try {
          workflow.editor(preferences);
          throw new AssertionError("Closed workflow reopened a hidden actor");
        } catch (PolicyRefusal expected) {
          check("closed workflow refuses reuse", true);
        }
        independent = PlatformWorkflowExample.open(parent);
        NativeScreens.open(client, independent.screen());
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
        show(independent, preferences);
        next("forced native navigation will release the entire workflow");
      }
      case 22 -> {
        if (!ready()) return false;
        press(independent.screen(), "Discard draft");
        waiting =
            DocumentActions.result(competing.actions().load(EntryPoint.GUI)).toCompletableFuture();
        check(
            "per-document decision can observe a newly queued source operation",
            !waiting.isDone() && competing.busy());
        press(NativeScreens.current(client), "Discard draft");
        check(
            "late per-document discard reports refusal and retains actual actor",
            competing.isOpen() && NativeScreens.current(client) == independent.screen());
        next("per-document decision rechecks live pending work without throwing");
      }
      case 23 -> {
        if (!waiting.isDone()) return false;
        waiting.join();
        var auxiliary = independent.editor(PlatformWorkflowExample.KEYS);
        independent.show(PlatformWorkflowExample.KEYS);
        NativeScreens.open(client, parent);
        check(
            "unexpected child removal closes all workflow editors",
            !independent.isOpen() && !auxiliary.isOpen() && !competing.isOpen());
        shutdown = PlatformWorkflowExample.open(parent);
        shutdown.editor(preferences);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown composed workflow stage " + stage);
    }
    return false;
  }

  private DocumentId hierarchyId(String path) {
    return DocumentId.parse("reform_platform_example:hierarchy/" + path);
  }

  private CycleButton<?> workflowView() {
    return widgets(hierarchy.screen()).stream()
        .filter(CycleButton.class::isInstance)
        .map(widget -> (CycleButton<?>) widget)
        .findFirst()
        .orElseThrow();
  }

  /** Calling-thread search response/rebuild cost; this does not measure rendered frame time. */
  private void measureCachedSearch() {
    var root = hierarchy.screen();
    var actor = hierarchy.editor(preferences);
    var before =
        design.inherent.reform.edit.DraftRevision.of(actor.view().snapshot().orElseThrow());
    long opened =
        hierarchy.documents().stream().filter(ClientEditingWorkflow.DocumentState::opened).count();
    var samples = new long[64];
    for (int index = 0; index < samples.length + 16; index++) {
      var search = input(root, "Search settings");
      String query = index % 2 == 0 ? "horizon threshold" : "";
      long start = System.nanoTime();
      search.setValue(query);
      long nanos = System.nanoTime() - start;
      if (index >= 16) samples[index - 16] = nanos;
    }
    if (!before.equals(
            design.inherent.reform.edit.DraftRevision.of(actor.view().snapshot().orElseThrow()))
        || hierarchy.documents().stream()
                .filter(ClientEditingWorkflow.DocumentState::opened)
                .count()
            != opened)
      throw new AssertionError("Cached search changed a draft or opened another owner");
    java.util.Arrays.sort(samples);
    cachedSearchCost = new JsonObject();
    cachedSearchCost.addProperty("scope", "Native search responder and synchronous widget rebuild");
    cachedSearchCost.addProperty("placements", 131);
    cachedSearchCost.addProperty("openedOwners", opened);
    cachedSearchCost.addProperty("warmups", 16);
    cachedSearchCost.addProperty("samples", samples.length);
    cachedSearchCost.addProperty("medianMillis", (samples[31] + samples[32]) / 2_000_000.0);
    cachedSearchCost.addProperty("p95Millis", samples[60] / 1_000_000.0);
    cachedSearchCost.addProperty("maximumMillis", samples[63] / 1_000_000.0);
    cachedSearchCost.addProperty("unchangedDraftAndOwnerCount", true);
  }

  private boolean hierarchyControls() {
    switch (hierarchyStage) {
      case 0 -> {
        var form = PlatformWorkflowExample.settings().form(Component.literal("Shared preferences"));
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
        NativeScreens.open(client, hierarchy.screen());
        check(
            "nested categories remain in one native tree over one actual owner",
            navigation(hierarchy.screen(), "Interface") != null
                && navigation(hierarchy.screen(), "Map module") != null
                && navigation(hierarchy.screen(), "Map planning") != null
                && hierarchy.documents().stream()
                        .filter(ClientEditingWorkflow.DocumentState::opened)
                        .count()
                    == 1);
        replace(
            hierarchy.screen(), input(hierarchy.screen(), "Search settings"), "horizon threshold");
        check(
            "description search across groups keeps only the matching placement",
            navigation(hierarchy.screen(), "Map distance") != null
                && widgets(hierarchy.screen()).stream()
                    .noneMatch(
                        widget ->
                            widget instanceof Button
                                && widget
                                    .getMessage()
                                    .getString()
                                    .strip()
                                    .matches("Map label [0-9]+")));
        var destination = navigation(hierarchy.screen(), "Map distance");
        focus(hierarchy.screen(), destination);
        click(hierarchy.screen(), destination);
        hierarchyForm = hierarchy.editor(form.route()).form();
        hierarchyStage++;
      }
      case 1 -> {
        if (hierarchyForm.editorView().availability() != EditorActor.Availability.READY
            || hierarchy.editor(preferences).busy()
            || !button(hierarchy.screen(), "Done").active
            || hierarchyForm.fieldControl(FieldPath.ROOT.field("distance")).isEmpty()) return false;
        var control =
            (EditBox) hierarchyForm.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow();
        check("selected catalog placement reveals its existing field", control.isFocused());
        replace(hierarchy.screen(), control, "bad");
        hierarchyStage++;
      }
      case 2 -> {
        if (hierarchyForm.editorView().validation().valid()
            || hierarchy.editor(preferences).busy()
            || !button(hierarchy.screen(), "Done").active) return false;
        var root = hierarchy.screen();
        check(
            "overview retains its earlier query when returning from a field",
            input(root, "Search settings").getValue().equals("horizon threshold"));
        replace(root, input(root, "Search settings"), "");
        check(
            "changing the query clears the exact active native input",
            input(root, "Search settings").getValue().isEmpty());
        for (String expected : List.of("MODIFIED", "INVALID", "RESTART")) {
          NativeClientProbe.press(workflowView());
          check(
              "expected " + expected + " but observed " + workflowView().getValue(),
              workflowView().getValue().toString().equals(expected));
          check(
              "filter " + expected + " keeps only its matching physical field",
              hierarchyForm.fieldControl(FieldPath.ROOT.field("distance")).isPresent()
                  && hierarchyForm.fieldControl(FieldPath.ROOT.field("label")).isEmpty());
          long unmatched =
              widgets(root).stream()
                  .filter(
                      widget ->
                          widget instanceof Button
                              && widget
                                  .getMessage()
                                  .getString()
                                  .strip()
                                  .matches("Map label [0-9]+"))
                  .count();
          check(
              "filter " + expected + " removes unmatched navigation aliases; observed=" + unmatched,
              navigation(root, "Map distance") != null && unmatched == 0);
        }
        NativeClientProbe.press(workflowView());
        long aliases =
            widgets(root).stream()
                .filter(
                    widget ->
                        widget instanceof Button
                            && widget.getMessage().getString().strip().matches("Map label [0-9]+"))
                .count();
        check(
            "clearing filters preserves all declared aliases with one physical control and draft",
            workflowView().getValue().toString().equals("ALL")
                && aliases == 130
                && hierarchy.documents().getFirst().dirty()
                && hierarchyForm.fieldControl(FieldPath.ROOT.field("distance")).isPresent()
                && hierarchyForm.fieldControl(FieldPath.ROOT.field("label")).isPresent());
        measureCachedSearch();
        hierarchy.close();
        NativeScreens.open(client, parent);
        hierarchyStage++;
        return true;
      }
      default -> throw new IllegalStateException("Hierarchy controls already completed");
    }
    return false;
  }
}
