package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.ClientEditingWorkflow;
import design.inherent.reform.minecraft.client.ClientFormOptions;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.ReferenceTitles;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.CommitReceipt;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormPresentation;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Opt-in title controls over the actual shared source, draft, browser and native raw input. */
final class PlatformReferenceTitleChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final JsonArray checks = new JsonArray();
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private CompletableFuture<?> pending =
      PlatformReferenceExample.prepareSources("titled-reference-check").toCompletableFuture();
  private ClientEditingWorkflow workflow;
  private ReferenceTitles titles;
  private Button originalChoice, retiredUse;
  private EditBox retiredRaw;
  private OperationHandle<?> save;
  private byte[] original;
  private int stage;
  private boolean complete;

  PlatformReferenceTitleChecks(Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.progress = progress;
    this.capture = capture;
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "RUNNING");
    result.add("checks", checks);
    return result;
  }

  private void check(String label, boolean valid) {
    if (!valid) throw new AssertionError(label);
    checks.add(label);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private boolean ready() {
    if (pending != null) {
      if (!pending.isDone()) return false;
      pending.join();
      pending = null;
    }
    var editor = workflow.editor(PlatformReferenceExample.DEFINITION.id());
    return !editor.busy() && editor.view().availability() == EditorActor.Availability.READY;
  }

  private String raw(String field) {
    return ((design.inherent.reform.edit.DraftNode.Scalar)
            workflow
                .editor(PlatformReferenceExample.DEFINITION.id())
                .view()
                .snapshot()
                .orElseThrow()
                .draft()
                .at(FieldPath.ROOT.field(field)))
        .raw();
  }

  private net.minecraft.client.gui.components.AbstractWidget field(String name) {
    return workflow
        .editor(PlatformReferenceExample.DEFINITION.id())
        .form()
        .fieldControl(FieldPath.ROOT.field(name))
        .orElseThrow();
  }

  private Button action(String field, String action) {
    return widgets(workflow.screen()).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(button -> NativeNarrationProbe.read(button).contains(field + ": " + action))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing reference action " + field + ": " + action));
  }

  private void press(Button button) {
    var screen = NativeScreens.current(client);
    check("reference control is reachable through native focus", focus(screen, button));
    click(screen, button);
  }

  private void edit(String name, String value) {
    var editor = workflow.editor(PlatformReferenceExample.DEFINITION.id());
    check(
        "raw reference field can be revealed",
        editor.form().focusField(FieldPath.ROOT.field(name)));
    replace(workflow.screen(), (EditBox) field(name), value);
  }

  private byte[] savedBytes() throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_platform_example/references.json"));
  }

  private void save() {
    var editor = workflow.editor(PlatformReferenceExample.DEFINITION.id());
    check("reference save has no retained native input", editor.canSubmit());
    save = editor.actions().commit(EntryPoint.GUI);
  }

  private boolean saved() {
    if (!save.snapshot().terminal()) return false;
    var commit = (EditorActor.Commit<?>) save.snapshot().result().orElseThrow();
    check(
        "reference persistence uses the actual owner receipt",
        commit.receipt().orElseThrow().persistence() == CommitReceipt.Persistence.SAVED);
    return ready();
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!pending.isDone()) return false;
        pending.join();
        original = savedBytes();
        var title = Component.literal("Default stone");
        titles =
            new ReferenceTitles(
                Map.of(
                    "stone",
                    title,
                    "minecraft:oak_planks",
                    Component.literal("Oak plank profile")));
        title.append(" mutated by caller");
        var options =
            new ClientFormOptions<PlatformReferenceExample.Profile>(
                    FormPresentation.EMPTY, PlatformReferenceExample.sources())
                .withReferenceTitles(PlatformReferenceExample.BLOCKS, titles)
                .withReferenceTitles(
                    PlatformReferenceExample.UNBOUND,
                    new ReferenceTitles(
                        Map.of("minecraft:manual", Component.literal("Manual profile"))));
        var form =
            PlatformReferenceExample.settings()
                .form(Component.literal("Titled references"), options);
        workflow =
            ClientEditingWorkflow.open(
                parent, Component.literal("Reference choices"), List.of(form));
        NativeScreens.open(client, workflow.screen());
        pending = workflow.openEditor(form.route()).toCompletableFuture();
        next("open owner-titled reference controls");
      }
      case 1 -> {
        if (!ready()) return false;
        originalChoice = (Button) field("block");
        check(
            "titled reference hides its raw ID and copies caller components",
            originalChoice.getMessage().getString().equals("Default stone")
                && !(field("block") instanceof EditBox));
        check(
            "the titled control narrates its friendly selection",
            NativeNarrationProbe.read(originalChoice).contains("Default stone"));
        check(
            "titled reference retains its ID tooltip after ordinary feedback",
            NativeNarrationProbe.read(originalChoice)
                .contains(PlatformReferenceExample.BLOCKS.toString()));
        check(
            "title control supports normal field focus",
            workflow
                .editor(PlatformReferenceExample.DEFINITION.id())
                .form()
                .focusField(FieldPath.ROOT.field("block")));
        try {
          titles.replace(Map.of("stone", Component.literal("x".repeat(1025))));
          throw new AssertionError("Oversized title was admitted");
        } catch (IllegalArgumentException expected) {
          check("oversized title publication refuses the complete replacement", true);
        }
        titles.replace(
            Map.of(
                "stone",
                Component.literal("Vanilla stone (Inherited)"),
                "minecraft:oak_planks",
                Component.literal("Oak plank profile")));
        next("replace only the bounded title cache");
      }
      case 2 -> {
        if (!originalChoice.getMessage().getString().equals("Vanilla stone (Inherited)"))
          return false;
        check(
            "cached title publication updates the same control without editing",
            field("block") == originalChoice
                && raw("block").equals("stone")
                && Arrays.equals(original, savedBytes()));
        stage = 3;
        capture.accept("form-workspace-reference-titles.png", () -> stage = 4);
      }
      case 3 -> {}
      case 4 -> {
        press((Button) field("block"));
        next("titled control opens the existing reference browser");
      }
      case 5 -> {
        var popup = NativeScreens.current(client);
        if (!popup.getClass().getSimpleName().equals("ReferenceScreen")) return false;
        replace(popup, input(popup, "Reference ID"), "minecraft:oak_planks");
        press(button(popup, "Inspect"));
        next("inspect a real registry choice through the shared browser");
      }
      case 6 -> {
        var use = button(NativeScreens.current(client), "Use selection");
        if (!use.active) return false;
        retiredUse = use;
        pending =
            PlatformReferenceExample.prepareSources("titled-reference-check").toCompletableFuture();
        next("replace the actual source with an equal revision label");
      }
      case 7 -> {
        if (!pending.isDone()) return false;
        pending.join();
        if (button(NativeScreens.current(client), "Use selection").active) return false;
        NativeClientProbe.pressRetired(retiredUse);
        check("titled browser retains source-nonce refusal", raw("block").equals("stone"));
        press(button(NativeScreens.current(client), "Refresh source"));
        next("explicitly refresh the same shared selection");
      }
      case 8 -> {
        var use = button(NativeScreens.current(client), "Use selection");
        if (!use.active) return false;
        press(use);
        next("stage the resolved choice without saving");
      }
      case 9 -> {
        if (NativeScreens.current(client) != workflow.screen()
            || !ready()
            || !raw("block").equals("minecraft:oak_planks")) return false;
        check(
            "resolved selection updates friendly title and retains unsaved bytes",
            field("block").getMessage().getString().equals("Oak plank profile")
                && Arrays.equals(original, savedBytes()));
        press(action("Block", "Edit ID"));
        edit("block", "??");
        next("retain malformed native ID input for explicit repair");
      }
      case 10 -> {
        if (!ready() || !raw("block").equals("??")) return false;
        check(
            "malformed reference input blocks actual submission",
            !workflow.editor(PlatformReferenceExample.DEFINITION.id()).view().validation().valid()
                && !workflow.editor(PlatformReferenceExample.DEFINITION.id()).canSubmit()
                && Arrays.equals(original, savedBytes()));
        press(action("Block", "Hide ID"));
        stage = 100;
        capture.accept("form-workspace-reference-invalid.png", () -> stage = 101);
      }
      case 100 -> {}
      case 101 -> {
        press(action("Block", "Edit ID"));
        edit("block", "minecraft:missing");
        stage = 102;
      }
      case 102 -> {
        if (!ready() || !raw("block").equals("minecraft:missing")) return false;
        check(
            "a missing reference remains a valid editable draft",
            workflow.editor(PlatformReferenceExample.DEFINITION.id()).view().validation().valid());
        save();
        progress.accept("save the explicit missing ID through the real owner");
        stage = 11;
      }
      case 11 -> {
        if (!saved()) return false;
        check(
            "missing ID persistence preserves its authored spelling",
            PlatformReferenceExample.settings()
                .runtime()
                .orElseThrow()
                .view()
                .value()
                .orElseThrow()
                .block()
                .equals("minecraft:missing"));
        retiredRaw = (EditBox) field("block");
        press(action("Block", "Hide ID"));
        check(
            "collapsing raw input exposes the title control, not its retired widget",
            field("block") instanceof Button
                && field("block") != retiredRaw
                && field("block").getMessage().getString().equals("ID: minecraft:missing"));
        retiredRaw.setValue("minecraft:dirt");
        check(
            "a retired raw callback cannot alter the collapsed reference",
            raw("block").equals("minecraft:missing"));
        check(
            "an unbound titled source keeps its explicit unavailable state",
            !field("unbound").active);
        press(action("Unbound", "Edit ID"));
        edit("unbound", "modpack:missing");
        next("repair an unbound source without inventing catalogue data");
      }
      case 12 -> {
        if (!ready() || !raw("unbound").equals("modpack:missing")) return false;
        check(
            "unbound raw input remains valid without source lookup",
            workflow.editor(PlatformReferenceExample.DEFINITION.id()).view().validation().valid());
        edit("unbound", "minecraft:manual");
        next("restore the unbound field before changing disclosure");
      }
      case 13 -> {
        if (!ready() || !raw("unbound").equals("minecraft:manual")) return false;
        press(action("Block", "Edit ID"));
        edit("block", "stone");
        next("restore authored references through native controls");
      }
      case 14 -> {
        if (!ready() || !raw("block").equals("stone") || !raw("unbound").equals("minecraft:manual"))
          return false;
        save();
        next("persist the original references without direct file writes");
      }
      case 15 -> {
        if (!saved()) return false;
        check(
            "title and raw controls restore exact source bytes",
            Arrays.equals(original, savedBytes()));
        workflow.screen().onClose();
        NativeClientProbe.pressRetired(originalChoice);
        check(
            "retired titled control cannot reopen the closed editor",
            !workflow.isOpen() && NativeScreens.current(client) == parent);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown titled reference stage " + stage);
    }
    return false;
  }
}
