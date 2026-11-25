package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.minecraft.client.ClientRegistration;
import java.util.concurrent.CompletionStage;

/** Actual registered task and field-selector paths; no replacement renderer or draft. */
final class PlatformReferencePresentationChecks implements PlatformFormProbe.Scene {
  private final PlatformFormProbe.Driver driver;
  private final JsonArray checks = new JsonArray();
  private PlatformTaskChecks task;
  private PlatformReferenceTitleChecks fields;
  private boolean complete;

  private PlatformReferencePresentationChecks(PlatformFormProbe.Driver driver) {
    this.driver = driver;
    task =
        new PlatformTaskChecks(
            (label, valid) -> {
              if (!valid) throw new AssertionError(label);
              checks.add(label);
            },
            driver::capture);
  }

  static CompletionStage<JsonObject> run(ClientRegistration registration) {
    return PlatformFormProbe.run(
        registration, "REFERENCES", PlatformReferencePresentationChecks::new);
  }

  public boolean tick() throws Exception {
    if (task != null) {
      if (!task.tick()) return false;
      task = null;
      fields = new PlatformReferenceTitleChecks(driver::progress, driver::capture);
    }
    if (!fields.tick()) return false;
    checks.addAll(fields.report().getAsJsonArray("checks"));
    complete = true;
    return true;
  }

  public JsonObject report() {
    var report = new JsonObject();
    report.addProperty("status", complete ? "PASS" : "RUNNING");
    report.add("checks", checks.deepCopy());
    if (fields != null) report.add("fieldSelector", fields.report());
    return report;
  }

  public int tickBudget() {
    return 1800;
  }
}
