package example.reform.platform;

import com.google.gson.JsonObject;
import design.inherent.reform.neoforge.Reform;
import net.neoforged.fml.ModList;

/** Actual optional runtime inspection, confined to the independent native fixture. */
final class NativeConnectorProbe {
  static JsonObject verify() throws ClassNotFoundException {
    var mods = ModList.get();
    var connector = mods.getModContainerById("connector").orElseThrow();
    var fabric = mods.getModContainerById("fabric_api").orElseThrow();
    var library = mods.getModContainerById("reform").orElseThrow();
    if (!Reform.class
        .getAnnotation(net.neoforged.fml.common.Mod.class)
        .value()
        .equals(library.getModId()))
      throw new AssertionError("Library entry metadata disagrees with its native identity");
    long identities =
        mods.getMods().stream().filter(mod -> mod.getModId().equals("reform")).count();
    if (identities != 1) throw new AssertionError("Duplicate library bootstrap identity");
    var foreign =
        Class.forName(
            "net.fabricmc.loader.api.FabricLoader",
            false,
            NativeConnectorProbe.class.getClassLoader());
    try {
      Class.forName(
          "design.inherent.reform.fabric.ReformFabric",
          false,
          NativeConnectorProbe.class.getClassLoader());
      throw new AssertionError("NeoForge product exposed a second loader bootstrap");
    } catch (ClassNotFoundException expected) {
      // Foreign platform classes exist; the selected library still contains only its own adapter.
    }
    var report = new JsonObject();
    report.addProperty("status", "PASS");
    report.addProperty("connectorVersion", connector.getModInfo().getVersion().toString());
    report.addProperty("fabricApiVersion", fabric.getModInfo().getVersion().toString());
    report.addProperty("foreignLoaderApi", foreign.getName());
    report.addProperty("bootstrapClass", Reform.class.getName());
    report.addProperty("libraryIdentities", identities);
    return report;
  }

  private NativeConnectorProbe() {}
}
