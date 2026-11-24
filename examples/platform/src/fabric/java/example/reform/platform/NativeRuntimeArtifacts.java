package example.reform.platform;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

final class NativeRuntimeArtifacts {
  private NativeRuntimeArtifacts() {}

  static JsonObject read() throws Exception {
    var roots = new LinkedHashMap<String, Path>();
    for (var id : List.of("reform", "jei", "emi")) {
      var mod = FabricLoader.getInstance().getModContainer(id);
      if (mod.isEmpty()) continue;
      var actual = mod.orElseThrow().getRootPaths();
      if (actual.size() != 1) throw new IllegalStateException("Ambiguous runtime roots for " + id);
      var uri = actual.getFirst().toUri();
      var nested = uri.getRawSchemeSpecificPart();
      int end = nested.indexOf("!/");
      if (!uri.getScheme().equals("jar") || end < 0)
        throw new IllegalStateException("Expected a packaged runtime root: " + uri);
      roots.put(id, Path.of(URI.create(nested.substring(0, end))));
    }
    return PlatformRuntimeArtifacts.capture(roots);
  }
}
