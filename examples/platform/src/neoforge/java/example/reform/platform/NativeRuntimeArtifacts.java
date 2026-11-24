package example.reform.platform;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import net.neoforged.fml.ModList;

final class NativeRuntimeArtifacts {
  private NativeRuntimeArtifacts() {}

  static JsonObject read() throws Exception {
    var roots = new LinkedHashMap<String, Path>();
    for (var id : List.of("reform", "jei", "emi")) {
      var mod = ModList.get().getModFileById(id);
      if (mod != null) roots.put(id, mod.getFile().getFilePath());
    }
    return PlatformRuntimeArtifacts.capture(roots);
  }
}
