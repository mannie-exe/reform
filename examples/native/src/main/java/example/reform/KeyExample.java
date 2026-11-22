package example.reform;

import com.mojang.blaze3d.platform.InputConstants;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.ClientKeyBindings;
import design.inherent.reform.minecraft.client.KeyBindingEditors;
import design.inherent.reform.minecraft.client.KeyBindingOptions;
import design.inherent.reform.minecraft.client.SettingsScreen;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.presentation.FormPresentation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Existing native Controls entries; the library must neither register nor replace them. */
public final class KeyExample {
  public static final DocumentId DOCUMENT = DocumentId.parse("reform_native_example:native_keys");
  public static final Mapping PRIMARY = new Mapping();
  public static final KeyMapping SECONDARY =
      new KeyMapping("key.reform_native_example.secondary", GLFW.GLFW_KEY_H, "key.categories.misc");
  private static ClientKeyBindings bindings;
  private static int selections;

  public static final class Mapping extends KeyMapping {
    public Runnable afterSet;

    private Mapping() {
      super("key.reform_native_example.primary", GLFW.GLFW_KEY_G, "key.categories.misc");
    }

    @Override
    public void setKeyModifierAndCode(KeyModifier modifier, InputConstants.Key key) {
      super.setKeyModifierAndCode(modifier, key);
      var callback = afterSet;
      afterSet = null;
      if (callback != null) callback.run();
    }
  }

  static void register(IEventBus bus, ModContainer container) {
    bus.addListener(
        (RegisterKeyMappingsEvent event) -> {
          event.register(PRIMARY);
          event.register(SECONDARY);
        });
    bindings =
        ClientKeyBindings.clientKeys(
            NeoForgeClientRegistration.of(bus, container),
            DOCUMENT,
            () -> {
              selections++;
              return List.of(PRIMARY, SECONDARY);
            });
    bindings.registerCommands("reformkeys");
  }

  public static ClientKeyBindings settings() {
    return Objects.requireNonNull(bindings, "Native bindings have not registered");
  }

  public static int selections() {
    return selections;
  }

  /** Ordinary native use supplies no executor, codec, serializer or editing infrastructure. */
  public static Screen screen(Screen parent) {
    return settings().screen(parent, Component.literal("Native bindings"));
  }

  /** Existing native bindings use the same generated form and operations as ordinary documents. */
  public static SettingsScreen<Map<String, KeyBindingOptions.Binding>> screen(
      Screen parent,
      KeyBindingOptions.Session session,
      DocumentActions<Map<String, KeyBindingOptions.Binding>> actions) {
    return new SettingsScreen<>(
        parent,
        Component.literal("Native bindings"),
        Component.literal("Actual native Controls / options.txt"),
        session.definition(),
        actions,
        Map.of(),
        FormPresentation.EMPTY,
        KeyBindingEditors.forSession(session));
  }

  private KeyExample() {}
}
