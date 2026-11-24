package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.minecraft.configuration.ServerSettings;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.ZipFile;

/** Verification-only archive identities from actual loader roots, not echoed Maven input hashes. */
final class PlatformRuntimeArtifacts {
  private static final Map<String, String> WITNESSES =
      Map.of(
          "reform", "design/inherent/reform/minecraft/configuration/ServerSettings.class",
          "jei", "mezz/jei/api/IModPlugin.class",
          "emi", "dev/emi/emi/api/EmiApi.class");

  private PlatformRuntimeArtifacts() {}

  static JsonObject capture(Map<String, Path> roots) throws Exception {
    if (!roots.containsKey("reform"))
      throw new IllegalStateException("Missing library loader root");
    var result = new JsonObject();
    for (var entry : roots.entrySet()) {
      var archive = entry.getValue().toRealPath();
      if (!Files.isRegularFile(archive))
        throw new IllegalStateException("Runtime mod is not a packaged archive: " + archive);
      long size = Files.size(archive);
      if (size <= 0 || size > 64L * 1024 * 1024)
        throw new IllegalStateException("Runtime archive exceeds fixture allowance: " + archive);
      var fact = new JsonObject();
      fact.addProperty("path", archive.toString());
      fact.addProperty("bytes", size);
      try (var input = Files.newInputStream(archive)) {
        fact.addProperty("sha256", hash(input, 64L * 1024 * 1024));
      }
      var witness = WITNESSES.get(entry.getKey());
      if (witness == null) throw new IllegalArgumentException("Unadmitted runtime witness");
      String packaged;
      try (var zip = new ZipFile(archive.toFile())) {
        var member = zip.getEntry(witness);
        if (member == null) throw new IllegalStateException("Runtime archive lacks " + witness);
        try (var input = zip.getInputStream(member)) {
          packaged = hash(input, 4L * 1024 * 1024);
        }
      }
      var resource =
          entry.getKey().equals("reform")
              ? ServerSettings.class.getResource("ServerSettings.class")
              : PlatformRuntimeArtifacts.class.getClassLoader().getResource(witness);
      if (resource == null) throw new IllegalStateException("Missing runtime resource " + witness);
      try (var input = resource.openStream()) {
        if (!packaged.equals(hash(input, 4L * 1024 * 1024)))
          throw new IllegalStateException("Loader archive and class resource differ: " + witness);
      }
      fact.addProperty("witness", witness);
      fact.addProperty("witnessSha256", packaged);
      fact.addProperty("resource", resource.toString());
      fact.addProperty("resourceMatchesArchive", true);
      result.add(entry.getKey(), fact);
    }
    return result;
  }

  private static String hash(InputStream input, long allowance) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    var buffer = new byte[8192];
    long total = 0;
    for (int count; (count = input.read(buffer)) != -1; ) {
      total += count;
      if (total > allowance)
        throw new IllegalStateException("Runtime identity input exceeds bound");
      digest.update(buffer, 0, count);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
