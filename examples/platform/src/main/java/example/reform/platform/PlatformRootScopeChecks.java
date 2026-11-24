package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.presentation.*;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Actual custom-layout close choices, original scoped review, and failed-mount history recovery.
 */
final class PlatformRootScopeChecks {
  private static final DocumentId REVIEW = DocumentId.parse("fixture:close_review");
  private static final DestinationId BROKEN = DestinationId.parse("fixture:close_broken");
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final int[] width = new int[1], height = new int[1];
  private List<ClientForm> forms;
  private ClientWorkflowLayout layout;
  private ClientWorkspacePresentation presentation;
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private DocumentBatch.Review reviewed;
  private CompletableFuture<NavigationAttempt.Result> pending;
  private OperationHandle<?> reverting;
  private Button retired;
  private byte[] source;
  private long revision;
  private int stage;

  PlatformRootScopeChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private static Component text(String value) {
    return Component.literal(value);
  }

  private ClientWorkflowLayout.Category category(String id, String title) {
    return new ClientWorkflowLayout.Category(DocumentId.parse(id), text(title), Component.empty());
  }

  private byte[] source() throws Exception {
    return Files.readAllBytes(
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json"));
  }

  private void unchanged() throws Exception {
    check.accept(
        "scope choice and inspection preserve actual draft and source",
        workflow.editor(PlatformWorkflowExample.DEFINITION.id()) == editor
            && editor.view().snapshot().orElseThrow().version() == revision
            && Arrays.equals(source, source()));
  }

  private void press(String label) {
    var control = button(workflow.screen(), label);
    check.accept(
        "scope control is an active native target: " + label,
        control.active && focus(workflow.screen(), control));
    click(workflow.screen(), control);
  }

  private void open() {
    workflow = ClientEditingWorkflow.open(parent, forms, layout, presentation);
    NativeScreens.open(client, workflow.screen());
    pending =
        workflow
            .navigation()
            .open(new ClientDestination.Document(PlatformWorkflowExample.DEFINITION.id()))
            .completion()
            .toCompletableFuture();
  }

  private boolean revealed() {
    if (!pending.isDone()) return false;
    check.accept(
        "scope link acknowledges actual mounted target",
        pending.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  private boolean dependencyOpened() {
    return workflow.documents().stream()
        .anyMatch(d -> d.opened() && !d.document().equals(PlatformWorkflowExample.DEFINITION.id()));
  }

  private void edit() {
    editor = workflow.editor(PlatformWorkflowExample.DEFINITION.id());
    replace(
        workflow.screen(),
        (EditBox) editor.form().fieldControl(FieldPath.ROOT.field("label")).orElseThrow(),
        "Scoped draft");
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        source = source();
        forms =
            List.of(
                PlatformWorkflowExample.settings().form(text("Scoped preferences")),
                PlatformWorkspaceChecks.navigationTwin());
        var settings = category("fixture:close_settings", "Settings");
        var groups = new ArrayList<ClientWorkflowLayout.Group>();
        for (int i = 0; i < forms.size(); i++) {
          var form = forms.get(i);
          groups.add(
              new ClientWorkflowLayout.Group(
                  List.of(settings, category("fixture:close_owner/" + i, form.title().getString())),
                  List.of(
                      new ClientWorkflowLayout.Placement(
                          form.route(), form.route(), form.title(), Optional.empty())),
                  List.of()));
        }
        groups.add(
            new ClientWorkflowLayout.Group(
                List.of(category(BROKEN.toString(), "Unavailable view")), List.of(), List.of()));
        var scope =
            new ClientWorkflowLayout.ReviewScope(
                REVIEW,
                text("Reviewed preferences"),
                Set.of(forms.get(0).route()),
                Set.of(forms.get(1).route()),
                true,
                ignored -> DocumentBatch.Check.VALID);
        layout = new ClientWorkflowLayout(groups, List.of(scope));
        var broken =
            new ClientCustomView<>(
                new ViewKey<>(
                    BROKEN,
                    new ViewStateKey<>(
                        DocumentId.parse("fixture:close_state"), ValueTypes.integer(0, 1))),
                0,
                (lifetime, state) -> {
                  throw new IllegalStateException("fixture review-origin mount failure");
                });
        presentation =
            ClientWorkspacePresentation.standard(
                    new WorkspaceIdentity(
                        DocumentId.parse("fixture:scope_workspace"), text("Scoped workspace")))
                .withView(broken);
        open();
        stage = 1;
      }
      case 1 -> {
        if (!revealed()) return false;
        editor = workflow.editor(PlatformWorkflowExample.DEFINITION.id());
        if (editor.busy() || editor.form().fieldControl(FieldPath.ROOT.field("label")).isEmpty())
          return false;
        edit();
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        revision = editor.view().snapshot().orElseThrow().version();
        check.accept("scope fixture leaves its checked dependency unopened", !dependencyOpened());
        press("Done");
        stage = 3;
      }
      case 3 -> {
        press("Review changes");
        stage = 4;
      }
      case 4 -> {
        check.accept(
            "scoped close Review changes opens actual choices in SAME native root",
            NativeScreens.current(client) == workflow.screen()
                && button(workflow.screen(), "Reviewed preferences") != null
                && button(workflow.screen(), "Inspect Scoped preferences") != null);
        check.accept(
            "choosing a review opens no dependency or capture implicitly",
            !dependencyOpened() && workflow.batchInspection().isEmpty());
        unchanged();
        retired = button(workflow.screen(), "Reviewed preferences");
        check.accept(
            "scope Back participates in actual native focus",
            focus(workflow.screen(), button(workflow.screen(), "Back to settings")));
        NativeClientProbe.size(client, width, height);
        stage = 90;
        capture.accept("form-root-review-choices-wide.png", () -> stage = 5);
      }
      case 5 -> {
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 6;
      }
      case 6 -> {
        if (workflow.screen().width > 340) return false;
        net.minecraft.client.gui.components.events.GuiEventListener focused =
            workflow.screen().getFocused();
        while (focused
                instanceof
                net.minecraft.client.gui.components.events.ContainerEventHandler container
            && container.getFocused() != null) focused = container.getFocused();
        check.accept(
            "choice reflow restores its current native return focus",
            focused == button(workflow.screen(), "Back to settings"));
        NativeClientProbe.pressRetired(retired);
        check.accept(
            "retired scope callback cannot admit a dependency or capture",
            !dependencyOpened() && workflow.batchInspection().isEmpty());
        unchanged();
        stage = 90;
        capture.accept("form-root-review-choices-narrow.png", () -> stage = 7);
      }
      case 7 -> {
        NativeClientProbe.resize(client, width[0], height[0]);
        NativeClientProbe.reflow(client);
        press("Inspect Scoped preferences");
        stage = 8;
      }
      case 8 -> {
        if (workflow.inspection().flatMap(ClientInspection::reviewedInput).isEmpty()) return false;
        check.accept(
            "document choice inspects actual current capture without bypassing required scope",
            workflow
                    .inspection()
                    .orElseThrow()
                    .reviewedInput()
                    .orElseThrow()
                    .target()
                    .matches(editor.view().snapshot().orElseThrow())
                && !editor.canSubmit()
                && !dependencyOpened()
                && workflow.batchInspection().isEmpty());
        unchanged();
        pending = workflow.navigation().up().orElseThrow().completion().toCompletableFuture();
        stage = 9;
      }
      case 9 -> {
        if (!revealed()) return false;
        press("Done");
        stage = 10;
      }
      case 10 -> {
        press("Review changes");
        stage = 11;
      }
      case 11 -> {
        press("Reviewed preferences");
        stage = 12;
      }
      case 12 -> {
        if (workflow.batchInspection().flatMap(ClientBatchInspection::reviewedInput).isEmpty())
          return false;
        reviewed = workflow.batchInspection().orElseThrow().reviewedInput().orElseThrow();
        check.accept(
            "explicit scope choice invokes original complete COMMIT/CHECK review",
            reviewed.valid()
                && reviewed.entries().size() == 2
                && reviewed.entries().getFirst().intent() == DocumentBatch.Intent.COMMIT
                && reviewed.entries().getLast().intent() == DocumentBatch.Intent.CHECK
                && dependencyOpened());
        unchanged();
        pending =
            workflow
                .navigation()
                .open(new ClientDestination.View(BROKEN))
                .completion()
                .toCompletableFuture();
        stage = 13;
      }
      case 13 -> {
        if (!pending.isDone()) return false;
        check.accept(
            "failed native view mounting retains original Batch body and capture",
            pending.join().outcome() == NavigationAttempt.Outcome.FAILED
                && workflow
                    .navigation()
                    .current()
                    .orElseThrow()
                    .equals(new ClientDestination.BatchReview(REVIEW))
                && workflow.batchInspection().orElseThrow().reviewedInput().orElseThrow()
                    == reviewed);
        unchanged();
        press("Back");
        stage = 14;
      }
      case 14 -> {
        check.accept(
            "failed mount recovery adds no spurious editing history visit",
            !(workflow.navigation().current().orElseThrow()
                instanceof ClientDestination.BatchReview));
        press("Forward");
        stage = 15;
      }
      case 15 -> {
        if (!(workflow.navigation().current().orElseThrow()
            instanceof ClientDestination.BatchReview)) return false;
        check.accept(
            "history returns SAME issued scope capture without rechecking or saving",
            workflow.batchInspection().orElseThrow().reviewedInput().orElseThrow() == reviewed
                && workflow.batchInspection().orElseThrow().submission().isEmpty());
        unchanged();
        reverting = editor.actions().revert(EntryPoint.GUI);
        stage = 16;
      }
      case 16 -> {
        if (!reverting.snapshot().terminal() || editor.busy()) return false;
        check.accept(
            "scoped fixture cleanup uses actual revert with no source write",
            !editor.dirty() && Arrays.equals(source, source()));
        workflow.close();
        NativeScreens.open(client, parent);
        layout = new ClientWorkflowLayout(layout.groups(), List.of());
        open();
        stage = 20;
      }
      case 20 -> {
        if (!revealed()) return false;
        editor = workflow.editor(PlatformWorkflowExample.DEFINITION.id());
        if (editor.busy() || editor.form().fieldControl(FieldPath.ROOT.field("label")).isEmpty())
          return false;
        edit();
        stage = 21;
      }
      case 21 -> {
        if (editor.busy() || !editor.dirty()) return false;
        revision = editor.view().snapshot().orElseThrow().version();
        press("Done");
        stage = 22;
      }
      case 22 -> {
        press("Review changes");
        stage = 23;
      }
      case 23 -> {
        check.accept(
            "custom layout without declared scopes offers actual owner inspection",
            button(workflow.screen(), "Inspect Scoped preferences") != null
                && workflow.batchInspection().isEmpty()
                && !dependencyOpened());
        unchanged();
        press("Inspect Scoped preferences");
        stage = 24;
      }
      case 24 -> {
        if (workflow.inspection().flatMap(ClientInspection::reviewedInput).isEmpty()) return false;
        unchanged();
        check.accept(
            "unscoped owner inspection invents no batch or dependency writer",
            workflow.batchInspection().isEmpty() && !dependencyOpened());
        reverting = editor.actions().revert(EntryPoint.GUI);
        stage = 25;
      }
      case 25 -> {
        if (!reverting.snapshot().terminal() || editor.busy()) return false;
        check.accept(
            "unscoped review cleanup leaves original file bytes and no unsaved draft",
            !editor.dirty() && Arrays.equals(source, source()));
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 26;
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
