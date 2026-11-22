package example.reform.platform;

import com.terraformersmc.modmenu.ModMenu;
import net.minecraft.client.gui.screens.Screen;

/** Optional API is linked only after the actual Fabric loader reports it present. */
final class NativeModMenuChecks {
  static Screen screen(Screen parent) {
    if (ModMenu.getConfigScreen("reform", parent) != null)
      throw new AssertionError("Library invented a configuration menu for its own identity");
    var form = ModMenu.getConfigScreen(PlatformServer.ID, parent);
    if (form == null) throw new AssertionError("Mod Menu lost the actual consumer entry");
    return form;
  }

  private NativeModMenuChecks() {}
}
