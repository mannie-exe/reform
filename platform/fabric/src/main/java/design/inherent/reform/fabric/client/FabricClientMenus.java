package design.inherent.reform.fabric.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.gui.screens.Screen;

/** Menu declarations remain useful for composition; optional Mod Menu supplies its native entry. */
final class FabricClientMenus {
  private static final Map<String, Function<Screen, Screen>> factories = new LinkedHashMap<>();
  private static boolean closed;

  static synchronized void register(String modId, Function<Screen, Screen> factory) {
    Objects.requireNonNull(factory);
    // Container providers are lazy metadata here: do not instantiate absent optional APIs.
    for (var entry :
        net.fabricmc.loader.api.FabricLoader.getInstance()
            .getEntrypointContainers("modmenu", Object.class))
      if (entry.getProvider().getMetadata().getId().equals(modId))
        throw new IllegalStateException(
            "This mod declares a Mod Menu entry point; compose re:form through that entry instead");
    if (closed || factories.size() >= 128)
      throw new IllegalStateException("Client menu registration is closed or full");
    if (factories.putIfAbsent(modId, factory) != null)
      throw new IllegalStateException("This mod already declares a re:form configuration entry");
  }

  static synchronized Map<String, Function<Screen, Screen>> factories() {
    return Map.copyOf(factories);
  }

  static synchronized void close() {
    closed = true;
    factories.clear();
  }

  private FabricClientMenus() {}
}
