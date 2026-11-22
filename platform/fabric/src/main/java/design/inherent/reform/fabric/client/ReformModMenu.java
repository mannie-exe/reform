package design.inherent.reform.fabric.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loaded only by optional Mod Menu; the library supplies no menu for its own identity. */
public final class ReformModMenu implements ModMenuApi {
  @Override
  public Map<String, ConfigScreenFactory<?>> getProvidedConfigScreenFactories() {
    var result = new LinkedHashMap<String, ConfigScreenFactory<?>>();
    FabricClientMenus.factories().forEach((id, factory) -> result.put(id, factory::apply));
    return Map.copyOf(result);
  }
}
