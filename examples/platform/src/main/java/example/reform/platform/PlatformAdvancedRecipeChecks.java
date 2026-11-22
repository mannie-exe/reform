package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.minecraft.client.NativeScreens;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Existing Phase 1 native recipe, reviewed-effect and defaults scenarios on every target. */
final class PlatformAdvancedRecipeChecks implements PlatformFormProbe.Scene {
  private static PlatformAdvancedRecipeChecks active;

  static CompletionStage<JsonObject> run(ClientRegistration registration, boolean reopen) {
    return PlatformFormProbe.run(
        registration,
        "RECIPE_ADVANCED",
        driver -> {
          active = new PlatformAdvancedRecipeChecks(driver, reopen);
          return active;
        });
  }

  static void stopped() {
    if (active == null || active.stage != 3)
      throw new AssertionError("Advanced recipe did not finish");
    active.flow.shutdown();
    active.report.add("defaults", active.defaults.report());
    active.report.add("invalidDefaults", active.invalid.report());
    for (var source : new PlatformWorldDefaultsChecks[] {active.defaults, active.invalid}) {
      var result = source.report();
      if (!result.get("factoryClosed").getAsBoolean() || !result.get("eagerClosed").getAsBoolean())
        throw new AssertionError("Native shutdown retained future-world defaults");
    }
    active.report.addProperty("shutdownClosed", true);
  }

  private final Minecraft client = Minecraft.getInstance();
  private final Screen initial = NativeScreens.current(client);
  private final PlatformFormProbe.Driver driver;
  private final JsonObject report = new JsonObject();
  private final boolean reopen;
  private final PlatformRecipeFlowChecks flow;
  private PlatformWorldDefaultsChecks defaults, invalid;
  private int stage;

  private PlatformAdvancedRecipeChecks(PlatformFormProbe.Driver driver, boolean reopen) {
    this.driver = driver;
    this.reopen = reopen;
    report.addProperty("status", "RUNNING");
    flow = new PlatformRecipeFlowChecks(driver::progress, driver::capture, reopen);
  }

  @Override
  public int tickBudget() {
    return 1800;
  }

  @Override
  public String diagnostic() {
    return "advanced recipe stage="
        + stage
        + ", "
        + switch (stage) {
          case 0 -> flow.diagnostic();
          case 1 -> defaults.diagnostic();
          case 2 -> invalid.diagnostic();
          default -> "complete";
        };
  }

  @Override
  public boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        if (!flow.tick()) return false;
        report.add("flow", flow.report());
        defaults =
            new PlatformWorldDefaultsChecks(
                reopen ? "defaults-reopen" : "defaults", driver::progress, driver::capture);
        stage++;
      }
      case 1 -> {
        if (!defaults.tick()) return false;
        report.add("defaults", defaults.report());
        defaults.close();
        invalid =
            new PlatformWorldDefaultsChecks("defaults-invalid", driver::progress, driver::capture);
        stage++;
      }
      case 2 -> {
        if (!invalid.tick()) return false;
        report.add("invalidDefaults", invalid.report());
        invalid.close();
        NativeScreens.open(client, initial);
        if (client.level != null || client.getSingleplayerServer() != null)
          throw new AssertionError("Recipe qualification started a world");
        report.addProperty("status", "PASS");
        stage++;
        return true;
      }
      default -> throw new AssertionError("Unexpected advanced recipe stage");
    }
    return false;
  }

  @Override
  public JsonObject report() {
    return report;
  }

  @Override
  public void close() {
    flow.close();
    if (defaults != null) defaults.close();
    if (invalid != null) invalid.close();
  }
}
