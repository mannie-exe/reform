package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/** Native input over independent public definitions, without library-private editor access. */
final class PlatformCollectionChecks implements PlatformFormProbe.Scene {
  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "COLLECTION",
        driver -> new PlatformCollectionChecks(reopen, driver::progress, driver::capture));
  }

  public String diagnostic() {
    return "collection stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName();
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final boolean reopen;
  private final JsonObject report = new JsonObject();
  private final JsonArray checks = new JsonArray();
  private CompletableFuture<Void> preparation;
  private SettingsScreen<PlatformCollectionExample.Profile> form;
  private String original;
  private String beforePreview;
  private boolean previewsChecked;
  private Button retired;
  private long version;
  private List<java.util.UUID> blockIds;
  private int stage;

  PlatformCollectionChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture) {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    report.add("checks", checks);
    report.addProperty("status", "RUNNING");
    preparation = PlatformReferenceExample.prepareSources("collection-first").toCompletableFuture();
  }

  public JsonObject report() {
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
    return NativeScreens.current(client) == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private boolean popupReady() {
    return NativeScreens.current(client) != form
        && widgets(NativeScreens.current(client)).stream()
            .anyMatch(w -> w.getMessage().getString().equals("Back") && w.active);
  }

  private Button narrated(String prefix) {
    return widgets(NativeScreens.current(client)).stream()
        .filter(Button.class::isInstance)
        .map(Button.class::cast)
        .filter(button -> NativeNarrationProbe.read(button).startsWith(prefix))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing collection action: " + prefix));
  }

  private void press(Button button) {
    check(
        "collection control is in native focus tree", focus(NativeScreens.current(client), button));
    click(NativeScreens.current(client), button);
  }

  private void browse(String field) {
    returnToSettings(form);
    press(narrated(field + ": Choose values"));
  }

  private void search(String filter) {
    replace(
        NativeScreens.current(client),
        input(NativeScreens.current(client), "Search choices"),
        filter);
    press(button(NativeScreens.current(client), "Search choices"));
  }

  private void custom(String value) {
    replace(
        NativeScreens.current(client), input(NativeScreens.current(client), "Custom value"), value);
    press(button(NativeScreens.current(client), "Add custom"));
  }

  private void back() {
    press(button(NativeScreens.current(client), "Back"));
  }

  private boolean choicesReady() {
    return popupReady() && button(NativeScreens.current(client), "Add missing shown").active;
  }

  private String focusedNarration() {
    return NativeClientProbe.narration(
        output -> {
          for (var child : NativeScreens.current(client).children())
            if (child instanceof net.minecraft.client.gui.narration.NarratableEntry narrated)
              narrated.updateNarration(output.nest());
        });
  }

  private void preparePreviewChoices() {
    var limits = design.inherent.reform.reference.ReferenceLimits.STANDARD;
    var values =
        design.inherent.reform.value.ValueTypes.list(
                design.inherent.reform.value.ValueTypes.text(512), 64)
            .snapshotPolicy();
    PlatformReferenceExample.sources()
        .get(PlatformReferenceExample.BLOCKS)
        .bind(
            "collection-preview-controls",
            stamp ->
                new design.inherent.reform.reference.ReferenceIndex<>(
                    stamp,
                    design.inherent.reform.value.ValueTypes.text(512),
                    values,
                    limits,
                    List.of(
                        new design.inherent.reform.reference.ReferenceIndex.Entry<>(
                            "#fixture:empty",
                            List.of(),
                            "#fixture:empty",
                            "fixture:empty",
                            java.util.Map.of("kind", "tag", "matches", "0")),
                        new design.inherent.reform.reference.ReferenceIndex.Entry<>(
                            "#fixture:one",
                            List.of("minecraft:stone"),
                            "#fixture:one",
                            "fixture:one",
                            java.util.Map.of("kind", "tag", "matches", "1")),
                        new design.inherent.reform.reference.ReferenceIndex.Entry<>(
                            "#fixture:verbose",
                            List.of(),
                            "#fixture:verbose",
                            "fixture:verbose",
                            java.util.Map.of(
                                "detail",
                                "🙂".repeat(400),
                                "more",
                                "界".repeat(400),
                                "last",
                                "x".repeat(400))))));
  }

  @SuppressWarnings("unchecked")
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        form =
            (SettingsScreen<PlatformCollectionExample.Profile>)
                PlatformCollectionExample.screen(parent);
        NativeScreens.open(client, form);
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
          check(
              "clean collection close returns to parent", NativeScreens.current(client) == parent);
          report.addProperty("status", "PASS");
          return true;
        }
        original =
            Files.readString(
                client
                    .gameDirectory
                    .toPath()
                    .resolve("config/reform_platform_example/collections.json"));
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
        NativeClientProbe.press(retired);
        step("retired collection button invoked as negative control");
      }
      case 3 -> {
        check(
            "detached collection button cannot stage another edit",
            form.editorView().snapshot().orElseThrow().version() == version);
        back();
        press(ownerAction(form, "Undo"));
        step("collection edit undone through parent actor");
      }
      case 4 -> {
        if (!formReady() || values("tones").size() != 2) return false;
        check(
            "one undo restores the whole membership edit",
            values("tones").equals(List.of("CALM", "CALM")));
        press(ownerAction(form, "Redo"));
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
            widgets(NativeScreens.current(client)).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> NativeNarrationProbe.read(button).startsWith("false: Remove"))
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
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        search("oak");
        step("actual native block catalog searched");
      }
      case 9 -> {
        if (!choicesReady()
            || !button(NativeScreens.current(client), "Next choices").active
            || NativeScreens.current(client).width > 340) return false;
        report.addProperty("narrowWidth", NativeScreens.current(client).width);
        report.addProperty("narrowHeight", NativeScreens.current(client).height);
        geometry(NativeScreens.current(client));
        retired =
            widgets(NativeScreens.current(client)).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals("Add"))
                .findFirst()
                .orElseThrow();
        press(button(NativeScreens.current(client), "Next choices"));
        step("bounded registry continuation selected");
      }
      case 10 -> {
        if (!choicesReady()) return false;
        version = form.editorView().snapshot().orElseThrow().version();
        NativeClientProbe.press(retired);
        check(
            "previous catalog page callback cannot author current draft",
            form.editorView().snapshot().orElseThrow().version() == version);
        capture.accept(
            "form-collection-choices.png",
            () -> press(button(NativeScreens.current(client), "Add missing shown")));
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
        press(ownerAction(form, "Undo"));
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
        preparation =
            PlatformReferenceExample.prepareSources("collection-replaced").toCompletableFuture();
        step("actual source snapshot replaced while picker remains open", 22);
      }
      case 22 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        NativeClientProbe.press(retired);
        check(
            "old source callback cannot stage into unchanged document",
            form.editorView().snapshot().orElseThrow().version() == version);
        check(
            "obsolete source choices are visibly disabled",
            !narrated("minecraft:oak_planks: Add another").active);
        press(button(NativeScreens.current(client), "Refresh views"));
        step("source refresh explicitly requested", 23);
      }
      case 23 -> {
        if (!popupReady()
            || widgets(NativeScreens.current(client)).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .noneMatch(
                    button ->
                        button.active
                            && NativeNarrationProbe.read(button)
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
        geometry(NativeScreens.current(client));
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
                        .resolve("config/reform_platform_example/collections.json"))
                .equals(original));
        check("complete authored candidate remains valid", form.editorView().validation().valid());
        press(button(form, "Save and apply"));
        step("explicit parent Save requested");
      }
      case 20 -> {
        if (!formReady() || form.editorView().dirty()) return false;
        check(
            "explicit Save publishes actual local runtime value",
            PlatformCollectionExample.settings()
                .status()
                .effective()
                .orElseThrow()
                .value()
                .orElseThrow()
                .tones()
                .equals(
                    List.of(
                        PlatformCollectionExample.Tone.CALM,
                        PlatformCollectionExample.Tone.CALM,
                        PlatformCollectionExample.Tone.CAFE)));
        if (!previewsChecked) {
          beforePreview =
              Files.readString(
                  client
                      .gameDirectory
                      .toPath()
                      .resolve("config/reform_platform_example/collections.json"));
          preparePreviewChoices();
          browse("Blocks");
          search("fixture:");
          step("bounded owner preview choices requested", 30);
          return false;
        }
        check(
            "borrowed native source survives collection popup closures",
            PlatformReferenceExample.sources().values().stream()
                .allMatch(source -> source.isOpen()));
        press(button(form, "Done"));
        check("saved collection close returns to parent", NativeScreens.current(client) == parent);
        report.addProperty("status", "PASS");
        return true;
      }
      case 30 -> {
        if (!choicesReady()) return false;
        var add = narrated("#fixture:empty: Add");
        check("empty preview choice is focusable", focus(NativeScreens.current(client), add));
        check(
            "choice narration distinguishes resolved empty owner facts",
            focusedNarration().contains("matches: 0") && focusedNarration().contains("kind: tag"));
        var verbose = narrated("#fixture:verbose: Add");
        check("long preview choice is focusable", focus(NativeScreens.current(client), verbose));
        String spoken = focusedNarration();
        check(
            "bounded Unicode preview reports abbreviation without splitting a code point",
            spoken.contains("preview abbreviated")
                && spoken.codePoints().noneMatch(value -> value >= 0xd800 && value <= 0xdfff));
        check(
            "preview browsing alone leaves source bytes and draft unchanged",
            !form.editorView().dirty()
                && Files.readString(
                        client
                            .gameDirectory
                            .toPath()
                            .resolve("config/reform_platform_example/collections.json"))
                    .equals(beforePreview));
        check(
            "preview capture returns focus to the empty choice",
            focus(NativeScreens.current(client), add));
        capture.accept(
            "form-collection-previews.png", () -> press(narrated("#fixture:empty: Add")));
        step("empty preview choice staged for inspection", 31);
      }
      case 31 -> {
        if (!popupReady() || values("blocks").size() != 4) return false;
        press(narrated("#fixture:empty: Inspect"));
        step("actual empty-value inspection requested", 32);
      }
      case 32 -> {
        if (!NativeScreens.current(client).getNarrationMessage().getString().contains("matches: 0"))
          return false;
        check(
            "lookup narration retains empty count and owner kind",
            NativeScreens.current(client).getNarrationMessage().getString().contains("kind: tag"));
        capture.accept("form-collection-preview-empty.png", () -> search("fixture:one"));
        step("nonempty owner preview requested", 33);
      }
      case 33 -> {
        if (!choicesReady()) return false;
        var add = narrated("#fixture:one: Add");
        check("nonempty preview choice is focusable", focus(NativeScreens.current(client), add));
        check(
            "choice narration preserves nonempty owner count",
            focusedNarration().contains("matches: 1"));
        press(add);
        step("nonempty preview choice staged for inspection", 34);
      }
      case 34 -> {
        if (!popupReady() || values("blocks").size() != 5) return false;
        press(narrated("#fixture:one: Inspect"));
        step("actual nonempty-value inspection requested", 35);
      }
      case 35 -> {
        if (!NativeScreens.current(client).getNarrationMessage().getString().contains("matches: 1"))
          return false;
        check(
            "new inspection replaces the old count rather than combining observations",
            !NativeScreens.current(client)
                .getNarrationMessage()
                .getString()
                .contains("matches: 0"));
        capture.accept(
            "form-collection-preview-one.png",
            () -> {
              preparation =
                  PlatformReferenceExample.prepareSources("collection-preview-restored")
                      .toCompletableFuture();
            });
        step("preview source replaced through its owner", 36);
      }
      case 36 -> {
        if (!preparation.isDone()) return false;
        preparation.join();
        check(
            "retired source preview is not narrated as a current match count",
            !NativeScreens.current(client).getNarrationMessage().getString().contains("matches: "));
        back();
        step("preview-only edits returned for explicit Revert", 37);
      }
      case 37 -> {
        if (!formReady()) return false;
        press(ownerAction(form, "Revert"));
        step("preview trial explicitly reverted", 38);
      }
      case 38 -> {
        if (!formReady() || form.editorView().dirty()) return false;
        expected();
        check(
            "preview inspection/recovery never writes the saved profile",
            Files.readString(
                    client
                        .gameDirectory
                        .toPath()
                        .resolve("config/reform_platform_example/collections.json"))
                .equals(beforePreview));
        previewsChecked = true;
        step("preview trial restored original native source and saved draft", 20);
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
