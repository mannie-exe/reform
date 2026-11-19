package example.reformprobe;

import static example.reformprobe.NativeFormProbe.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.neoforge.client.ReviewScreen;
import design.inherent.reform.neoforge.client.SettingsScreen;
import design.inherent.reform.neoforge.command.SettingsCommands;
import design.inherent.reform.operation.DocumentReview;
import design.inherent.reform.owner.EditorActor;
import example.reform.CatalogExample;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientCommandSourceStack;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/** Native source inspection and related-field return over an ordinary, unsaved public editor. */
final class NativeReviewControls {
  private static final FieldPath MIN = CatalogExample.GLOBAL.field("minimum");
  private static final FieldPath MAX = CatalogExample.GLOBAL.field("maximum");
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = client.screen;
  private final SettingsScreen<CatalogExample.Profiles> form;
  private final JsonArray checks = new JsonArray();
  private final boolean reopen;
  private final Consumer<String> progress;
  private final BiConsumer<String, Runnable> capture;
  private final String original;
  private final List<SettingsCommands.Reply> replies = new ArrayList<>();
  private ReviewScreen<?> view, retiredView;
  private FieldPath first;
  private Button retiredDestination;
  private EditBox filter;
  private DocumentReview captured;
  private int stage;
  private boolean complete;

  @SuppressWarnings("unchecked") // Ready public factory uses this exact definition.
  NativeReviewControls(
      boolean reopen, Consumer<String> progress, BiConsumer<String, Runnable> capture)
      throws Exception {
    this.reopen = reopen;
    this.progress = progress;
    this.capture = capture;
    original = Files.readString(path());
    form =
        (SettingsScreen<CatalogExample.Profiles>)
            CatalogExample.settings()
                .screen(
                    parent, Component.literal("Inspection parent"), CatalogExample.PRESENTATION);
    client.setScreen(form);
  }

  private Path path() {
    return client.gameDirectory.toPath().resolve("config/reform_native_example/catalog.json");
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
    return client.screen == form
        && form.editorView().availability() == EditorActor.Availability.READY
        && button(form, "Done").active;
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

  private void press(Button control) {
    check("native inspection control receives focus", focus(client.screen, control));
    click(client.screen, control);
    check(
        "callback focus remains in the active tree",
        client.screen.getFocused() == null
            || client.screen.children().contains(client.screen.getFocused()));
  }

  private CycleButton<?> fieldChoice() {
    return widgets(view).stream()
        .filter(CycleButton.class::isInstance)
        .map(CycleButton.class::cast)
        .filter(value -> value.getValue() instanceof FieldPath)
        .findFirst()
        .orElseThrow();
  }

  private EditBox search() {
    return input(
        view,
        Component.translatableWithFallback("reform.review.search", "Find a field").getString());
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!ready()) return false;
        first =
            CatalogExample.PROFILES.row(
                ((DraftNode.Sequence) node(CatalogExample.PROFILES)).rows().getFirst().id());
        if (reopen) {
          check(
              "fresh JVM sees no discarded inspection edits",
              raw(first.field("name")).equals("Land")
                  && raw(MIN).equals("1")
                  && ((DraftNode.Sequence) node(CatalogExample.PROFILES)).rows().size() == 2
                  && !form.editorView().dirty());
          press(button(form, "Done"));
          check(
              "reopened source closes without rewriting",
              form.editorView().availability() == EditorActor.Availability.CLOSED
                  && Files.readString(path()).equals(original));
          complete = true;
          return true;
        }
        check(
            "profile can be selected through actual public navigation", form.selectSection(first));
        replace(form, field(first.field("name")), "Alpha review " + "x".repeat(100));
        next("inspection retains unsaved profile text");
      }
      case 1 -> {
        if (!ready() || !raw(first.field("name")).startsWith("Alpha review")) return false;
        check("range field can be selected", form.focusField(MIN));
        replace(form, field(MIN), "9");
        next("cross-field invalid input entered");
      }
      case 2 -> {
        if (!ready() || form.editorView().validation().valid()) return false;
        geometry(form);
        check("inspection stays available for invalid draft", button(form, "Inspect…").active);
        press(button(form, "Inspect…"));
        check("status entry opens native inspection", client.screen instanceof ReviewScreen<?>);
        view = (ReviewScreen<?>) client.screen;
        next("native inspection requested");
      }
      case 3 -> {
        if (view.reviewedInput().isEmpty()) return false;
        captured = view.reviewedInput().orElseThrow();
        check(
            "view and parent share one actual draft",
            captured.target().equals(DraftRevision.of(form.editorView().snapshot().orElseThrow())));
        var count =
            captured.entries().stream()
                .filter(entry -> entry.path().equals(MIN))
                .findFirst()
                .orElseThrow();
        check(
            "baseline draft and effective details remain distinct",
            count.baseline().equals("\"1\"")
                && count.draft().equals("\"9\"")
                && count.effective().orElseThrow().equals("\"1\"")
                && !captured.comparison().report().valid()
                && captured.comparison().authoredChanges());
        check(
            "source has actual owned location",
            captured.source().kind().equals("json")
                && captured.source().location().orElseThrow().endsWith("catalog.json"));
        check("inspection does not persist", Files.readString(path()).equals(original));
        var choices =
            widgets(view).stream()
                .filter(CycleButton.class::isInstance)
                .map(CycleButton.class::cast)
                .filter(value -> value.getValue() instanceof FieldPath)
                .toList();
        check("related diagnostic uses a bounded location control", choices.size() == 2);
        var related = choices.getLast();
        related.onPress();
        check("related control retains actual target", related.getValue().equals(MAX));
        retiredDestination = button(view, "Go to field");
        retiredView = view;
        capture.accept("form-review-diagnostics.png", () -> next("diagnostics captured"));
      }
      case 4 -> {
        press(retiredDestination);
        check(
            "related target returns to same editor and reveals its field",
            client.screen == form
                && form.fieldControl(MAX).isPresent()
                && raw(MIN).equals("9")
                && raw(first.field("name")).startsWith("Alpha review"));
        check(
            "return closes only the borrowed view",
            retiredView.reviewedInput().isEmpty()
                && form.editorView().availability() == EditorActor.Availability.READY);
        retiredDestination.onPress();
        check("retired diagnostic callback has no navigation effect", client.screen == form);
        replace(form, field(MIN), "2");
        next("range repair entered");
      }
      case 5 -> {
        if (!ready() || !form.editorView().validation().valid()) return false;
        check("return to profile preserves actor", form.selectSection(first));
        press(button(form, "Copy"));
        next("first explicit profile copy");
      }
      case 6 -> {
        if (!ready() || ((DraftNode.Sequence) node(CatalogExample.PROFILES)).rows().size() != 3)
          return false;
        press(button(form, "Copy"));
        next("second explicit profile copy");
      }
      case 7 -> {
        if (!ready() || ((DraftNode.Sequence) node(CatalogExample.PROFILES)).rows().size() != 4)
          return false;
        press(button(form, "Inspect…"));
        view = (ReviewScreen<?>) client.screen;
        next("whole collection inspection requested");
      }
      case 8 -> {
        if (view.reviewedInput().isEmpty()) return false;
        captured = view.reviewedInput().orElseThrow();
        check(
            "dynamic row resolves to declared containing collection",
            view.selectedField().filter(CatalogExample.PROFILES::equals).isPresent());
        var values =
            captured.entries().stream()
                .filter(entry -> entry.path().equals(CatalogExample.PROFILES))
                .findFirst()
                .orElseThrow();
        check(
            "large authored collection retains complete captured data",
            values.wholeValue()
                && values.draft().length() > 512
                && values.baseline().contains("Land")
                && values.effective().orElseThrow().contains("Land"));
        press(button(view, "Expand"));
        check(
            "expansion does not repeat source work",
            view.reviewedInput().orElseThrow() == captured);
        press(button(view, "Collapse"));
        replace(view, search(), "no match");
        check(
            "zero field matches retain selected capture",
            view.selectedField().filter(CatalogExample.PROFILES::equals).isPresent()
                && view.reviewedInput().orElseThrow() == captured);
        replace(view, search(), "minimum");
        var selector = fieldChoice();
        check("field chooser receives native focus", focus(view, selector));
        selector.onPress();
        check(
            "field selector follows real metadata",
            view.selectedField().filter(MIN::equals).isPresent());
        check(
            "single-result field chooser cannot retain disabled focus",
            !fieldChoice().active && !fieldChoice().isFocused());
        filter = search();
        filter.setCursorPosition(1);
        filter.setHighlightPos(3);
        client.options.guiScale().set(3);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 480, 360);
        client.resizeDisplay();
        next("native inspection narrow reflow requested");
      }
      case 9 -> {
        if (view.width > 340) return false;
        check(
            "native filter survives measured reflow",
            search() == filter
                && filter.getCursorPosition() == 1
                && filter.getHighlighted().equals("in")
                && view.selectedField().filter(MIN::equals).isPresent()
                && view.reviewedInput().orElseThrow() == captured);
        geometry(view);
        capture.accept("form-review-narrow.png", () -> next("narrow inspection captured"));
      }
      case 10 -> {
        press(button(view, "Validate"));
        next("explicit actual owner validation requested");
      }
      case 11 -> {
        if (!button(view, "Refresh view").active) return false;
        check("actual validation does not save profile", Files.readString(path()).equals(original));
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        NeoForge.EVENT_BUS.post(
            new RegisterClientCommandsEvent(
                dispatcher,
                CommandBuildContext.simple(RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS)));
        check(
            "ordinary addon registers review-capable command root",
            dispatcher.getRoot().getChild("reformcatalog") != null);
        dispatcher.register(CatalogExample.settings().commandBranch("reviewprobe", replies::add));
        var source =
            new ClientCommandSourceStack(
                CommandSource.NULL,
                Vec3.ZERO,
                Vec2.ZERO,
                0,
                "review-probe",
                Component.literal("review-probe"),
                null);
        check(
            "native Brigadier accepts shared review service",
            dispatcher.execute("reviewprobe review", source) == 1);
        next("native command review queued");
      }
      case 12 -> {
        var reply =
            replies.stream()
                .filter(value -> value.kind() == SettingsCommands.Kind.COMPLETED)
                .findFirst();
        if (reply.isEmpty()) return false;
        var message = reply.orElseThrow();
        var command = (DocumentReview) message.operation().orElseThrow().result().orElseThrow();
        check(
            "command review has independent actual actor draft",
            !command.target().session().equals(captured.target().session())
                && command.target().document().equals(captured.target().document())
                && !command.comparison().authoredChanges());
        check(
            "native feedback identifies observed source and bounded result",
            message.message().getString().contains("observed source=json")
                && message.message().getString().length() <= 8192);
        check(
            "GUI review remains the same unsaved capture",
            view.reviewedInput().orElseThrow() == captured
                && Files.readString(path()).equals(original));
        press(button(view, "Back"));
        press(button(form, "Revert"));
        next("explicit draft revert requested");
      }
      case 13 -> {
        if (!ready() || form.editorView().dirty()) return false;
        check(
            "revert retains source and effective values",
            raw(first.field("name")).equals("Land")
                && raw(MIN).equals("1")
                && ((DraftNode.Sequence) node(CatalogExample.PROFILES)).rows().size() == 2
                && Files.readString(path()).equals(original));
        press(button(form, "Done"));
        check(
            "native inspection parent closes actual editing lifetime",
            client.screen == parent
                && form.editorView().availability() == EditorActor.Availability.CLOSED);
        complete = true;
        return true;
      }
      default -> throw new AssertionError("Unknown inspection stage");
    }
    return false;
  }

  JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", complete ? "PASS" : "PENDING");
    result.addProperty("stage", stage);
    result.addProperty(
        "editorClosed", form.editorView().availability() == EditorActor.Availability.CLOSED);
    result.add("checks", checks.deepCopy());
    return result;
  }
}
