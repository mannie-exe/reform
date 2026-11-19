package example.reform;

import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/** Existing native Controls entries; the library must neither register nor replace them. */
public final class KeyExample {
  public static final Mapping PRIMARY = new Mapping();
  public static final KeyMapping SECONDARY =
      new KeyMapping("key.reform_native_example.secondary", GLFW.GLFW_KEY_H, "key.categories.misc");

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
    bus.addListener(
        (RegisterKeyMappingsEvent event) -> {
          event.register(PRIMARY);
          event.register(SECONDARY);
        });
  }

  /** Existing native bindings use the same generated form and operations as ordinary documents. */
  public static design.inherent.reform.neoforge.client.SettingsScreen<
          java.util.Map<String, design.inherent.reform.neoforge.client.KeyBindingOptions.Binding>>
      screen(
          net.minecraft.client.gui.screens.Screen parent,
          design.inherent.reform.neoforge.client.KeyBindingOptions.Session session,
          design.inherent.reform.operation.DocumentActions<
                  java.util.Map<
                      String, design.inherent.reform.neoforge.client.KeyBindingOptions.Binding>>
              actions) {
    return new design.inherent.reform.neoforge.client.SettingsScreen<>(
        parent,
        net.minecraft.network.chat.Component.literal("Native bindings"),
        net.minecraft.network.chat.Component.literal("Actual native Controls / options.txt"),
        session.definition(),
        actions,
        java.util.Map.of(),
        design.inherent.reform.presentation.FormPresentation.EMPTY,
        design.inherent.reform.neoforge.client.KeyBindingEditors.forSession(session));
  }

  private KeyExample() {}
}
