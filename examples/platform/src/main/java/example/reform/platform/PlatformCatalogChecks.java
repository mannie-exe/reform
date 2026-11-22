package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.ImportScreen;
import design.inherent.reform.minecraft.client.NativeScreens;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormPresentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

/** Actual native catalog/input lifecycle over the ordinary public JSON factory and shared draft. */
final class PlatformCatalogChecks implements PlatformFormProbe.Scene {
  static java.util.concurrent.CompletionStage<JsonObject> run(
      ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "CATALOG",
        driver -> new PlatformCatalogChecks(reopen, driver::progress, driver::capture));
  }

  public String diagnostic() {
    return "catalog stage="
        + stage
        + ", screen="
        + NativeScreens.current(client).getClass().getSimpleName();
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final SettingsScreen<PlatformCatalogExample.Profiles> form;
  private final JsonArray checks = new JsonArray();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final String original;
  private SettingsScreen<PlatformCatalogExample.Profiles> limited;
  private String savedSource;
  private FieldPath first, second, role, weight, delay;
  private EditBox retiredName, narrowWeight;
  private Button retiredRemove, retiredStep;
  private CompletableFuture<Void> resourceReload;
  private Language previousLanguage;
  private String originalLanguage;
  private boolean originalUnicode;
  private long version;
  private int stage;
  private boolean complete;

  PlatformCatalogChecks(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture)
      throws Exception {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    original = Files.readString(path());
    System.out.println("REFORM_CATALOG_PARENT " + focusDescription(parent));
    form = editor("Public profile catalog", PlatformCatalogExample.PRESENTATION);
    NativeScreens.open(client, form);
  }

  @SuppressWarnings("unchecked") // Ready ordinary factory uses this exact public definition.
  private SettingsScreen<PlatformCatalogExample.Profiles> editor(
      String title, FormPresentation presentation) {
    return (SettingsScreen<PlatformCatalogExample.Profiles>)
        PlatformCatalogExample.settings().screen(parent, Component.literal(title), presentation);
  }

  private Path path() {
    return client.gameDirectory.toPath().resolve("config/reform_platform_example/catalog.json");
  }

  private void check(String name, boolean valid) {
    if (!valid) throw new AssertionError(name);
    checks.add(name);
  }

  private void next(String label) {
    stage++;
    progress.accept(label);
  }

  private boolean ready() {
    return NativeScreens.current(client) == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && form.editorView().snapshot().isPresent()
        && button(form, "Done").active;
  }

  private long version() {
    return form.editorView().snapshot().orElseThrow().version();
  }

  private DraftNode node(FieldPath path) {
    return form.editorView().snapshot().orElseThrow().draft().at(path);
  }

  private String raw(FieldPath path) {
    return ((DraftNode.Scalar) node(path)).raw();
  }

  private EditBox field(FieldPath path) {
    return (EditBox) form.fieldControl(path).orElseThrow();
  }

  private EditBox search() {
    return input(
        form,
        Component.translatableWithFallback("reform.workspace.search", "Search settings")
            .getString());
  }

  private CycleButton<?> units() {
    return widgets(form).stream()
        .filter(CycleButton.class::isInstance)
        .map(CycleButton.class::cast)
        .filter(value -> value.getValue().equals("ticks") || value.getValue().equals("seconds"))
        .findFirst()
        .orElseThrow();
  }

  private void press(Button button) {
    check(
        "native catalog control participates in focus",
        focus(NativeScreens.current(client), button));
    click(NativeScreens.current(client), button);
    var current = NativeScreens.current(client);
    if (current.getFocused() != null && !current.children().contains(current.getFocused()))
      throw new AssertionError(
          "native callback leaves focus in the current tree: " + focusDescription(current));
    check("native callback leaves focus in the current tree", true);
  }

  private static String focusDescription(Screen screen) {
    var focused = screen.getFocused();
    return "screen="
        + screen.getClass().getSimpleName()
        + ", focused="
        + (focused == null ? "none" : focused.getClass().getSimpleName())
        + ", label="
        + (focused instanceof net.minecraft.client.gui.components.AbstractWidget widget
            ? widget.getMessage().getString()
            : "")
        + ", currentChild="
        + screen.children().contains(focused);
  }

  private void select(FieldPath path) {
    check("public section navigation uses the existing editor", form.selectSection(path));
    check(
        "selection retains actual structural identity",
        form.selectedSection().filter(path::equals).isPresent());
  }

  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!ready()) return false;
        var rows = ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows();
        first = PlatformCatalogExample.PROFILES.row(rows.getFirst().id());
        second = PlatformCatalogExample.PROFILES.row(rows.get(1).id());
        var roles = first.field("roles");
        role = roles.row(((DraftNode.Mapping) node(roles)).rows().getFirst().id());
        weight = role.append(FieldPath.Part.VALUE).field("weight");
        delay = role.append(FieldPath.Part.VALUE).field("delay");
        if (reopen) {
          check(
              "saved profile and role reopen without editor row IDs in storage",
              raw(first.field("name")).equals("Alpha revised")
                  && raw(weight).equals("7")
                  && raw(delay).equals("40"));
          select(role);
          check(
              "display unit preference is local to its editing lifetime",
              units().getValue().equals("ticks"));
          check("reopen has no unsaved edits", !form.editorView().dirty());
          press(button(form, "Done"));
          check(
              "reopened native form closes its editor",
              form.editorView().availability() == EditorActor.Availability.CLOSED);
          check(
              "viewing existing source does not rewrite it",
              Files.readString(path()).equals(original));
          complete = true;
          return true;
        }
        press(navigation(form, "Profiles"));
        press(navigation(form, "Land"));
        check(
            "native catalog click selects the intended profile",
            form.selectedSection().filter(first::equals).isPresent());
        check(
            "wide catalog exposes the focused profile fields",
            form.fieldControl(first.field("name")).isPresent());
        check(
            "focused detail does not expose another profile's controls",
            form.fieldControl(second.field("name")).isEmpty());
        check(
            "nested detail narration retains declared section help",
            form.getNarrationMessage().getString().contains("Authored profiles and their roles"));
        capture.accept(
            "form-catalog-wide.png",
            () -> {
              replace(form, field(first.field("name")), "Alpha revised");
              next("catalog profile edit entered");
            });
      }
      case 1 -> {
        if (!ready() || !raw(first.field("name")).equals("Alpha revised")) return false;
        retiredName = field(first.field("name"));
        retiredName.setCursorPosition(2);
        retiredName.setHighlightPos(5);
        version = version();
        replace(form, search(), "Marine");
        check(
            "filtering preserves the active editor widget and selection",
            field(first.field("name")) == retiredName
                && retiredName.getCursorPosition() == 2
                && retiredName.getHighlighted().equals("pha"));
        replace(form, search(), "no matching entry");
        check(
            "zero matches retain selection and actual draft",
            version() == version && form.selectedSection().filter(first::equals).isPresent());
        check(
            "Escape clears the focused filter without closing",
            NativeClientProbe.key(form, "key.keyboard.escape", true)
                && NativeScreens.current(client) == form
                && search().getValue().isEmpty());
        replace(form, search(), "minimum threshold");
        press(navigation(form, "Lower global limit"));
        var minimum = PlatformCatalogExample.GLOBAL.field("minimum");
        check(
            "localized scalar help reveals its real control in the existing section",
            form.selectedSection().filter(PlatformCatalogExample.GLOBAL::equals).isPresent()
                && field(minimum).isFocused()
                && version() == version
                && raw(first.field("name")).equals("Alpha revised"));
        replace(form, search(), "");
        select(second);
        retiredName.setValue("retired callback");
        check(
            "retired text admits no hidden edit",
            button(form, "Done").active && version() == version);
        next("catalog switches retain raw input");
      }
      case 2 -> {
        if (!ready()) return false;
        check(
            "retired text never changed the original row",
            raw(first.field("name")).equals("Alpha revised"));
        select(first);
        check(
            "return restores caret and directional selection",
            field(first.field("name")) != retiredName
                && field(first.field("name")).getCursorPosition() == 2
                && field(first.field("name")).getHighlighted().equals("pha"));
        retiredName.setValue("stale callback after return");
        check(
            "returned fields do not reactivate a retired text callback",
            version() == version && field(first.field("name")).getValue().equals("Alpha revised"));
        retiredRemove = button(form, "Remove");
        select(second);
        version = version();
        NativeClientProbe.press(retiredRemove);
        check(
            "retired structural callback admits no edit",
            button(form, "Done").active && version() == version);
        select(first);
        press(button(form, "Down"));
        next("catalog row reorder requested");
      }
      case 3 -> {
        if (!ready()) return false;
        var rows = ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows();
        if (!rows.get(1).id().equals(((FieldPath.Row) first.segments().getLast()).id()))
          return false;
        check(
            "reordering retains selected UUID and authored text",
            form.selectedSection().filter(first::equals).isPresent()
                && raw(first.field("name")).equals("Alpha revised"));
        press(ownerAction(form, "Undo"));
        next("catalog reorder undo requested");
      }
      case 4 -> {
        if (!ready()) return false;
        var rows = ((DraftNode.Sequence) node(PlatformCatalogExample.PROFILES)).rows();
        if (!rows.getFirst().id().equals(((FieldPath.Row) first.segments().getLast()).id()))
          return false;
        select(role);
        var unit = units();
        for (int i = 0; i < 3 && !unit.getValue().equals("seconds"); i++)
          NativeClientProbe.press(unit);
        check(
            "nested numeric unit switches are view-only",
            unit.getValue().equals("seconds") && field(delay).getValue().equals("2"));
        version = version();
        retiredStep = button(form, "+");
        select(second);
        select(role);
        check(
            "return retains numeric display preference without a draft edit",
            units().getValue().equals("seconds")
                && field(delay).getValue().equals("2")
                && version() == version);
        NativeClientProbe.press(retiredStep);
        check(
            "retired numeric step admits no edit",
            button(form, "Done").active && version() == version && raw(delay).equals("40"));
        replace(form, field(weight), "-");
        next("invalid nested raw input entered");
      }
      case 5 -> {
        if (!ready() || !raw(weight).equals("-") || form.editorView().validation().valid())
          return false;
        select(second);
        check(
            "hidden invalid field still blocks complete Save",
            !button(form, "Save and apply").active && raw(weight).equals("-"));
        check(
            "related field navigation reaches the nested entry",
            form.focusField(weight)
                && form.selectedSection().filter(role::equals).isPresent()
                && field(weight).getValue().equals("-"));
        replace(form, field(weight), "7");
        next("nested invalid field repaired");
      }
      case 6 -> {
        if (!ready() || !form.editorView().validation().valid() || !raw(weight).equals("7"))
          return false;
        select(PlatformCatalogExample.EXTRA);
        check(
            "inactive section retains no invented input",
            form.fieldControl(PlatformCatalogExample.EXTRA.field("minimum")).isEmpty()
                && !((DraftNode.OptionalValue) node(FieldPath.ROOT.field("extra"))).present());
        check(
            "inactive state is included in screen narration",
            form.getNarrationMessage().getString().contains("inactive"));
        press(ownerAction(form, "Import…"));
        check(
            "catalog opens the shared import workflow",
            NativeScreens.current(client) instanceof ImportScreen<?>);
        press(button(NativeScreens.current(client), "Back"));
        check(
            "auxiliary return keeps the same catalog and raw draft",
            NativeScreens.current(client) == form
                && form.selectedSection().filter(PlatformCatalogExample.EXTRA::equals).isPresent()
                && raw(first.field("name")).equals("Alpha revised")
                && raw(weight).equals("7"));
        check(
            "all navigation and edits leave source untouched before Save",
            Files.readString(path()).equals(original));
        check("nested field can be focused before reflow", form.focusField(weight));
        narrowWeight = field(weight);
        narrowWeight.setCursorPosition(0);
        narrowWeight.setHighlightPos(1);
        client.options.guiScale().set(3);
        NativeClientProbe.resize(client, 480, 360);
        NativeClientProbe.reflow(client);
        next("catalog compact reflow requested");
      }
      case 7 -> {
        if (form.width > 340) return false;
        check(
            "compact detail retains field focus, caret and selection",
            field(weight) == narrowWeight
                && narrowWeight.getCursorPosition() == 0
                && narrowWeight.getHighlighted().equals("7"));
        geometry(form);
        capture.accept("form-catalog-narrow.png", () -> next("compact catalog captured"));
      }
      case 8 -> {
        press(button(form, "Categories"));
        check(
            "compact catalog switches to list without dropping selection",
            form.fieldControl(weight).isEmpty()
                && form.selectedSection().filter(role::equals).isPresent());
        replace(form, search(), "anchor");
        search().setCursorPosition(1);
        search().setHighlightPos(3);
        press(navigation(form, "anchor"));
        check(
            "filtered compact entry opens its actual detail",
            form.fieldControl(weight).isPresent()
                && form.selectedSection().filter(role::equals).isPresent());
        press(button(form, "Categories"));
        check(
            "returning to compact list retains filter caret and selection",
            search().getValue().equals("anchor")
                && search().getCursorPosition() == 1
                && search().getHighlighted().equals("nc"));
        version = version();
        client.options.guiScale().set(2);
        NativeClientProbe.resize(client, 960, 640);
        NativeClientProbe.reflow(client);
        next("catalog wide reflow requested");
      }
      case 9 -> {
        if (form.width < 400) return false;
        check(
            "wide return retains filter, caret, selection and the active draft",
            search().getValue().equals("anchor")
                && search().getCursorPosition() == 1
                && search().getHighlighted().equals("nc")
                && form.fieldControl(weight).isPresent()
                && raw(weight).equals("7")
                && version() == version);
        geometry(form);
        check(
            "source still matches before explicit Save", Files.readString(path()).equals(original));
        check("field can be focused before native resource reload", form.focusField(weight));
        field(weight).setCursorPosition(0);
        field(weight).setHighlightPos(1);
        check(
            "native language metadata contains the selected fixture language",
            client.getLanguageManager().getLanguages().containsKey("de_de"));
        originalLanguage = client.getLanguageManager().getSelected();
        originalUnicode = client.options.forceUnicodeFont().get();
        previousLanguage = Language.getInstance();
        client.options.forceUnicodeFont().set(!originalUnicode);
        client.getLanguageManager().setSelected("de_de");
        resourceReload = client.reloadResourcePacks();
        next("catalog actual language and font reload requested");
      }
      case 10 -> {
        if (!ready() || !resourceReload.isDone()) return false;
        resourceReload.join();
        check(
            "actual resource reload replaces language and changes native font option",
            Language.getInstance() != previousLanguage
                && client.isEnforceUnicode() != originalUnicode
                && Component.translatable("example.catalog.profiles")
                    .getString()
                    .contains("unverändert"));
        check("localized detail remains reachable", form.focusField(weight));
        check(
            "long locale and font reload retain row, input selection and complete draft",
            form.selectedSection().filter(role::equals).isPresent()
                && raw(weight).equals("7")
                && field(weight).getCursorPosition() == 0
                && field(weight).getHighlighted().equals("7")
                && version() == version
                && units().getValue().equals("seconds")
                && form.getNarrationMessage().getString().contains("verschachtelten Rollen"));
        geometry(form);
        capture.accept("form-catalog-localized.png", () -> next("localized catalog captured"));
      }
      case 11 -> {
        previousLanguage = Language.getInstance();
        client.options.forceUnicodeFont().set(originalUnicode);
        client.getLanguageManager().setSelected(originalLanguage);
        resourceReload = client.reloadResourcePacks();
        next("catalog original language and font reload requested");
      }
      case 12 -> {
        if (!ready() || !resourceReload.isDone()) return false;
        resourceReload.join();
        check(
            "restoring actual resources retains filter, section, caret and draft",
            Language.getInstance() != previousLanguage
                && client.isEnforceUnicode() == originalUnicode
                && search().getValue().equals("anchor")
                && search().getCursorPosition() == 1
                && search().getHighlighted().equals("nc")
                && form.selectedSection().filter(role::equals).isPresent()
                && field(weight).getCursorPosition() == 0
                && field(weight).getHighlighted().equals("7")
                && version() == version
                && Files.readString(path()).equals(original));
        geometry(form);
        press(button(form, "Save and apply"));
        next("catalog complete owner Save requested");
      }
      case 13 -> {
        if (!ready() || form.editorView().dirty()) return false;
        var value =
            PlatformCatalogExample.settings().runtime().orElseThrow().view().value().orElseThrow();
        check(
            "actual Save publishes edits from multiple catalog pages",
            value.profiles().getFirst().name().equals("Alpha revised")
                && value.profiles().getFirst().roles().get("anchor").weight() == 7
                && value.profiles().getFirst().roles().get("anchor").delay() == 40);
        check(
            "actual owner persists without catalog row IDs",
            !Files.readString(path()).equals(original)
                && !Files.readString(path())
                    .contains(((FieldPath.Row) first.segments().getLast()).id().toString()));
        press(button(form, "Done"));
        check(
            "catalog closes its actual editor and presentation",
            NativeScreens.current(client) == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED
                && form.selectedSection().isEmpty());
        savedSource = Files.readString(path());
        var limits = FormCatalog.Limits.STANDARD;
        var boundedCatalog =
            new FormCatalog(
                PlatformCatalogExample.PRESENTATION.catalog().sections(),
                new FormCatalog.Limits(
                    3,
                    limits.maximumLabelCharacters(),
                    limits.maximumTotalLabelCharacters(),
                    limits.maximumQueryCharacters(),
                    limits.maximumHistoryEntries()),
                ignored -> Optional.empty());
        limited =
            editor(
                "Bounded catalog refusal",
                PlatformCatalogExample.PRESENTATION.withCatalog(boundedCatalog));
        NativeScreens.open(client, limited);
        next("native complete catalog exhaustion requested");
      }
      case 14 -> {
        if (limited.editorView().availability() != EditorActor.Availability.READY
            || !button(limited, "Done").active) return false;
        var rows =
            ((DraftNode.Sequence)
                    limited
                        .editorView()
                        .snapshot()
                        .orElseThrow()
                        .draft()
                        .at(PlatformCatalogExample.PROFILES))
                .rows();
        var name = PlatformCatalogExample.PROFILES.row(rows.getFirst().id()).field("name");
        check(
            "exhausted catalog exposes explicit refusal without partial detail",
            limited.getNarrationMessage().getString().contains("Catalog unavailable")
                && limited.selectedSection().isEmpty()
                && limited.fieldControl(name).isEmpty());
        press(button(limited, "Show all fields"));
        check(
            "explicit fallback exposes the same saved source without inventing defaults",
            ((EditBox) limited.fieldControl(name).orElseThrow()).getValue().equals("Alpha revised")
                && !limited.editorView().dirty()
                && Files.readString(path()).equals(savedSource));
        press(button(limited, "Done"));
        check(
            "fallback closes its actual editor without a second save",
            NativeScreens.current(client) == parent
                && limited.editorView().availability() == EditorActor.Availability.CLOSED
                && Files.readString(path()).equals(savedSource));
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown native catalog stage");
    }
    return false;
  }

  public JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "RUNNING");
    result.addProperty("stage", stage);
    result.addProperty(
        "editorClosed", form.editorView().availability() == EditorActor.Availability.CLOSED);
    result.add("checks", checks);
    return result;
  }
}
