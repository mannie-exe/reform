package example.reformprobe;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import example.reform.NativeExample;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Real dedicated-side loading and public record derivation, without resolving client entry points.
 */
@Mod(value = "reform_native_example_probe", dist = Dist.DEDICATED_SERVER)
public final class NativeAddonServerProbe {
  public NativeAddonServerProbe(
      net.neoforged.bus.api.IEventBus bus, net.neoforged.fml.ModContainer container) {
    if (Files.isRegularFile(java.nio.file.Path.of("registered-probe-mode.json")))
      new RegisteredOwnerProbe(
          bus,
          container,
          net.neoforged.fml.config.ModConfig.Type.COMMON,
          NativeReferenceChecks::verify);
    else if (Files.isRegularFile(java.nio.file.Path.of("server-policy-probe-mode.json")))
      new NativeServerPolicyProbe();
    else NeoForge.EVENT_BUS.addListener(this::started);
  }

  private void started(ServerStartedEvent event) {
    var report = new JsonObject();
    report.addProperty("status", "FAIL");
    try {
      var artifact = ModList.get().getModFileById("reform").getFile().getFilePath();
      report.addProperty("libraryPath", artifact.toString());
      report.addProperty(
          "librarySha256",
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
      var defaults = NativeExample.DEFINITION.defaults();
      if (!defaults.equals(new NativeExample.Preferences(true, 64, "Example")))
        throw new AssertionError("Common derived record definition failed");
      var encoded = NativeExample.DEFINITION.encode(defaults).getOrThrow();
      if (encoded.getAsJsonObject().get("distance").getAsInt() != 64)
        throw new AssertionError("Common record codec did not retain its typed value");
      var path = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(NativeExample.ID);
      if (Files.exists(path))
        throw new AssertionError("Client helper initialized a document on the dedicated server");
      report.addProperty("clientDocumentAbsent", true);
      report.addProperty("derivedRecordCommonCodec", true);
      report.addProperty("nativeServerStarted", true);
      report.addProperty("status", "PASS");
    } catch (Throwable failure) {
      report.addProperty("failure", failure.toString());
    } finally {
      try {
        Files.writeString(
            event.getServer().getServerDirectory().resolve("native-addon-server.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(report));
      } catch (Exception failed) {
        throw new IllegalStateException("Could not retain native addon server evidence", failed);
      } finally {
        event.getServer().halt(false);
      }
    }
  }
}
