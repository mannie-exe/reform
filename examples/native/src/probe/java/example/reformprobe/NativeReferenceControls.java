package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import example.reform.ReferenceExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

/** Real registry/resource/absence pickers through public addon factories and native input only. */
final class NativeReferenceControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final Screen parent;
  private final boolean reopen;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private CompletableFuture<Void> preparation;
  private SettingsScreen<ReferenceExample.Profile> form;
  private EditBox blockWidget;
  private Button retiredBrowse;
  private String original;
  private int stage;

  NativeReferenceControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    parent = client.screen;
    report.addProperty("status", "RUNNING");
    report.add("checks", checks);
    preparation = ReferenceExample.prepareSources("same-label").toCompletableFuture();
  }

  JsonObject report() {
    return report;
  }

  private void check(String name, boolean value) {
    if (!value) throw new AssertionError(name);
    checks.add(name);
  }

  private void step(String name) {
    stage++;
    progress.accept(name);
  }

  @SuppressWarnings("unchecked")
  private void open() {
    form = (SettingsScreen<ReferenceExample.Profile>) ReferenceExample.screen(parent);
    client.setScreen(form);
  }

  private boolean formReady() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private String raw(String name) {
    return ((DraftNode.Scalar)
            form.editorView().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(name)))
        .raw();
  }

  private EditBox field(String name) {
    return (EditBox) form.fieldControl(FieldPath.ROOT.field(name)).orElseThrow();
  }

  private Path path() {
    return client.gameDirectory.toPath().resolve("config/reform_native_example/references.json");
  }

  private Button narrated(String text) {
    return widgets(client.screen).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(widget -> NativeStructuredControls.narration(widget).contains(text))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing action: " + text));
  }

  private void press(Button button) {
    check("reference action is in native focus tree", focus(client.screen, button));
    click(client.screen, button);
  }

  private void browse(String name) {
    press(narrated(name + ": Browse"));
  }

  private void type(String label, String value) {
    replace(client.screen, input(client.screen, label), value);
  }

  private boolean popupReady() {
    return client.screen != form
        && widgets(client.screen).stream()
            .anyMatch(w -> w.getMessage().getString().equals("Use selection"));
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        original = Files.readString(path());
        open();
        step("native reference sources captured and compiled");
      }
      case 1 -> {
        if (!formReady()) return false;
        if (reopen) {
          saved();
          check("reference profile reopens clean", !form.editorView().dirty());
          form.onClose();
          check("clean reference close restores parent", client.screen == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        check("default namespace spelling is preserved", field("block").getValue().equals("stone"));
        check(
            "unbound source has disabled explicit state",
            !narrated("Unbound: Source unavailable").active);
        check(
            "unbound authored key is preserved",
            field("unbound").getValue().equals("minecraft:manual"));
        blockWidget = field("block");
        retiredBrowse = narrated("Block: Browse");
        browse("Block");
        step("native block picker opened");
      }
      case 2 -> {
        if (!popupReady() || !button(client.screen, "Use selection").active) return false;
        var popup = client.screen;
        retiredBrowse.onPress();
        check(
            "inactive parent Browse callback cannot replace picker or close editor",
            client.screen == popup
                && form.editorView().availability() == EditorActor.Availability.READY);
        check(
            "native registry lookup retains authored default namespace",
            input(client.screen, "Reference ID").getValue().equals("stone"));
        type("Search", "oak");
        press(button(client.screen, "Search"));
        step("native block source searched");
      }
      case 3 -> {
        if (widgets(client.screen).stream()
            .noneMatch(w -> w instanceof Button && w.getMessage().getString().equals("Next page")))
          return false;
        check(
            "native search exposes bounded continuation",
            button(client.screen, "Next page").active);
        type("Search", "spruce");
        check(
            "edited filter cannot reuse old continuation",
            !button(client.screen, "Next page").active);
        press(button(client.screen, "Search"));
        step("native filter changed explicitly");
      }
      case 4 -> {
        if (widgets(client.screen).stream()
            .noneMatch(
                w ->
                    w instanceof Button
                        && w.getMessage().getString().equals("Next page")
                        && w.active)) return false;
        press(button(client.screen, "Next page"));
        type("Reference ID", "Bad ID");
        press(button(client.screen, "Inspect"));
        step("native malformed key inspected");
      }
      case 5 -> {
        if (!client.screen.getNarrationMessage().getString().contains("malformed")) return false;
        check(
            "native malformed response is not selectable",
            !button(client.screen, "Use selection").active);
        check("malformed lookup does not change draft", raw("block").equals("stone"));
        type("Reference ID", "minecraft:missing_block");
        press(button(client.screen, "Inspect"));
        step("native unknown key inspected");
      }
      case 6 -> {
        if (!client.screen.getNarrationMessage().getString().contains("unknown reference"))
          return false;
        check(
            "native unknown response is not selectable",
            !button(client.screen, "Use selection").active);
        preparation = ReferenceExample.prepareSources("same-label").toCompletableFuture();
        step("actual source rebound with equal revision label");
      }
      case 7 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        check("obsolete source disables selection", !button(client.screen, "Use selection").active);
        press(button(client.screen, "Refresh source"));
        step("native stale picker refreshed");
      }
      case 8 -> {
        if (!popupReady()) return false;
        type("Reference ID", "minecraft:polished_blackstone_brick_stairs");
        press(button(client.screen, "Inspect"));
        step("native source key beyond field bound inspected");
      }
      case 9 -> {
        if (!button(client.screen, "Use selection").active) return false;
        check("lookup and rebind do not write", Files.readString(path()).equals(original));
        capture.accept(
            "form-reference-block.png", () -> press(button(client.screen, "Use selection")));
        step("native block choice staged explicitly");
      }
      case 10 -> {
        if (!formReady() || !raw("block").equals("minecraft:polished_blackstone_brick_stairs"))
          return false;
        check(
            "source choice beyond field bound is retained without native truncation",
            field("block").getValue().equals(raw("block"))
                && !form.editorView().validation().valid());
        check("invalid choice leaves file unchanged", Files.readString(path()).equals(original));
        browse("Block");
        step("invalid source choice reopened for repair");
      }
      case 11 -> {
        if (!popupReady()) return false;
        type("Reference ID", "minecraft:oak_planks");
        press(button(client.screen, "Inspect"));
        step("native valid replacement inspected");
      }
      case 12 -> {
        if (!button(client.screen, "Use selection").active) return false;
        press(button(client.screen, "Use selection"));
        step("native invalid reference draft repaired");
      }
      case 13 -> {
        if (!formReady() || !raw("block").equals("minecraft:oak_planks")) return false;
        check("returning from picker preserves scalar widget", field("block") == blockWidget);
        check(
            "native choice changes draft only",
            Files.readString(path()).equals(original)
                && ReferenceExample.settings()
                    .runtime()
                    .orElseThrow()
                    .view()
                    .value()
                    .orElseThrow()
                    .block()
                    .equals("stone"));
        browse("Resource");
        step("native resource picker opened");
      }
      case 14 -> {
        if (!popupReady()) return false;
        type("Reference ID", "minecraft:lang/en_us.json");
        press(button(client.screen, "Inspect"));
        step("actual admitted client resource inspected");
      }
      case 15 -> {
        if (!button(client.screen, "Use selection").active) return false;
        press(button(client.screen, "Use selection"));
        step("native resource choice staged");
      }
      case 16 -> {
        if (!formReady() || !raw("resource").equals("minecraft:lang/en_us.json")) return false;
        browse("Optional");
        step("native missing optional source opened");
      }
      case 17 -> {
        if (!popupReady()
            || !client.screen.getNarrationMessage().getString().contains("missing dependency"))
          return false;
        check(
            "missing dependency is not selectable", !button(client.screen, "Use selection").active);
        capture.accept("form-reference-missing.png", () -> press(button(client.screen, "Back")));
        step("native missing dependency retained without fallback");
      }
      case 18 -> {
        if (!formReady()) return false;
        check(
            "closing picker leaves borrowed source open",
            ReferenceExample.sources().get(ReferenceExample.OPTIONAL).isOpen());
        check(
            "missing and unbound inputs remain unchanged",
            raw("optional").equals("optional:missing")
                && raw("unbound").equals("minecraft:manual"));
        check("all source choices remain unsaved", Files.readString(path()).equals(original));
        press(button(form, "Save and apply"));
        step("native reference profile saved explicitly");
      }
      case 19 -> {
        if (!formReady() || form.editorView().dirty() || form.editorView().lastReceipt().isEmpty())
          return false;
        saved();
        check(
            "native reference save changes actual file",
            !Files.readString(path()).equals(original));
        form.onClose();
        check(
            "native reference close releases actor",
            client.screen == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED);
        open();
        step("separate editor opened for teardown control");
      }
      case 20 -> {
        if (!formReady()) return false;
        browse("Block");
        step("native auxiliary teardown control opened");
      }
      case 21 -> {
        if (!popupReady()) return false;
        var retiredBack = button(client.screen, "Back");
        client.setScreen(parent);
        retiredBack.onPress();
        check("retired picker Back callback cannot reopen editor", client.screen == parent);
        check(
            "unexpected picker removal closes borrowed editor",
            form.editorView().availability() == EditorActor.Availability.CLOSED);
        check(
            "unexpected removal does not close source owner",
            ReferenceExample.sources().get(ReferenceExample.BLOCKS).isOpen());
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unknown reference stage " + stage);
    }
    return false;
  }

  private void saved() {
    var value = ReferenceExample.settings().runtime().orElseThrow().view().value().orElseThrow();
    check(
        "native reference runtime matches saved authored keys",
        value.equals(
            new ReferenceExample.Profile(
                "minecraft:oak_planks",
                "minecraft:lang/en_us.json",
                "optional:missing",
                "minecraft:manual")));
  }
}
