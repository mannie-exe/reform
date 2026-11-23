package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.minecraft.client.FieldEditors;
import design.inherent.reform.minecraft.client.OperationScreen;
import design.inherent.reform.minecraft.client.WorldRecipe;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.value.SnapshotPolicy;
import example.reform.CreationExample;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;

/** Same native Create World screen and actions that an ordinary addon can use. */
final class NativeRecipePreviewControls {
  private final Minecraft client = Minecraft.getInstance();
  private final CreateWorldScreen parent;
  private final WorldCreationContext original;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final AtomicInteger calls = new AtomicInteger();
  private final CountDownLatch release = new CountDownLatch(1);
  private final JsonArray checks = new JsonArray();
  private final JsonObject report = new JsonObject();
  private final DocumentActions<CreationExample.Recipe> actions;
  private final Screen form;
  private int stage;
  private UUID first;
  private DraftRevision secondDraft;

  NativeRecipePreviewControls(
      CreateWorldScreen parent,
      WorldCreationContext original,
      Consumer<String> progress,
      BiConsumer<String, Runnable> capture) {
    this.parent = parent;
    this.original = original;
    this.progress = progress;
    this.capture = capture;
    report.add("checks", checks);
    var recipe =
        CreationExample.editor(parent, original)
            .withPreview(
                new WorldRecipe.Preview<Integer, Integer>(
                    Component.literal("Terrain preview"),
                    (compiled, execution) -> {
                      if (client.isSameThread())
                        throw new AssertionError("Analysis must use the worker");
                      if (calls.incrementAndGet() == 1) {
                        if (compiled != 6) throw new AssertionError("First draft was not captured");
                        execution.progress(
                            new OperationSnapshot.Progress(
                                "Sampling recipe", 1, OptionalLong.of(3), "samples"));
                        try {
                          if (!release.await(12, TimeUnit.SECONDS))
                            throw new AssertionError("Preview fixture was never released");
                        } catch (InterruptedException failed) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(failed);
                        }
                        execution.checkpoint();
                      }
                      return compiled;
                    },
                    SnapshotPolicy.immutable(),
                    result ->
                        new OperationScreen.Outcome(
                            Component.literal("Compiled soil depth " + result.value()),
                            List.of())));
    actions = recipe.actions();
    form =
        recipe.screen(
            Component.literal("Previewable recipe settings"),
            Map.of(),
            FormPresentation.EMPTY,
            FieldEditors.builder(CreationExample.DEFINITION).build(),
            Optional.empty());
    client.setScreen(form);
  }

  private void check(String label, boolean passing) {
    if (!passing) throw new AssertionError(label);
    checks.add(label);
  }

  private void step(String label) {
    stage++;
    progress.accept(label);
  }

  private void edit(String value) {
    returnToSettings(form);
    replace(form, input(form, "Soil depth"), value);
  }

  private boolean ready(String value) {
    return client.screen == form
        && actions.view().availability() == EditorActor.Availability.READY
        && actions.view().validation().valid()
        && button(form, "Done").active
        && input(form, "Soil depth").getValue().equals(value);
  }

  private OperationScreen<?> operation() {
    if (!(client.screen instanceof OperationScreen<?> screen))
      throw new AssertionError("Preview did not open the actual operation view");
    return screen;
  }

  boolean tick() {
    switch (stage) {
      case 0 -> {
        if (!ready("2")) return false;
        check("initial recipe screen starts no analysis", calls.get() == 0);
        edit("6");
        step("first preview draft edited through native text");
      }
      case 1 -> {
        if (!ready("6") || !actions.view().dirty()) return false;
        check("ordinary typing starts no analysis", calls.get() == 0);
        click(form, ownerAction(form, "Preview…"));
        step("explicit preview requested");
      }
      case 2 -> {
        var view = operation();
        var observed = view.observedOperation().orElse(null);
        if (observed == null || !observed.progress().phase().equals("Sampling recipe"))
          return false;
        first = observed.id();
        check(
            "real operation displays bounded worker progress",
            observed.state() == OperationSnapshot.State.RUNNING
                && observed.progress().completed() == 1
                && observed.progress().total().orElseThrow() == 3);
        click(view, button(view, "Back"));
        edit("8");
        step("same form accepts edits while the first preview is held");
      }
      case 3 -> {
        if (!ready("8") || !actions.view().dirty()) return false;
        secondDraft = DraftRevision.of(actions.view().snapshot().orElseThrow());
        check(
            "editing advances the validated draft while analysis remains held",
            calls.get() == 1
                && release.getCount() == 1
                && parent.getUiState().getSettings() == original);
        click(form, ownerAction(form, "Preview…"));
        var view = operation();
        check(
            "reopen observes the same live preview identity",
            view.observedOperation().orElseThrow().id().equals(first)
                && !button(view, "Run a new preview").active);
        click(view, button(view, "Request cancellation"));
        step("native cancellation requested; provider held for drain control");
      }
      case 4 -> {
        var observed = operation().observedOperation().orElseThrow();
        if (!observed.cancellationRequested()) return false;
        check("cancellation request keeps the active worker charged", !observed.terminal());
        release.countDown();
        step("held provider released to its cooperative checkpoint");
      }
      case 5 -> {
        var view = operation();
        var observed = view.observedOperation().orElseThrow();
        if (!observed.terminal() || !button(view, "Run a new preview").active) return false;
        check(
            "cancelled operation has no accepted result",
            observed.state() == OperationSnapshot.State.CANCELLED && observed.result().isEmpty());
        click(view, button(view, "Run a new preview"));
        step("explicit retry captures the later draft");
      }
      case 6 -> {
        var view = operation();
        var observed = view.observedOperation().orElse(null);
        if (observed == null || !observed.terminal()) return false;
        var result = (WorldRecipe.PreviewResult<?>) observed.result().orElseThrow();
        check(
            "new operation retains exact draft identity and frozen compiled value",
            observed.state() == OperationSnapshot.State.COMPLETED
                && !observed.id().equals(first)
                && secondDraft.equals(result.draft())
                && result.value().equals(8)
                && calls.get() == 2);
        click(view, button(view, "Back"));
        edit("9");
        step("another draft edit after the successful preview");
      }
      case 7 -> {
        if (!ready("9")) return false;
        click(form, ownerAction(form, "Preview…"));
        var view = operation();
        check(
            "older result is named and narrated without rerunning",
            view.getNarrationMessage()
                    .getString()
                    .contains("This result describes an earlier draft")
                && view.getNarrationMessage().getString().contains("Compiled soil depth 8")
                && calls.get() == 2);
        geometry(view);
        capture.accept(
            "form-recipe-preview.png",
            () -> {
              click(view, button(view, "Back"));
              click(form, button(form, "Revert"));
            });
        step("older preview retained with its source and draft; revert requested");
      }
      case 8 -> {
        if (!ready("2") || actions.view().dirty()) return false;
        form.onClose();
        check(
            "preview compiled without modifying creation context or acquiring a world",
            client.screen == parent
                && parent.getUiState().getSettings() == original
                && client.level == null
                && actions.view().availability() == EditorActor.Availability.CLOSED);
        report.addProperty("status", "PASS");
        report.addProperty("analysisCalls", calls.get());
        return true;
      }
      default -> throw new AssertionError("Unexpected native preview stage " + stage);
    }
    return false;
  }

  JsonObject report() {
    return report;
  }
}
