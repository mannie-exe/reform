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
    private int ticks;
    private long captureBaseline;
    private net.minecraft.client.gui.screens.Screen captureScreen;
    private final JsonObject captureFrames = new JsonObject();

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
      captureBaseline = PlatformFrameObservations.sequence();
      captureScreen =
          design.inherent.reform.minecraft.client.NativeScreens.current(Minecraft.getInstance());
    }

    private void tick() {
      try {
        if (Files.exists(Path.of("platform-cancel")))
          throw new AssertionError("Native form fixture cancelled");
        if (++ticks > scene.tickBudget())
          throw new AssertionError("Native form timed out: " + scene.diagnostic());
        if (!NativeClientProbe.ready(Minecraft.getInstance())) {
          captureBaseline = PlatformFrameObservations.sequence();
          return;
        }
        if (captureName != null) {
          if (design.inherent.reform.minecraft.client.NativeScreens.current(Minecraft.getInstance())
              != captureScreen)
            throw new AssertionError("Native capture screen changed: " + captureName);
          if (PlatformFrameObservations.sequence() - captureBaseline < 4) return;
          if (capture == null)
            capture = NativeClientProbe.capture(Minecraft.getInstance(), Path.of(captureName));
          if (!capture.isDone()) return;
          capture.join();
          captures.add(captureName);
          var observation = new JsonObject();
          observation.addProperty("requestedAfterFrame", captureBaseline);
          observation.addProperty("capturedAfterFrame", PlatformFrameObservations.sequence());
          observation.addProperty(
              "screen", captureScreen == null ? "none" : captureScreen.getClass().getName());
          captureFrames.add(captureName, observation);
          var action = afterCapture;
          captureName = null;
          afterCapture = null;
          capture = null;
          captureScreen = null;
          action.run();
        }
        if (scene.tick()) {
          var report = scene.report();
          report.add("captures", captures);
          report.add("captureFrames", captureFrames.deepCopy());
          scene.close();
          subscription.close();
          result.complete(report);
        }
      } catch (Exception | AssertionError failed) {
        retainFailure(failed);
        scene.close();
        subscription.close();
        result.completeExceptionally(failed);
      }
    }

    private void retainFailure(Throwable failed) {
      try {
        var failure = new JsonObject();
        failure.addProperty("status", "FAIL");
        failure.addProperty("scene", label);
        failure.addProperty("diagnostic", scene.diagnostic());
        failure.addProperty("failure", failed.toString());
        failure.add("partial", scene.report());
        failure.add("captures", captures);
        failure.add("captureFrames", captureFrames.deepCopy());
        var screen =
            design.inherent.reform.minecraft.client.NativeScreens.current(Minecraft.getInstance());
        if (screen != null) {
          failure.addProperty("screen", screen.getClass().getSimpleName());
          failure.addProperty("narration", screen.getNarrationMessage().getString());
        }
        Files.writeString(
            Minecraft.getInstance().gameDirectory.toPath().resolve("platform-scene-failure.json"),
            failure.toString());
      } catch (Exception observationFailed) {
        failed.addSuppressed(observationFailed);
      }
    }
  }

  private PlatformFormProbe() {}
}
