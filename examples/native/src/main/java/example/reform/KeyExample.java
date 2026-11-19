package example.reform;

import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Existing native Controls entries; the library must neither register nor replace them. */
public final class KeyExample {
  public static final Mapping PRIMARY = new Mapping();

  public static final class Mapping extends KeyMapping {
    public Runnable afterSet;

    private Mapping() {
      super("key.reform_native_example.primary", GLFW.GLFW_KEY_G, "key.categories.misc");
    }

    @Override
    public void setKeyModifierAndCode(
        net.neoforged.neoforge.client.settings.KeyModifier modifier,
        com.mojang.blaze3d.platform.InputConstants.Key key) {
      super.setKeyModifierAndCode(modifier, key);
      var callback = afterSet;
      afterSet = null;
      if (callback != null) callback.run();
    }
  }

  static void register(IEventBus bus) {
    bus.addListener((RegisterKeyMappingsEvent event) -> event.register(PRIMARY));
  }

  private KeyExample() {}
}
