package example.reform.platform;

import com.google.gson.*;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.presentation.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Owner-supplied actual native context, existing unlocked recipes, and optional EMI parent return.
 */
final class PlatformNativeRecipeChecks implements PlatformFormProbe.Scene {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final JsonArray checks = new JsonArray();
  private final BiConsumer<String, Runnable> capture;
  private ClientEditingWorkflow workflow;
  private InventoryScreen inventory;
  private List<ItemStack> items;
  private List<String> unlocked;
  private byte[] source;
  private CompletableFuture<Void> survival;
  private CompletableFuture<Void> nativeDisconnect;
  private boolean nativeDisconnectDeferredOutsideObserverTick,
      nativeDisconnectOriginRetained,
      nativeDisconnectCompleted;
  private boolean emi;
  private boolean both;
  private final JsonObject dualReadiness = new JsonObject();
  private int stage, mode;
  private ClientNativeRecipeBook.Result bookResult;
  private AutoCloseable simulated;
  private int simulatedOpens;
  private NavigationAttempt simulatedAttempt;

  static java.util.concurrent.CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration, "NATIVE_RECIPES", driver -> new PlatformNativeRecipeChecks(driver::capture));
  }

  PlatformNativeRecipeChecks(BiConsumer<String, Runnable> capture) {
    this.capture = capture;
  }

  @Override
  public int tickBudget() {
    return 1200;
  }

  @Override
  public String diagnostic() {
    return "native-recipes stage="
        + stage
        + ",mode="
        + mode
        + ",screen="
        + NativeScreens.current(client);
  }

  @Override
  public JsonObject report() {
    var result = new JsonObject();
    result.addProperty("status", "PASS");
    result.add("checks", checks);
    if (bookResult != null) {
      var book = new JsonObject();
      book.addProperty("visible", bookResult.visible());
      book.addProperty("changed", bookResult.changed());
      var issues = new JsonArray();
      for (var issue : bookResult.report().issues())
        issues.add(issue.code() + ": " + issue.fallback());
      book.add("issues", issues);
      book.addProperty("complete", bookResult.report().complete());
      book.addProperty("cause", bookResult.cause().map(Throwable::toString).orElse(""));
      result.add("bookResult", book);
    }
    result.addProperty("simulatedContainerOpenCalls", simulatedOpens);
    if (simulatedAttempt != null)
      result.addProperty(
          "simulatedContainerOutcome",
          simulatedAttempt.result().map(r -> r.outcome().name()).orElse("PENDING"));
    result.addProperty("emiRequested", emi);
    result.addProperty("bothRequested", both);
    result.add("dualReadiness", dualReadiness.deepCopy());
    result.addProperty("knownRecipeCount", unlocked == null ? -1 : unlocked.size());
    result.addProperty(
        "nativeDisconnectDeferredOutsideObserverTick", nativeDisconnectDeferredOutsideObserverTick);
    result.addProperty("nativeDisconnectOriginRetained", nativeDisconnectOriginRetained);
    result.addProperty("nativeDisconnectCompleted", nativeDisconnectCompleted);
    return result;
  }

  private void check(String label, boolean accepted) {
    if (!accepted) throw new AssertionError(label);
    checks.add(label);
  }

  private Path source() {
    return client
        .gameDirectory
        .toPath()
        .resolve("config/reform_platform_example/workflow_preferences.json");
  }

  private ClientDestination.Recipe link(RecipeLinkRequest.Kind kind, String id) {
    return new ClientDestination.Recipe(
        new RecipeLinkRequest(kind, DocumentId.parse(id)),
        RecipeLinkPolicy.standard().choosing(RecipeLinkPolicy.Provider.EMI));
  }

  private ClientDestination.Recipe selected() {
    return switch (mode) {
      case 0 -> link(RecipeLinkRequest.Kind.PRODUCING, "minecraft:crafting_table");
      case 1 -> link(RecipeLinkRequest.Kind.USES, "minecraft:oak_planks");
      case 2 -> link(RecipeLinkRequest.Kind.RECIPE, "minecraft:crafting_table");
      default -> link(RecipeLinkRequest.Kind.CATEGORY, "minecraft:crafting");
    };
  }

  private void unchanged() throws Exception {
    check(
        "native book/viewer leaves actual inventory and unlocked recipes unchanged " + stage,
        NativeRecipeFixture.unchanged(items) && NativeRecipeFixture.unlocked().equals(unlocked));
    check(
        "native book/viewer leaves owner file unchanged " + stage,
        Arrays.equals(source, Files.readAllBytes(source())));
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        var input =
            JsonParser.parseString(Files.readString(Path.of("platform-input.json")))
                .getAsJsonObject();
        var viewer =
            input.has("recipeViewer") && !input.get("recipeViewer").isJsonNull()
                ? input.get("recipeViewer").getAsString()
                : "";
        both = viewer.equals("both");
        emi = viewer.equals("emi") || both;
        check(
            "actual native recipe fixture optional admission",
            both
                ? PlatformClient.recipeViewers().keySet().equals(Set.of("jei", "emi"))
                : emi
                    ? PlatformClient.recipeViewers().keySet().equals(Set.of("emi"))
                    : PlatformClient.recipeViewers().isEmpty());
        source = Files.readAllBytes(source());
        check(
            "native book title context unavailable without manufacturing a world",
            ClientNativeRecipeBook.inspect(parent).kind()
                    == DestinationAvailability.Kind.UNAVAILABLE
                && !ClientNativeRecipeBook.open(parent).visible()
                && client.level == null
                && client.player == null
                && NativeScreens.current(client) == parent);
        NativeWorldProbe.start();
        stage = 1;
      }
      case 1 -> {
        if (client.level == null
            || client.player == null
            || client.getSingleplayerServer() == null
            || NativeScreens.current(client) != null) return false;
        survival = NativeRecipeFixture.survival();
        stage = 2;
      }
      case 2 -> {
        if (!survival.isDone()) return false;
        survival.join();
        if (client.player.getAbilities().instabuild || client.player.isSpectator()) return false;
        if (emi) {
          workflow =
              ClientEditingWorkflow.open(
                  parent,
                  Component.literal("EMI return boundary"),
                  List.of(
                      PlatformWorkflowExample.settings().form(Component.literal("Preferences"))));
          NativeScreens.open(client, workflow.screen());
          stage = 3;
        } else stage = 4;
      }
      case 3 -> {
        var destination = selected();
        var status = ClientRecipeLinks.inspect(destination);
        if (status.alternatives().stream()
            .noneMatch(
                value ->
                    value.provider() == RecipeLinkPolicy.Provider.EMI && !value.kinds().isEmpty()))
          return false;
        check(
            "installed EMI refuses generic settings return path explicitly",
            status.selected().isEmpty()
                && status.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("recipe.return_unavailable")));
        check(
            "generic EMI request never manufactures an inventory screen",
            workflow.navigation().open(destination).result().orElseThrow().outcome()
                    == NavigationAttempt.Outcome.UNAVAILABLE
                && NativeScreens.current(client) == workflow.screen()
                && workflow.isOpen());
        check(
            "native book refuses shared settings screen",
            !ClientNativeRecipeBook.open(workflow.screen()).visible());
        workflow.close();
        NativeScreens.open(client, null);
        stage = 4;
      }
      case 4 -> {
        inventory = new InventoryScreen(client.player);
        NativeScreens.open(client, inventory);
        if (NativeScreens.current(client) != inventory)
          throw new AssertionError("Owned actual survival inventory was replaced");
        items = NativeRecipeFixture.inventory();
        unlocked = NativeRecipeFixture.unlocked();
        stage = 5;
      }
      case 5 -> {
        check(
            "owner supplies actual current player inventory/menu",
            client.player.containerMenu == inventory.getMenu());
        if (emi) {
          var availability = ClientNativeRecipeBook.inspect(inventory);
          bookResult = ClientNativeRecipeBook.open(inventory);
          check(
              "EMI native book integration refuses before invoking repurposed action",
              availability.kind() == DestinationAvailability.Kind.UNAVAILABLE
                  && availability.report().issues().stream()
                      .anyMatch(issue -> issue.code().equals("recipe.book_integration_unavailable"))
                  && !bookResult.report().valid()
                  && !bookResult.visible()
                  && !bookResult.changed()
                  && NativeScreens.current(client) == inventory);
          unchanged();
          stage = both ? 11 : 6;
          break;
        }
        check(
            "current native book context is ready",
            ClientNativeRecipeBook.inspect(inventory).kind() == DestinationAvailability.Kind.READY);
        var opened = ClientNativeRecipeBook.open(inventory);
        bookResult = opened;
        check(
            "existing native book opens through its own action",
            opened.report().valid() && opened.visible());
        var repeated = ClientNativeRecipeBook.open(inventory);
        check(
            "repeated native book reveal does not hide it",
            repeated.report().valid() && repeated.visible() && !repeated.changed());
        unchanged();
        capture.accept("native-recipe-book.png", () -> {});
        stage = emi ? 6 : 9;
      }
      case 6 -> {
        var destination = selected();
        var status = ClientRecipeLinks.inspect(destination);
        if (status.selected().isEmpty()) return false;
        check(
            "EMI real-container requested capability is ready " + mode,
            status.selected().orElseThrow() == RecipeLinkPolicy.Provider.EMI);
        var result = ClientRecipeLinks.openContainer(inventory, destination).result().orElseThrow();
        check(
            "EMI native-container actual public handoff " + mode + ": " + result.report(),
            result.outcome() == NavigationAttempt.Outcome.HANDED_OFF
                && NativeScreens.current(client) != inventory);
        if (mode == 0)
          capture.accept("native-emi-viewer.png", () -> NativeScreens.current(client).onClose());
        else NativeScreens.current(client).onClose();
        stage = 7;
      }
      case 7 -> {
        if (NativeScreens.current(client) != inventory) return false;
        check(
            "EMI actual public parent returns same native owner/menu " + mode,
            client.player.containerMenu == inventory.getMenu());
        unchanged();
        if (++mode < 4) stage = 6;
        else stage = 8;
      }
      case 8 -> {
        var missing =
            ClientRecipeLinks.openContainer(
                    inventory, link(RecipeLinkRequest.Kind.RECIPE, "fixture:absent_recipe"))
                .result()
                .orElseThrow();
        check(
            "EMI missing recipe is useful refusal without losing native origin",
            missing.outcome() == NavigationAttempt.Outcome.REFUSED
                && missing.report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("recipe.no_match"))
                && NativeScreens.current(client) == inventory);
        var station = link(RecipeLinkRequest.Kind.WORKSTATION, "minecraft:crafting_table");
        check(
            "EMI advertises unsupported workstation-only public scope truthfully",
            ClientRecipeLinks.inspect(station).selected().isEmpty()
                && ClientRecipeLinks.inspect(station).report().issues().stream()
                    .anyMatch(issue -> issue.code().equals("recipe.request_unsupported")));
        stage = 9;
      }
      case 9 -> {
        unchanged();
        if (!emi) {
          simulated =
              ClientRecipeLinks.register(
                  RecipeLinkPolicy.Provider.JEI,
                  new ClientRecipeLinks.Adapter() {
                    public Set<RecipeLinkRequest.Kind> kinds() {
                      return Set.of(RecipeLinkRequest.Kind.PRODUCING);
                    }

                    public Optional<design.inherent.reform.owner.PolicyRefusal> unavailable(
                        Screen origin) {
                      return Optional.empty();
                    }

                    public ClientRecipeLinks.Prepared prepare(
                        RecipeLinkRequest request, Screen origin) {
                      NativeScreens.open(client, null);
                      return new ClientRecipeLinks.Prepared() {
                        public void open() {
                          simulatedOpens++;
                          throw new design.inherent.reform.owner.PolicyRefusal(
                              "fixture.container_retired",
                              "Simulated lookup retired its old container before activation");
                        }

                        public boolean hasParent(Screen origin) {
                          return false;
                        }
                      };
                    }
                  });
          var destination =
              new ClientDestination.Recipe(
                  new RecipeLinkRequest(
                      RecipeLinkRequest.Kind.PRODUCING,
                      DocumentId.parse("minecraft:crafting_table")),
                  RecipeLinkPolicy.standard().choosing(RecipeLinkPolicy.Provider.JEI));
          simulatedAttempt = ClientRecipeLinks.openContainer(inventory, destination);
          check(
              "simulated retired container lookup never enters or resurrects old view",
              simulatedOpens == 0
                  && simulatedAttempt.result().orElseThrow().outcome()
                      == NavigationAttempt.Outcome.REFUSED
                  && NativeScreens.current(client) == null);
          simulated.close();
          simulated = null;
        }
        NativeScreens.open(client, null);
        check(
            "retired native book screen is unavailable without resurrection",
            ClientNativeRecipeBook.inspect(inventory).kind()
                    == DestinationAvailability.Kind.UNAVAILABLE
                && !ClientNativeRecipeBook.open(inventory).visible()
                && NativeScreens.current(client) == null);
        if (emi)
          check(
              "retired container recipe activation is refused without replacement",
              ClientRecipeLinks.openContainer(inventory, selected())
                          .result()
                          .orElseThrow()
                          .outcome()
                      == NavigationAttempt.Outcome.REFUSED
                  && NativeScreens.current(client) == null);
        unchanged();
        stage = 10;
        var expectedLevel = new java.lang.ref.WeakReference<>(client.level);
        var expectedConnection = new java.lang.ref.WeakReference<>(client.getConnection());
        var completion = new CompletableFuture<Void>();
        nativeDisconnect = completion;
        // Queue unconditionally; execute can run inline inside the current ClientTick callback.
        NativeClientProbe.queue(
            () -> {
              if (completion.isCancelled()) return;
              try {
                nativeDisconnectDeferredOutsideObserverTick =
                    StackWalker.getInstance()
                        .walk(
                            frames ->
                                frames.noneMatch(
                                    frame ->
                                        frame
                                                .getClassName()
                                                .equals(PlatformFormProbe.Driver.class.getName())
                                            && frame.getMethodName().equals("tick")));
                check(
                    "native disconnect is deferred outside the observer tick",
                    nativeDisconnectDeferredOutsideObserverTick);
                nativeDisconnectOriginRetained =
                    expectedLevel.get() != null
                        && client.level == expectedLevel.get()
                        && expectedConnection.get() != null
                        && client.getConnection() == expectedConnection.get();
                check(
                    "queued native disconnect retains the original world and connection",
                    nativeDisconnectOriginRetained);
                NativeWorldProbe.leave();
                nativeDisconnectCompleted = true;
                completion.complete(null);
              } catch (RuntimeException | AssertionError failed) {
                completion.completeExceptionally(failed);
              }
            });
      }
      case 10 -> {
        if (nativeDisconnect == null || !nativeDisconnect.isDone()) return false;
        nativeDisconnect.join();
        if (client.level != null
            || client.player != null
            || client.getConnection() != null
            || client.getSingleplayerServer() != null) return false;
        check(
            "native recipe fixture departure leaves file unchanged",
            Arrays.equals(source, Files.readAllBytes(source())));
        return true;
      }
      case 11 -> {
        var request =
            new RecipeLinkRequest(
                RecipeLinkRequest.Kind.PRODUCING, DocumentId.parse("minecraft:crafting_table"));
        var preferred =
            new ClientDestination.Recipe(
                request, RecipeLinkPolicy.standard().choosing(RecipeLinkPolicy.Provider.JEI));
        var jei = ClientRecipeLinks.inspect(preferred);
        boolean jeiReady = jei.selected().isPresent();
        dualReadiness.addProperty("jeiReady", jeiReady);
        var reasons = new JsonArray();
        jei.report().issues().forEach(issue -> reasons.add(issue.code() + ": " + issue.fallback()));
        dualReadiness.add("jeiIssues", reasons);
        var emiStatus =
            ClientRecipeLinks.inspect(
                new ClientDestination.Recipe(
                    request, RecipeLinkPolicy.standard().choosing(RecipeLinkPolicy.Provider.EMI)));
        if (emiStatus.selected().isEmpty()) return false;
        dualReadiness.addProperty("emiReady", true);
        var automatic = new ClientDestination.Recipe(request);
        var expected = jeiReady ? RecipeLinkPolicy.Provider.JEI : RecipeLinkPolicy.Provider.EMI;
        check(
            "dual viewers choose the first ready provider without opening during inspection",
            ClientRecipeLinks.inspect(automatic).selected().orElseThrow() == expected
                && NativeScreens.current(client) == inventory);
        check(
            "explicit JEI preference never silently falls through",
            jeiReady
                || (jei.selected().isEmpty()
                    && jei.alternatives().stream()
                        .anyMatch(
                            value ->
                                value.provider() == RecipeLinkPolicy.Provider.EMI
                                    && value.ready())));
        if (!jeiReady) {
          var refused =
              ClientRecipeLinks.openContainer(inventory, preferred).result().orElseThrow();
          check(
              "installed but unready JEI refuses actual activation without opening EMI",
              refused.outcome() == NavigationAttempt.Outcome.REFUSED
                  && NativeScreens.current(client) == inventory);
        }
        var unsupported = link(RecipeLinkRequest.Kind.WORKSTATION, "minecraft:crafting_table");
        check(
            "configured unsupported EMI never falls through to installed JEI",
            ClientRecipeLinks.inspect(unsupported).selected().isEmpty()
                && ClientRecipeLinks.openContainer(inventory, unsupported)
                        .result()
                        .orElseThrow()
                        .outcome()
                    == NavigationAttempt.Outcome.REFUSED
                && NativeScreens.current(client) == inventory);
        var opened = ClientRecipeLinks.openContainer(inventory, automatic).result().orElseThrow();
        check(
            "dual viewer automatic choice performs one actual public handoff",
            opened.outcome() == NavigationAttempt.Outcome.HANDED_OFF
                && NativeScreens.current(client) != inventory);
        dualReadiness.addProperty("automaticProvider", expected.name());
        capture.accept("native-dual-viewer.png", () -> NativeScreens.current(client).onClose());
        stage = 12;
      }
      case 12 -> {
        if (NativeScreens.current(client) != inventory) return false;
        check(
            "dual viewer automatic return keeps same real owner and menu",
            client.player.containerMenu == inventory.getMenu());
        unchanged();
        stage = 6;
      }
      default -> {}
    }
    return false;
  }

  @Override
  public void close() {
    if (nativeDisconnect != null && !nativeDisconnect.isDone()) nativeDisconnect.cancel(false);
    if (simulated != null) {
      try {
        simulated.close();
      } catch (Exception failed) {
        throw new IllegalStateException("Simulated adapter cleanup failed", failed);
      }
      simulated = null;
    }
    if (workflow != null && workflow.isOpen()) workflow.close();
  }
}
