package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import com.google.gson.JsonParser;
import design.inherent.reform.definition.*;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Actual optional viewer admission, public handoff, parent return, and original edited owner. */
final class PlatformRecipeLinkChecks implements PlatformFormProbe.Scene {
  private final com.google.gson.JsonArray checks = new com.google.gson.JsonArray();

  static java.util.concurrent.CompletionStage<com.google.gson.JsonObject> run(
      ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration,
        "RECIPE_LINKS",
        driver -> new PlatformRecipeLinkChecks(null, driver::capture));
  }

  @Override
  public com.google.gson.JsonObject report() {
    var result = new com.google.gson.JsonObject();
    result.addProperty("status", "PASS");
    result.add("checks", checks);
    result.addProperty("optionalViewerInstalled", installed);
    return result;
  }

  @Override
  public int tickBudget() {
    return 900;
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private final BiConsumer<String, Runnable> capture;
  private final FieldPath label = FieldPath.ROOT.field("label");
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private CompletableFuture<NavigationAttempt.Result> entered;
  private byte[] source;
  private long revision;
  private boolean installed;
  private int stage, mode;
  private static final List<RecipeLinkRequest> REQUESTS =
      List.of(
          request(RecipeLinkRequest.Kind.PRODUCING, "minecraft:crafting_table"),
          request(RecipeLinkRequest.Kind.USES, "minecraft:oak_planks"),
          request(RecipeLinkRequest.Kind.RECIPE, "minecraft:crafting_table"),
          request(RecipeLinkRequest.Kind.CATEGORY, "minecraft:crafting"),
          request(RecipeLinkRequest.Kind.WORKSTATION, "minecraft:crafting_table"));

  PlatformRecipeLinkChecks(
      BiConsumer<String, Boolean> check, BiConsumer<String, Runnable> capture) {
    this.check =
        (message, accepted) -> {
          if (check != null) check.accept(message, accepted);
          if (!accepted) throw new AssertionError(message);
          checks.add(message);
        };
    this.capture = capture;
  }

  public String diagnostic() {
    return "recipe-links stage="
        + stage
        + ",mode="
        + mode
        + ",screen="
        + String.valueOf(NativeScreens.current(client));
  }

  private static RecipeLinkRequest request(RecipeLinkRequest.Kind kind, String id) {
    return new RecipeLinkRequest(kind, DocumentId.parse(id));
  }

  private ClientDestination.Recipe link(RecipeLinkRequest request) {
    return new ClientDestination.Recipe(
        request, RecipeLinkPolicy.standard().choosing(RecipeLinkPolicy.Provider.JEI));
  }

  private Path path() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  public boolean tick() throws Exception {
    var route = PlatformWorkflowExample.DEFINITION.id();
    switch (stage) {
      case 0 -> {
        var input =
            JsonParser.parseString(Files.readString(Path.of("platform-input.json")))
                .getAsJsonObject();
        installed =
            input.has("recipeViewer")
                && !input.get("recipeViewer").isJsonNull()
                && input.get("recipeViewer").getAsString().equals("jei");
        check.accept(
            "actual loader recipe-viewer admission matches fixture input",
            installed
                ? PlatformClient.recipeViewers().containsKey("jei")
                    && !PlatformClient.recipeViewers().containsKey("emi")
                : PlatformClient.recipeViewers().isEmpty());
        source = Files.readAllBytes(path());
        if (!installed) {
          var resolution = ClientRecipeLinks.inspect(link(REQUESTS.getFirst()));
          check.accept(
              "recipe viewer absent path resolves no foreign runtime or fallback",
              resolution.selected().isEmpty()
                  && resolution.report().issues().stream()
                      .anyMatch(issue -> issue.code().equals("recipe.viewer_absent")));
          check.accept(
              "absent recipe inspection creates no settings write",
              Arrays.equals(source, Files.readAllBytes(path())));
          return true;
        }
        NativeWorldProbe.start();
        stage = 1;
      }
      case 1 -> {
        if (client.level == null
            || client.player == null
            || client.getSingleplayerServer() == null
            || NativeScreens.current(client) != null) return false;
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Recipe link origin"),
                List.of(
                    PlatformWorkflowExample.settings()
                        .form(Component.literal("Recipe preferences"))));
        NativeScreens.open(client, workflow.screen());
        entered =
            workflow
                .navigation()
                .open(new ClientDestination.Document(route))
                .completion()
                .toCompletableFuture();
        stage = 2;
      }
      case 2 -> {
        if (!entered.isDone()) return false;
        check.accept(
            "recipe origin acknowledges actual native document",
            entered.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(route);
        if (editor.busy() || editor.form().fieldControl(label).isEmpty()) return false;
        var field = (EditBox) editor.form().fieldControl(label).orElseThrow();
        replace(workflow.screen(), field, "Recipe draft");
        field.setCursorPosition(3);
        field.setHighlightPos(1);
        stage = 3;
      }
      case 3 -> {
        if (editor.busy() || !editor.dirty()) return false;
        revision = editor.view().snapshot().orElseThrow().version();
        stage = 4;
      }
      case 4 -> {
        var link = link(REQUESTS.get(mode));
        var readiness = ClientRecipeLinks.inspect(link);
        if (readiness.selected().isEmpty()) return false;
        check.accept(
            "recipe runtime advertises requested kind " + mode,
            workflow.navigation().inspect(link).kind() == DestinationAvailability.Kind.READY);
        entered = workflow.navigation().open(link).completion().toCompletableFuture();
        stage = 5;
      }
      case 5 -> {
        if (!entered.isDone()) return false;
        var result = entered.join();
        check.accept(
            "one actual JEI handoff " + mode + ": " + result.report(),
            result.outcome() == NavigationAttempt.Outcome.HANDED_OFF);
        check.accept(
            "viewer handoff retains same dirty editor and revision " + mode,
            workflow.isOpen()
                && workflow.editor(route) == editor
                && editor.isOpen()
                && editor.view().snapshot().orElseThrow().version() == revision);
        if (mode == 0)
          capture.accept("recipe-viewer.png", () -> NativeScreens.current(client).onClose());
        else NativeScreens.current(client).onClose();
        stage = 6;
      }
      case 6 -> {
        if (NativeScreens.current(client) != workflow.screen()) return false;
        if (editor.busy() || editor.form().fieldControl(label).isEmpty()) return false;
        var field = (EditBox) editor.form().fieldControl(label).orElseThrow();
        check.accept(
            "actual parent return retains native raw input and caret " + mode,
            field.getValue().equals("Recipe draft")
                && field.getCursorPosition() == 3
                && field.getHighlighted().equals("ec")
                && editor.view().snapshot().orElseThrow().version() == revision
                && Arrays.equals(source, Files.readAllBytes(path())));
        if (++mode < REQUESTS.size()) stage = 4;
        else stage = 7;
      }
      case 7 -> {
        var absent = link(request(RecipeLinkRequest.Kind.RECIPE, "fixture:recipe_absent"));
        var result = workflow.navigation().open(absent).result().orElseThrow();
        check.accept(
            "no matching recipe is a structured non-success without leaving origin",
            result.outcome() == NavigationAttempt.Outcome.REFUSED
                && result.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("recipe.no_match"))
                && NativeScreens.current(client) == workflow.screen());
        var preferred =
            new ClientDestination.Recipe(
                REQUESTS.getFirst(),
                RecipeLinkPolicy.standard().choosing(RecipeLinkPolicy.Provider.EMI));
        var resolution = ClientRecipeLinks.inspect(preferred);
        check.accept(
            "configured unavailable EMI never silently selects JEI",
            resolution.selected().isEmpty()
                && resolution.alternatives().stream()
                    .anyMatch(
                        status ->
                            status.provider() == RecipeLinkPolicy.Provider.JEI && status.ready()));
        check.accept(
            "unavailable provider returns explicit alternate choice",
            workflow.navigation().open(preferred).result().orElseThrow().outcome()
                == NavigationAttempt.Outcome.UNAVAILABLE);
        click(
            NativeScreens.current(client), button(NativeScreens.current(client), "Open with JEI"));
        stage = 8;
      }
      case 8 -> {
        check.accept(
            "explicit alternate enters exactly one viewer with retained owner",
            NativeScreens.current(client) != workflow.screen()
                && !(NativeScreens.current(client) instanceof ClientChoiceScreen)
                && workflow.editor(route) == editor);
        NativeScreens.current(client).onClose();
        stage = 9;
      }
      case 9 -> {
        if (NativeScreens.current(client) != workflow.screen()) return false;
        check.accept(
            "all viewer activations leave source unchanged",
            Arrays.equals(source, Files.readAllBytes(path())));
        workflow.close();
        NativeWorldProbe.leave();
        stage = 10;
      }
      case 10 -> {
        if (client.level != null
            || client.player != null
            || client.getConnection() != null
            || client.getSingleplayerServer() != null) return false;
        check.accept(
            "viewer world departure preserves source bytes",
            Arrays.equals(source, Files.readAllBytes(path())));
        return true;
      }
      default -> {}
    }
    return false;
  }
}
