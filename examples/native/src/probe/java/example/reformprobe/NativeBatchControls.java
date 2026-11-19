package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.neoforge.client.BatchReviewScreen;
import design.inherent.reform.neoforge.client.ClientEditingWorkflow;
import design.inherent.reform.neoforge.client.ClientEditor;
import design.inherent.reform.neoforge.client.OperationScreen;
import design.inherent.reform.neoforge.client.ReviewScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import example.reform.ClientExample;
import example.reform.KeyExample;
import example.reform.NativeExample;
import example.reform.RegisteredExample;
import example.reform.WorkflowExample;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Actual complete-vector preflight, generated review, native callbacks and ordered owner saves. */
final class NativeBatchControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final JsonArray checks = new JsonArray();
  private final AtomicInteger crossChecks = new AtomicInteger(), nativeChecks = new AtomicInteger();
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> json, nativeClient, defaults;
  private SettingsScreen<?> form;
  private BatchReviewScreen batch;
  private DocumentBatch.Review invalid;
  private CompletableFuture<?> waiting;
  private final java.util.ArrayList<CompletableFuture<?>> heldMetadata =
      new java.util.ArrayList<>();
  private byte[] beforeJson, beforeNative, beforeDefaults, beforeOptions;
  private final int[] windowWidth = new int[1], windowHeight = new int[1];
  private int guiScale, stage;
  private boolean complete;

  NativeBatchControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "RUNNING");
    result.add("checks", checks);
    result.addProperty("workflowClosed", workflow != null && !workflow.isOpen());
    result.addProperty("crossChecks", crossChecks.get());
    result.addProperty("nativeCrossChecks", nativeChecks.get());
    return result;
  }

  private void check(String description, boolean value) {
    if (!value) throw new AssertionError(description);
    checks.add(description);
  }

  private void next(String description) {
    stage++;
    progress.accept(description);
  }

  private void press(Screen screen, String label) {
    click(screen, button(screen, label));
  }

  private void show(ClientEditor<?> selected) {
    workflow.show(selected.document());
    form = selected.screen();
  }

  private boolean ready() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Back to overview").active;
  }

  private void edit(String name, String raw) {
    var path = FieldPath.ROOT.field(name);
    check("generated field is reachable: " + name, form.focusField(path));
    replace(form, (EditBox) form.fieldControl(path).orElseThrow(), raw);
  }

  private boolean raw(ClientEditor<?> editor, String name, String value) {
    return editor
        .view()
        .snapshot()
        .map(
            snapshot ->
                ((design.inherent.reform.edit.DraftNode.Scalar)
                        snapshot.draft().at(FieldPath.ROOT.field(name)))
                    .raw()
                    .equals(value))
        .orElse(false);
  }

  private byte[] jsonBytes() throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_native_example/preferences.json"));
  }

  private void noWrites() throws Exception {
    check(
        "review does not write any of the four actual sources",
        Arrays.equals(beforeJson, jsonBytes())
            && Arrays.equals(
                beforeNative, Files.readAllBytes(RegisteredExample.clientConfig().getFullPath()))
            && Arrays.equals(
                beforeDefaults, Files.readAllBytes(RegisteredExample.commonConfig().getFullPath()))
            && Arrays.equals(beforeOptions, Files.readAllBytes(client.options.getFile().toPath())));
  }

  private void inspectFirst() {
    var inspect = button(batch, "Inspect fields");
    for (int i = 0; i < 128 && inspect.getBottom() > batch.height - 104; i++)
      batch.mouseScrolled(batch.width / 2.0, batch.height / 2.0, 0, -1);
    click(batch, inspect);
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (ClientExample.settings().runtime().isEmpty()
            || KeyExample.settings().definition().isEmpty()
            || RegisteredExample.client().definition().isEmpty()
            || RegisteredExample.common().definition().isEmpty()) return false;
        var defaultDefinition = RegisteredExample.common().definition().orElseThrow();
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Complete settings review"),
                WorkflowExample.forms(),
                values -> {
                  crossChecks.incrementAndGet();
                  if (client.isSameThread()) nativeChecks.incrementAndGet();
                  var preferences = values.candidate(NativeExample.DEFINITION).orElseThrow();
                  var nativeDefaults = values.candidate(defaultDefinition).orElseThrow();
                  return preferences.distance() <= (Integer) nativeDefaults.get("level") * 64
                      ? DocumentBatch.Check.VALID
                      : new DocumentBatch.Check(
                          Map.of(
                              NativeExample.DEFINITION.id(),
                              new ValidationReport(
                                  List.of(
                                      Issue.error(
                                          "fixture.batch.distance",
                                          FieldPath.ROOT.field("distance"),
                                          "Distance exceeds the other document's declared limit")),
                                  true)),
                          true);
                });
        client.setScreen(workflow.screen());
        json = workflow.editor(NativeExample.DEFINITION.id());
        nativeClient = workflow.editor(RegisteredExample.client().document());
        defaults = workflow.editor(RegisteredExample.common().document());
        show(json);
        next("batch enters retained generated preferences");
      }
      case 1 -> {
        if (!ready()) return false;
        if (reopen) {
          check("fresh JVM observes ordered JSON save", raw(json, "distance", "384"));
          check(
              "fresh JVM observes both native saves",
              RegisteredExample.live() == 7
                  && RegisteredExample.world() == 2
                  && RegisteredExample.game() == 3
                  && RegisteredExample.defaults() == 8);
          check(
              "clean native Controls retained its original key",
              KeyExample.PRIMARY
                  .getKey()
                  .getName()
                  .equals(
                      Files.readString(
                          client.gameDirectory.toPath().resolve("native-batch-key.txt"))));
          press(form, "Back to overview");
          workflow.close();
          client.setScreen(parent);
          complete = true;
          return true;
        }
        beforeJson = jsonBytes();
        beforeNative = Files.readAllBytes(RegisteredExample.clientConfig().getFullPath());
        beforeDefaults = Files.readAllBytes(RegisteredExample.commonConfig().getFullPath());
        beforeOptions = Files.readAllBytes(client.options.getFile().toPath());
        Files.writeString(
            client.gameDirectory.toPath().resolve("native-batch-key.txt"),
            KeyExample.PRIMARY.getKey().getName());
        edit("distance", "320");
        next("actual JSON candidate violates a cross-document rule");
      }
      case 2 -> {
        if (!ready() || !raw(json, "distance", "320")) return false;
        press(form, "Back to overview");
        press(workflow.screen(), "Review all documents");
        batch = (BatchReviewScreen) client.screen;
        next("complete preflight includes unopened native Controls");
      }
      case 3 -> {
        if (batch.reviewedInput().isEmpty()) return false;
        invalid = batch.reviewedInput().orElseThrow();
        check("all four owners are represented", invalid.entries().size() == 4);
        check(
            "real cross-document rule rejects the vector",
            !invalid.valid()
                && invalid.checksAttempted()
                && invalid.check().documents().containsKey(NativeExample.DEFINITION.id()));
        check(
            "invalid vector cannot be submitted", !button(batch, "Save reviewed documents").active);
        noWrites();
        geometry(batch);
        capture.accept("form-batch-invalid.png", this::inspectFirst);
        next("invalid complete preflight retained without effects");
      }
      case 4 -> {
        check(
            "batch opens real exact-capture field inspection",
            client.screen instanceof ReviewScreen<?>);
        var inspection = (ReviewScreen<?>) client.screen;
        check(
            "inspection uses the combined review's actual capture",
            inspection
                .reviewedInput()
                .orElseThrow()
                .target()
                .equals(invalid.entries().getFirst().review().target()));
        press(inspection, "Back");
        check(
            "field inspection returns to retained batch review",
            client.screen == batch && workflow.isOpen());
        press(batch, "Back to documents");
        show(nativeClient);
        next("editing resumes without replacing either owner session");
      }
      case 5 -> {
        if (!ready()) return false;
        edit("live", "7");
        edit("world", "2");
        edit("game", "3");
        next("native candidate keeps its declared restart boundaries");
      }
      case 6 -> {
        if (!ready()
            || !raw(nativeClient, "live", "7")
            || !raw(nativeClient, "world", "2")
            || !raw(nativeClient, "game", "3")) return false;
        press(form, "Back to overview");
        show(defaults);
        next("second native owner retains its own serializer");
      }
      case 7 -> {
        if (!ready()) return false;
        edit("level", "8");
        next("cross-document constraint corrected in actual native defaults draft");
      }
      case 8 -> {
        if (!ready() || !raw(defaults, "level", "8")) return false;
        press(form, "Back to overview");
        press(workflow.screen(), "Review all documents");
        batch = (BatchReviewScreen) client.screen;
        next("fresh combined review over three edited and one clean owner");
      }
      case 9 -> {
        if (batch.reviewedInput().isEmpty()) return false;
        var reviewed = batch.reviewedInput().orElseThrow();
        check("corrected complete vector passes", reviewed.valid());
        check(
            "clean Controls is a read-only guard",
            reviewed.entries().stream()
                .filter(value -> value.document().equals(KeyExample.DOCUMENT))
                .allMatch(value -> value.intent() == DocumentBatch.Intent.CHECK));
        check(
            "three changed sources are selected for ordered saving",
            reviewed.entries().stream()
                    .filter(value -> value.intent() == DocumentBatch.Intent.COMMIT)
                    .count()
                == 3);
        waiting =
            DocumentActions.result(json.actions().edit(EntryPoint.INTERNAL, "distance", "384"))
                .toCompletableFuture();
        next("later draft intentionally invalidates the captured batch review");
      }
      case 10 -> {
        if (!waiting.isDone() || button(batch, "Save reviewed documents").active) return false;
        waiting.join();
        check("later editing disables stale submission", raw(json, "distance", "384"));
        noWrites();
        press(batch, "Refresh complete review");
        next("stale review refresh performs another complete preflight");
      }
      case 11 -> {
        if (batch.reviewedInput().isEmpty() || !button(batch, "Save reviewed documents").active)
          return false;
        noWrites();
        geometry(batch);
        capture.accept(
            "form-batch-wide.png",
            () -> {
              GLFW.glfwGetWindowSize(client.getWindow().getWindow(), windowWidth, windowHeight);
              guiScale = client.options.guiScale().get();
              client.options.guiScale().set(3);
              GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
              client.resizeDisplay();
            });
        next("wide complete batch review captured");
      }
      case 12 -> {
        if (batch.width > 340) return false;
        geometry(batch);
        check(
            "compact review remains narratable",
            batch.getNarrationMessage().getString().contains("separately"));
        capture.accept(
            "form-batch-narrow.png",
            () -> {
              client.options.guiScale().set(guiScale);
              GLFW.glfwSetWindowSize(
                  client.getWindow().getWindow(), windowWidth[0], windowHeight[0]);
              client.resizeDisplay();
              press(batch, "Save reviewed documents");
            });
        next("compact review submits identified ordered owner operations");
      }
      case 13 -> {
        if (!(client.screen instanceof OperationScreen<?> result)
            || result.observedOperation().isEmpty()
            || !result.observedOperation().orElseThrow().terminal()) return false;
        var actual =
            (DocumentBatch.Result) result.observedOperation().orElseThrow().result().orElseThrow();
        check(
            "terminal progress counts all actual submitted owners",
            result.observedOperation().orElseThrow().progress().completed() == 3
                && result.observedOperation().orElseThrow().progress().total().orElseThrow() == 3);
        check(
            "ordered submission completes all four outcomes",
            actual.completed() && actual.outcomes().size() == 4);
        check(
            "three native source operations retain actual receipts",
            actual.outcomes().stream().filter(value -> value.operation().isPresent()).count() == 3);
        check(
            "actual native CLIENT result remains saved and staged",
            nativeClient.view().lastReceipt().orElseThrow().persistence()
                    == CommitReceipt.Persistence.SAVED
                && nativeClient.view().lastReceipt().orElseThrow().application()
                    == CommitReceipt.Application.STAGED);
        check(
            "actual JSON and native values were saved",
            ClientExample.settings().runtime().orElseThrow().view().value().orElseThrow().distance()
                    == 384
                && RegisteredExample.live() == 7
                && RegisteredExample.rawWorld() == 2
                && RegisteredExample.rawGame() == 3
                && RegisteredExample.defaults() == 8);
        check(
            "clean Controls never writes Options",
            Arrays.equals(beforeOptions, Files.readAllBytes(client.options.getFile().toPath())));
        check(
            "cross-document checks run off native thread",
            crossChecks.get() >= 4 && nativeChecks.get() == 0);
        geometry(result);
        capture.accept(
            "form-batch-result.png",
            () -> {
              press(result, "Back");
              press(batch, "Back to documents");
            });
        next("actual owner receipts retained separately in native operation view");
      }
      case 14 -> {
        check(
            "same editor survives combined saving",
            workflow.editor(json.document()) == json && json.isOpen() && nativeClient.isOpen());
        press(workflow.screen(), "Last batch review/result");
        check(
            "batch review and result are retained after overview navigation",
            client.screen == batch && batch.submission().isPresent());
        press(batch, "Submission result");
        press(client.screen, "Back");
        press(batch, "Back to documents");
        check(
            "every actual submitted draft is clean",
            !json.view().dirty() && !nativeClient.view().dirty() && !defaults.view().dirty());
        check(
            "retained controls agree with the clean owner drafts",
            workflow.documents().stream().noneMatch(ClientEditingWorkflow.DocumentState::dirty));
        show(json);
        next("retained form will reconcile accepted external edits");
      }
      case 15 -> {
        if (!ready()) return false;
        check(
            "reopened field shows the accepted current actor value",
            ((EditBox) form.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("384"));
        boolean refused = false;
        for (int i = 0; i < 256; i++) {
          try {
            heldMetadata.add(
                json.actions().describe(EntryPoint.INTERNAL).completion().toCompletableFuture());
          } catch (design.inherent.reform.owner.PolicyRefusal full) {
            refused = true;
            break;
          }
        }
        check(
            "actual bounded action scope is occupied before native input",
            refused && !heldMetadata.isEmpty());
        edit("distance", "448");
        waiting = CompletableFuture.allOf(heldMetadata.toArray(CompletableFuture[]::new));
        next("native input refused by the real full operation scope");
      }
      case 16 -> {
        if (!waiting.isDone() || !ready()) return false;
        waiting.join();
        check(
            "refused local input never changed the actual actor draft",
            raw(json, "distance", "384"));
        check(
            "refused text remains in the retained widget",
            ((EditBox) form.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("448"));
        press(form, "Back to overview");
        check(
            "unaccepted local input remains dirty in overview",
            workflow.documents().stream()
                .filter(value -> value.document().equals(json.document()))
                .allMatch(ClientEditingWorkflow.DocumentState::dirty));
        press(workflow.screen(), "Review all documents");
        check(
            "combined review refuses to omit unaccepted local text",
            client.screen == workflow.screen());
        show(json);
        next("unaccepted local input survives external reconciliation");
      }
      case 17 -> {
        if (!ready()) return false;
        check(
            "reopening does not discard refused local text",
            ((EditBox) form.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("448"));
        press(form, "Revert");
        next("explicit Revert recovers the retained rejected local input");
      }
      case 18 -> {
        if (!ready()
            || !((EditBox) form.fieldControl(FieldPath.ROOT.field("distance")).orElseThrow())
                .getValue()
                .equals("384")) return false;
        press(form, "Back to overview");
        press(workflow.screen(), "Done");
        check(
            "ordinary clean exit releases all actual sessions",
            !workflow.isOpen()
                && !json.isOpen()
                && !nativeClient.isOpen()
                && !defaults.isOpen()
                && client.screen == parent);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown native batch stage " + stage);
    }
    return false;
  }
}
