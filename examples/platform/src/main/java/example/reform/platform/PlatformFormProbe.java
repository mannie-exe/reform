package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import design.inherent.reform.minecraft.client.ClientRegistration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.client.Minecraft;

/** Shared disposable native-form driver; not a product owner or alternate settings frontend. */
final class PlatformFormProbe {
  interface Scene {
    boolean tick() throws Exception;

    JsonObject report();

    default void close() {}

    default int tickBudget() {
      return 600;
    }

    default String diagnostic() {
      return getClass().getSimpleName();
    }
  }

  @FunctionalInterface
  interface SceneFactory {
    Scene open(Driver driver) throws Exception;
  }

  static CompletionStage<JsonObject> run(
      ClientRegistration registration, String label, SceneFactory open) {
    var driver = new Driver(label);
    try {
      driver.scene = open.open(driver);
      driver.subscription = registration.onTick(driver::tick);
    } catch (Exception | AssertionError failed) {
      if (driver.scene != null) driver.scene.close();
      driver.result.completeExceptionally(failed);
    }
    return driver.result.minimalCompletionStage();
  }

  static final class Driver {
    private final String label;
    private final CompletableFuture<JsonObject> result = new CompletableFuture<>();
    private final JsonArray captures = new JsonArray();
    private Scene scene;
    private ClientRegistration.Subscription subscription;
    private String captureName;
    private Runnable afterCapture;
    private CompletableFuture<Void> capture;
    private int ticks, frames;

    private Driver(String label) {
      this.label = label;
    }

    void progress(String message) {
      System.out.println("REFORM_" + label + " " + message);
    }

    void capture(String name, Runnable action) {
      if (captureName != null) throw new AssertionError("Overlapping native capture");
      captureName = "platform-" + name;
      afterCapture = action;
      frames = 0;
    }

    private void tick() {
      try {
        if (Files.exists(Path.of("platform-cancel")))
          throw new AssertionError("Native form fixture cancelled");
        if (++ticks > scene.tickBudget())
          throw new AssertionError("Native form timed out: " + scene.diagnostic());
        if (!NativeClientProbe.ready(Minecraft.getInstance())) {
          frames = 0;
          return;
        }
        if (captureName != null) {
          if (++frames < 4) return;
          if (capture == null)
            capture = NativeClientProbe.capture(Minecraft.getInstance(), Path.of(captureName));
          if (!capture.isDone()) return;
          capture.join();
          captures.add(captureName);
          var action = afterCapture;
          captureName = null;
          afterCapture = null;
          capture = null;
          action.run();
        }
        if (scene.tick()) {
          var report = scene.report();
          report.add("captures", captures);
          scene.close();
          subscription.close();
          result.complete(report);
        }
      } catch (Exception | AssertionError failed) {
        scene.close();
        subscription.close();
        result.completeExceptionally(failed);
      }
    }
  }

  private PlatformFormProbe() {}
}
