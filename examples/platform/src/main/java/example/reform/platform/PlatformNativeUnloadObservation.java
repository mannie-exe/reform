package example.reform.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded passive native shutdown evidence; never completes or changes a native future. */
public final class PlatformNativeUnloadObservation {
  public static volatile boolean armed;
  private static final JsonArray samples = new JsonArray();

  static void arm() {
    armed = true;
  }

  public static synchronized void sample(JsonObject value) {
    if (!armed || samples.size() >= 6) return;
    samples.add(value);
    var root = new JsonObject();
    root.addProperty("scope", "Read-only native chunk unload readiness after fixture disconnect");
    root.add("samples", samples.deepCopy());
    try {
      Files.writeString(Path.of("platform-native-unload.json"), root.toString());
    } catch (java.io.IOException failed) {
      System.err.println("Unable to retain native unload observation: " + failed);
    }
  }

  private PlatformNativeUnloadObservation() {}
}
