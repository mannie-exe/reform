package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Real complete-owner capture, root navigation and separate owner receipts, not a batch mock. */
final class PlatformRootBatchChecks {
  private static final DocumentId SCOPE = DocumentId.parse("reform:client_workflow");
  private static final DocumentId TWIN = DocumentId.parse("reform_platform_example:workspace_twin");
  private static final FieldPath LABEL = FieldPath.ROOT.field("label");
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final int[] width = new int[1], height = new int[1];
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor, twin;
  private ClientBatchInspection inspection;
  private DocumentBatch.Review reviewed;
  private OperationHandle<DocumentBatch.Result> submission;
  private OperationHandle<?> restoration;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private NavigationBookmark bookmark, resultBookmark;
  private byte[] original, twinOriginal;
  private String originalLabel;
  private long version, twinVersion;
  private int stage;

  PlatformRootBatchChecks(BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check = check;
    this.capture = capture;
  }

  private byte[] source(String name) throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_platform_example/" + name + ".json"));
  }

  private void unchanged() throws Exception {
    check.accept(
        "batch browsing preserves both actual sources",
        Arrays.equals(original, source("workflow_preferences"))
            && Arrays.equals(twinOriginal, source("workspace_twin")));
    check.accept(
        "batch browsing retains both actual actors",
        workflow.editor(PlatformWorkflowExample.DEFINITION.id()) == editor
            && workflow.editor(TWIN) == twin);
  }

  private void enter(ClientDestination target) {
    navigation = workflow.navigation().open(target).completion().toCompletableFuture();
  }

  private boolean revealed() {
    if (!navigation.isDone()) return false;
    check.accept(
        "batch link acknowledges its actual mounted target: " + navigation.join(),
        navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
    return true;
  }

  private EditBox field(ClientEditor<?> owner) {
    return (EditBox) owner.form().fieldControl(LABEL).orElseThrow();
  }

  private void press(String label) {
    var control = button(workflow.screen(), label);
    check.accept("batch native control is active: " + label, control.active);
    check.accept(
        "batch native control belongs to root: " + label, focus(workflow.screen(), control));
    click(workflow.screen(), control);
  }

  boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        original = source("workflow_preferences");
        twinOriginal = source("workspace_twin");
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Batch workspace"),
                List.of(
                    PlatformWorkflowExample.settings()
                        .form(Component.literal("Edited preferences")),
                    PlatformWorkspaceChecks.navigationTwin()));
        NativeScreens.open(client, workflow.screen());
        enter(new ClientDestination.Document(route));
        stage = 1;
      }
      case 1 -> {
        if (!revealed()) return false;
        editor = workflow.editor(route);
        if (editor.busy() || editor.form().fieldControl(LABEL).isEmpty()) return false;
        originalLabel = field(editor).getValue();
        replace(workflow.screen(), field(editor), "Batch draft");
        stage = 2;
      }
      case 2 -> {
        if (editor.busy() || !editor.dirty()) return false;
        version = editor.view().snapshot().orElseThrow().version();
        press("Review all documents");
        stage = 3;
      }
      case 3 -> {
        var current = NativeScreens.current(client);
        if (current != workflow.screen())
          check.accept(
              "complete batch review keeps original native root: "
                  + current.getClass().getSimpleName(),
              false);
        // Review-all explicitly admits the previously unopened owner asynchronously.
        // Observe the real capture rather than assuming that one tick admits the complete scope.
        inspection = workflow.batchInspection().orElse(null);
        if (inspection == null || inspection.reviewedInput().isEmpty()) return false;
        check.accept(
            "complete batch review keeps original native root", current == workflow.screen());
        check.accept(
            "complete batch mounts its actual submission control",
            button(current, "Save reviewed documents") != null);
        reviewed = inspection.reviewedInput().orElseThrow();
        twin = workflow.editor(TWIN);
        if (editor.busy() || twin.busy()) return false;
        twinVersion = twin.view().snapshot().orElseThrow().version();
        check.accept(
            "complete capture keeps COMMIT and CHECK participation in issued order",
            reviewed.valid()
                && reviewed.entries().size() == 2
                && reviewed.entries().get(0).document().equals(route)
                && reviewed.entries().get(0).intent() == DocumentBatch.Intent.COMMIT
                && reviewed.entries().get(1).document().equals(TWIN)
                && reviewed.entries().get(1).intent() == DocumentBatch.Intent.CHECK
                && editor.view().snapshot().orElseThrow().version() == version);
        unchanged();
        press("Tick target preferences");
        stage = 4;
      }
      case 4 -> {
        check.accept(
            "index highlight selects only presentation over the same complete capture",
            inspection.reviewedInput().orElseThrow() == reviewed
                && inspection.selectedDocument().orElseThrow().equals(TWIN)
                && inspection.submission().isEmpty()
                && twin.view().snapshot().orElseThrow().version() == twinVersion);
        unchanged();
        check.accept(
            "batch bookmark captures an actual detail control",
            focus(workflow.screen(), button(workflow.screen(), "Inspect fields")));
        bookmark = workflow.navigation().bookmark();
        press("Inspect fields");
        stage = 5;
      }
      case 5 -> {
        var owner = workflow.inspection().orElse(null);
        if (owner == null || owner.reviewedInput().isEmpty()) return false;
        check.accept(
            "owner field inspection borrows the exact issued per-owner capture in root",
            NativeScreens.current(client) == workflow.screen()
                && owner.reviewedInput().orElseThrow() == reviewed.entries().get(1).review()
                && workflow.batchInspection().orElseThrow() == inspection);
        unchanged();
        navigation = workflow.navigation().up().orElseThrow().completion().toCompletableFuture();
        stage = 6;
      }
      case 6 -> {
        if (!revealed()) return false;
        check.accept(
            "owner parent link restores the SAME batch review and selection",
            workflow
                    .navigation()
                    .current()
                    .orElseThrow()
                    .equals(new ClientDestination.BatchReview(SCOPE))
                && inspection.reviewedInput().orElseThrow() == reviewed
                && inspection.selectedDocument().orElseThrow().equals(TWIN));
        NativeClientProbe.size(client, width, height);
        stage = 90;
        capture.accept("form-root-batch-wide.png", () -> stage = 7);
      }
      case 7 -> {
        NativeClientProbe.resize(client, 320, 240);
        NativeClientProbe.reflow(client);
        stage = 8;
      }
      case 8 -> {
        if (workflow.screen().width > 340) return false;
        check.accept(
            "narrow batch retains capture, selection and actors without submission",
            inspection.reviewedInput().orElseThrow() == reviewed
                && inspection.selectedDocument().orElseThrow().equals(TWIN)
                && inspection.submission().isEmpty());
        unchanged();
        stage = 90;
        capture.accept("form-root-batch-narrow.png", () -> stage = 9);
      }
      case 9 -> {
        NativeClientProbe.resize(client, width[0], height[0]);
        NativeClientProbe.reflow(client);
        enter(new ClientDestination.Document(TWIN));
        stage = 10;
      }
      case 10 -> {
        if (!revealed() || twin.busy() || twin.form().fieldControl(LABEL).isEmpty()) return false;
        replace(workflow.screen(), field(twin), "x".repeat(96));
        stage = 11;
      }
      case 11 -> {
        if (twin.busy()) return false;
        check.accept(
            "retained rejected text is distinct from an otherwise valid actor draft",
            twin.view().validation().valid()
                && field(twin).getValue().equals("x".repeat(96))
                && !twin.canSubmit());
        navigation = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 12;
      }
      case 12 -> {
        if (!revealed()) return false;
        check.accept(
            "cached READ inspection does not accept retained input or permit a partial save",
            inspection.reviewedInput().orElseThrow() == reviewed
                && inspection.selectedDocument().orElseThrow().equals(TWIN)
                && !button(workflow.screen(), "Save reviewed documents").active
                && inspection.submission().isEmpty());
        net.minecraft.client.gui.components.events.GuiEventListener focused =
            workflow.screen().getFocused();
        while (focused
                instanceof
                net.minecraft.client.gui.components.events.ContainerEventHandler container
            && container.getFocused() != null) focused = container.getFocused();
        check.accept(
            "batch bookmark restores the native detail focus in the current child tree",
            focused == button(workflow.screen(), "Inspect fields"));
        press("Refresh complete review");
        stage = 13;
      }
      case 13 -> {
        check.accept(
            "refused refresh retains the original complete capture without submission",
            inspection.reviewedInput().orElseThrow() == reviewed
                && inspection.submission().isEmpty());
        unchanged();
        enter(new ClientDestination.Document(TWIN));
        stage = 14;
      }
      case 14 -> {
        if (!revealed() || twin.busy() || twin.form().fieldControl(LABEL).isEmpty()) return false;
        replace(workflow.screen(), field(twin), "Example");
        stage = 15;
      }
      case 15 -> {
        if (twin.busy() || !twin.canSubmit()) return false;
        navigation = workflow.navigation().restore(bookmark).completion().toCompletableFuture();
        stage = 16;
      }
      case 16 -> {
        if (!revealed()) return false;
        press("Refresh complete review");
        stage = 17;
      }
      case 17 -> {
        var actual = inspection.reviewedInput();
        if (actual.isEmpty() || actual.orElseThrow() == reviewed) return false;
        reviewed = actual.orElseThrow();
        if (!button(workflow.screen(), "Save reviewed documents").active) return false;
        check.accept(
            "explicit repair and refresh issue a new complete valid review",
            reviewed.valid() && reviewed.entries().get(1).intent() == DocumentBatch.Intent.CHECK);
        unchanged();
        press("Save reviewed documents");
        stage = 18;
      }
      case 18 -> {
        var actual = inspection.submission();
        if (actual.isEmpty() || !actual.orElseThrow().snapshot().terminal()) return false;
        submission = actual.orElseThrow();
        var result = submission.snapshot().result().orElseThrow();
        check.accept(
            "batch result retains separate original COMMIT receipt and CHECK no-write outcome",
            result.completed()
                && result.review().equals(reviewed.id())
                && result.outcomes().size() == 2
                && result.outcomes().get(0).operation().orElseThrow().result().orElseThrow()
                    instanceof EditorActor.Commit<?> saved
                && saved.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED
                && result.outcomes().get(1).intent() == DocumentBatch.Intent.CHECK
                && result.outcomes().get(1).operation().isEmpty()
                && Arrays.equals(twinOriginal, source("workspace_twin"))
                && !Arrays.equals(original, source("workflow_preferences")));
        var target = new ClientDestination.BatchResult(SCOPE, submission.snapshot().id());
        check.accept(
            "result is in original root with exact registered batch operation identity",
            NativeScreens.current(client) == workflow.screen()
                && workflow.navigation().current().orElseThrow().equals(target));
        resultBookmark = workflow.navigation().bookmark();
        enter(new ClientDestination.BatchReview(SCOPE));
        stage = 19;
      }
      case 19 -> {
        if (!revealed()) return false;
        check.accept(
            "result parent return retains consumed capture and same submission",
            inspection.reviewedInput().orElseThrow() == reviewed
                && reviewed.consumed()
                && inspection.submission().orElseThrow() == submission
                && !button(workflow.screen(), "Save reviewed documents").active);
        navigation =
            workflow.navigation().restore(resultBookmark).completion().toCompletableFuture();
        stage = 20;
      }
      case 20 -> {
        if (!revealed()) return false;
        check.accept(
            "result bookmark restores same handle without another write",
            inspection.submission().orElseThrow() == submission
                && submission.snapshot().result().orElseThrow().review().equals(reviewed.id())
                && Arrays.equals(twinOriginal, source("workspace_twin")));
        enter(new ClientDestination.Document(route));
        stage = 21;
      }
      case 21 -> {
        if (!revealed() || editor.busy() || editor.form().fieldControl(LABEL).isEmpty())
          return false;
        check.accept(
            "batch save reached actual loaded file owner",
            field(editor).getValue().equals("Batch draft"));
        replace(workflow.screen(), field(editor), originalLabel);
        stage = 22;
      }
      case 22 -> {
        if (editor.busy() || !editor.canSubmit()) return false;
        restoration = editor.actions().commit(EntryPoint.GUI);
        stage = 23;
      }
      case 23 -> {
        if (!restoration.snapshot().terminal() || editor.busy()) return false;
        check.accept(
            "fixture restoration uses the actual owner and preserves both original source bytes",
            restoration.snapshot().result().orElseThrow() instanceof EditorActor.Commit<?> saved
                && saved.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED
                && Arrays.equals(original, source("workflow_preferences"))
                && Arrays.equals(twinOriginal, source("workspace_twin")));
        var prior = editor;
        workflow.discard(route);
        check.accept(
            "owner retirement invalidates its old batch and result without resurrection",
            !prior.isOpen()
                && workflow.navigation().inspect(new ClientDestination.BatchReview(SCOPE)).kind()
                    == DestinationAvailability.Kind.MISSING
                && workflow
                        .navigation()
                        .inspect(
                            new ClientDestination.BatchResult(SCOPE, submission.snapshot().id()))
                        .kind()
                    == DestinationAvailability.Kind.MISSING);
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 24;
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
