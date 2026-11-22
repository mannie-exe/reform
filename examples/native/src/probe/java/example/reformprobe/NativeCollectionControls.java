package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import example.reform.CollectionExample;
import example.reform.ReferenceExample;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Native input over independent public definitions, without library-private editor access. */
final class NativeCollectionControls {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final boolean reopen;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private CompletableFuture<Void> preparation;
  private SettingsScreen<CollectionExample.Profile> form;
  private String original;
  private Button retired;
  private long version;
  private List<java.util.UUID> blockIds;
  private int stage;

  NativeCollectionControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    report.add("checks", checks);
    report.addProperty("status", "RUNNING");
    preparation = ReferenceExample.prepareSources("collection-first").toCompletableFuture();
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

  private void step(String name, int target) {
    stage = target;
    progress.accept(name);
  }

  private List<DraftNode.Row> rows(String field) {
    return ((DraftNode.Sequence)
            form.editorView().snapshot().orElseThrow().draft().at(FieldPath.ROOT.field(field)))
        .rows();
  }

  private List<String> values(String field) {
    return rows(field).stream().map(row -> ((DraftNode.Scalar) row.value()).raw()).toList();
  }

  private boolean formReady() {
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private boolean popupReady() {
    return client.screen != form
        && widgets(client.screen).stream()
            .anyMatch(w -> w.getMessage().getString().equals("Back") && w.active);
  }

  private Button narrated(String prefix) {
    return widgets(client.screen).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(button -> NativeStructuredControls.narration(button).startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing collection action: " + prefix));
  }

  private void press(Button button) {
    check("collection control is in native focus tree", focus(client.screen, button));
    click(client.screen, button);
  }

  private void browse(String field) {
    press(narrated(field + ": Choose values"));
  }

  private void search(String filter) {
    replace(client.screen, input(client.screen, "Search choices"), filter);
    press(button(client.screen, "Search choices"));
  }

  private void custom(String value) {
    replace(client.screen, input(client.screen, "Custom value"), value);
    press(button(client.screen, "Add custom"));
  }

  private void back() {
    press(button(client.screen, "Back"));
  }

  private boolean choicesReady() {
    return popupReady() && button(client.screen, "Add missing shown").active;
  }

  @SuppressWarnings("unchecked")
  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        form = (SettingsScreen<CollectionExample.Profile>) CollectionExample.screen(parent);
        client.setScreen(form);
        step("public collection form opened");
      }
      case 1 -> {
        if (!formReady()) return false;
        if (reopen) {
          expected();
          check(
              "second JVM collection owner is clean and valid",
              !form.editorView().dirty() && form.editorView().validation().valid());
          form.onClose();
          check("clean collection close returns to parent", client.screen == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        original =
            Files.readString(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_native_example/collections.json"));
        check(
            "list starts with retained duplicate values",
            values("tones").equals(List.of("CALM", "CALM")));
        browse("Tones");
        search("cafe");
        check(
            "localized diacritic search exposes the exact enum choice",
            narrated("CAFÉ: Add").active);
        retired = narrated("CAFÉ: Add");
        press(retired);
        step("localized enum choice staged");
      }
      case 2 -> {
        if (!popupReady() || values("tones").size() != 3) return false;
        check(
            "enum choice appends while preserving list duplicates",
            values("tones").equals(List.of("CALM", "CALM", "CAFE")));
        version = form.editorView().snapshot().orElseThrow().version();
        retired.onPress();
        step("retired collection button invoked as negative control");
      }
      case 3 -> {
        check(
            "detached collection button cannot stage another edit",
            form.editorView().snapshot().orElseThrow().version() == version);
        back();
        press(button(form, "Undo"));
        step("collection edit undone through parent actor");
      }
      case 4 -> {
        if (!formReady() || values("tones").size() != 2) return false;
        check(
            "one undo restores the whole membership edit",
            values("tones").equals(List.of("CALM", "CALM")));
        press(button(form, "Redo"));
        step("collection membership redo requested");
      }
      case 5 -> {
        if (!formReady() || values("tones").size() != 3) return false;
        check(
            "parent Redo restores the complete selected list",
            values("tones").equals(List.of("CALM", "CALM", "CAFE")));
        browse("Flags");
        press(narrated("true: Add"));
        step("boolean set choice staged");
      }
      case 6 -> {
        if (!popupReady() || values("flags").size() != 2) return false;
        check(
            "included set choice cannot be added again from catalog",
            !narrated("true: Add another").active);
        custom("false");
        step("duplicate set draft authored explicitly");
      }
      case 7 -> {
        if (!popupReady() || values("flags").size() != 3) return false;
        check(
            "duplicate set entries remain visible and invalid",
            !form.editorView().validation().valid());
        var duplicates =
            widgets(client.screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(
                    button ->
                        NativeStructuredControls.narration(button).startsWith("false: Remove"))
                .toList();
        check("both duplicate rows have separate remove controls", duplicates.size() == 2);
        press(duplicates.getLast());
        step("duplicate set row repaired by exact identity");
      }
      case 8 -> {
        if (!popupReady() || values("flags").size() != 2) return false;
        check(
            "set repair restores complete owner validation",
            form.editorView().validation().valid());
        back();
        blockIds = rows("blocks").stream().map(DraftNode.Row::id).toList();
        browse("Blocks");
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        search("oak");
        step("actual native block catalog searched");
      }
      case 9 -> {
        if (!choicesReady()
            || !button(client.screen, "Next choices").active
            || client.screen.width > 340) return false;
        report.addProperty("narrowWidth", client.screen.width);
        report.addProperty("narrowHeight", client.screen.height);
        geometry(client.screen);
        retired =
            widgets(client.screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals("Add"))
                .findFirst()
                .orElseThrow();
        press(button(client.screen, "Next choices"));
        step("bounded registry continuation selected");
      }
      case 10 -> {
        if (!choicesReady()) return false;
        version = form.editorView().snapshot().orElseThrow().version();
        retired.onPress();
        check(
            "previous catalog page callback cannot author current draft",
            form.editorView().snapshot().orElseThrow().version() == version);
        capture.accept(
            "form-collection-choices.png", () -> press(button(client.screen, "Add missing shown")));
        step("visible registry page staged as one batch");
      }
      case 11 -> {
        if (!popupReady() || values("blocks").size() != 10) return false;
        check("bulk add affects exactly eight visible choices", values("blocks").size() == 10);
        check(
            "bulk selection preserves unknown text and existing row identities",
            values("blocks").getFirst().equals("future:unknown")
                && rows("blocks").subList(0, 2).stream()
                    .map(DraftNode.Row::id)
                    .toList()
                    .equals(blockIds));
        back();
        press(button(form, "Undo"));
        step("whole page batch undone");
      }
      case 12 -> {
        if (!formReady() || values("blocks").size() != 2) return false;
        check(
            "one undo restores all pre-batch row identities",
            rows("blocks").stream().map(DraftNode.Row::id).toList().equals(blockIds));
        browse("Blocks");
        search("minecraft:oak_planks");
        step("single native block requested");
      }
      case 13 -> {
        if (!choicesReady()) return false;
        press(narrated("minecraft:oak_planks: Add"));
        step("single native block staged");
      }
      case 14 -> {
        if (!popupReady() || values("blocks").size() != 3) return false;
        retired = narrated("minecraft:oak_planks: Add another");
        version = form.editorView().snapshot().orElseThrow().version();
        preparation = ReferenceExample.prepareSources("collection-replaced").toCompletableFuture();
        step("actual source snapshot replaced while picker remains open", 22);
      }
      case 22 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        retired.onPress();
        check(
            "old source callback cannot stage into unchanged document",
            form.editorView().snapshot().orElseThrow().version() == version);
        check(
            "obsolete source choices are visibly disabled",
            !narrated("minecraft:oak_planks: Add another").active);
        press(button(client.screen, "Refresh views"));
        step("source refresh explicitly requested", 23);
      }
      case 23 -> {
        if (!popupReady()
            || widgets(client.screen).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .noneMatch(
                    button ->
                        button.active
                            && NativeStructuredControls.narration(button)
                                .startsWith("minecraft:oak_planks: Add another"))) return false;
        check(
            "source refresh retains authored collection and row identities",
            values("blocks")
                    .equals(List.of("future:unknown", "minecraft:stone", "minecraft:oak_planks"))
                && rows("blocks").subList(0, 2).stream()
                    .map(DraftNode.Row::id)
                    .toList()
                    .equals(blockIds));
        back();
        browse("Resources");
        search("minecraft:lang/en_us.json");
        step("actual native resource requested", 15);
      }
      case 15 -> {
        if (!choicesReady()) return false;
        press(narrated("minecraft:lang/en_us.json: Add"));
        step("actual native resource staged");
      }
      case 16 -> {
        if (!popupReady() || values("resources").size() != 2) return false;
        back();
        browse("Optional");
        custom("optional:future");
        step("missing optional source retains manual authoring");
      }
      case 17 -> {
        if (!popupReady() || values("optional").size() != 2) return false;
        check(
            "missing dependency keeps original and manual entries",
            values("optional").equals(List.of("optional:missing", "optional:future")));
        back();
        browse("Unbound");
        custom("minecraft:future");
        step("unbound source retains manual authoring");
      }
      case 18 -> {
        if (!popupReady() || values("unbound").size() != 2) return false;
        check(
            "unbound source keeps original and manual entries",
            values("unbound").equals(List.of("minecraft:manual", "minecraft:future")));
        geometry(client.screen);
        capture.accept("form-collection-unbound.png", this::back);
        step("collection draft reviewed before Save");
      }
      case 19 -> {
        if (!formReady()) return false;
        geometry(form);
        var presentation = new JsonArray();
        for (String field :
            List.of("tones", "flags", "blocks", "resources", "optional", "unbound")) {
          for (var row : rows(field)) {
            var path = FieldPath.ROOT.field(field).row(row.id());
            var widget = form.fieldControl(path).orElseThrow();
            var observed = new JsonObject();
            observed.addProperty("field", field);
            observed.addProperty("row", row.id().toString());
            observed.addProperty("draft", ((DraftNode.Scalar) row.value()).raw());
            observed.addProperty(
                "display",
                widget instanceof net.minecraft.client.gui.components.EditBox input
                    ? input.getValue()
                    : widget instanceof net.minecraft.client.gui.components.CycleButton<?> cycle
                        ? String.valueOf(cycle.getValue())
                        : widget.getMessage().getString());
            presentation.add(observed);
          }
        }
        report.add("beforeSaveFields", presentation);
        report.addProperty(
            "beforeSaveButtons",
            widgets(form).stream()
                .filter(Button.class::isInstance)
                .map(w -> w.getMessage().getString())
                .toList()
                .toString());
        expected();
        check(
            "all selection changes leave file unchanged before Save",
            Files.readString(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_native_example/collections.json"))
                .equals(original));
        check("complete authored candidate remains valid", form.editorView().validation().valid());
        press(button(form, "Save and apply"));
        step("explicit parent Save requested");
      }
      case 20 -> {
        if (!formReady() || form.editorView().dirty()) return false;
        check(
            "explicit Save publishes actual local runtime value",
            CollectionExample.settings()
                .status()
                .effective()
                .orElseThrow()
                .value()
                .orElseThrow()
                .tones()
                .equals(
                    List.of(
                        CollectionExample.Tone.CALM,
                        CollectionExample.Tone.CALM,
                        CollectionExample.Tone.CAFE)));
        check(
            "borrowed native source survives collection popup closures",
            ReferenceExample.sources().values().stream().allMatch(source -> source.isOpen()));
        press(button(form, "Done"));
        check("saved collection close returns to parent", client.screen == parent);
        report.addProperty("status", "PASS");
        return true;
      }
      default -> throw new AssertionError("Unexpected collection stage " + stage);
    }
    return false;
  }

  private void expected() {
    check(
        "saved enum list retains duplicates and selected exact key",
        values("tones").equals(List.of("CALM", "CALM", "CAFE")));
    check(
        "saved boolean set retains both members",
        Set.copyOf(values("flags")).equals(Set.of("false", "true")));
    check(
        "saved registry list retains unknown original and selected native block",
        values("blocks")
            .equals(List.of("future:unknown", "minecraft:stone", "minecraft:oak_planks")));
    check(
        "saved resource set retains unknown original and selected resource",
        Set.copyOf(values("resources"))
            .equals(Set.of("minecraft:missing_resource", "minecraft:lang/en_us.json")));
    check(
        "saved missing dependency values remain authored",
        values("optional").equals(List.of("optional:missing", "optional:future")));
    check(
        "saved unbound values remain authored",
        values("unbound").equals(List.of("minecraft:manual", "minecraft:future")));
  }
}
