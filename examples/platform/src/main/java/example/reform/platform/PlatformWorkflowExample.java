package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.minecraft.client.ClientEditingWorkflow;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientForm;
import design.inherent.reform.minecraft.client.ClientKeyBindings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Four public owners; the loader supplies real registered or portable local sources. */
public final class PlatformWorkflowExample {
  public record Preferences(boolean enabled, int distance, String label) {}

  static final DocumentDefinition<Preferences> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(PlatformServer.ID + ":workflow_preferences"),
          1,
          RecordTypes.record(Preferences.class)
              .field("distance", FieldKey.of("distance", ValueTypes.integer(1, 512)))
              .field("label", FieldKey.of("label", ValueTypes.text(128)))
              .build(),
          () -> new Preferences(true, 64, "Example"));
  static final DocumentId KEYS = DocumentId.parse(PlatformServer.ID + ":workflow_keys");
  static final KeyMapping PRIMARY =
      NativeClientProbe.mapping("key.reform_platform.workflow_primary");
  static final KeyMapping SECONDARY =
      NativeClientProbe.mapping("key.reform_platform.workflow_secondary", "key.keyboard.h");
  private static ClientFileSettings<Preferences> preferences;
  private static ClientKeyBindings keys;
  private static int selections;

  static void register(ClientRegistration registration) {
    preferences = ClientFileSettings.clientJson(registration, DEFINITION);
    keys =
        ClientKeyBindings.clientKeys(
            registration,
            KEYS,
            () -> {
              selections++;
              return List.of(PRIMARY, SECONDARY);
            });
    PlatformWorkflowSources.register(registration);
  }

  static List<KeyMapping> registeredMappings() {
    return keys == null ? List.of() : List.of(PRIMARY, SECONDARY);
  }

  static ClientFileSettings<Preferences> settings() {
    return java.util.Objects.requireNonNull(preferences);
  }

  static ClientKeyBindings keys() {
    return java.util.Objects.requireNonNull(keys);
  }

  static int selections() {
    return selections;
  }

  static List<ClientForm> forms() {
    return List.of(
        settings().form(Component.literal("Client preferences")),
        keys().form(Component.literal("Native Controls")),
        PlatformWorkflowSources.client(),
        PlatformWorkflowSources.defaults());
  }

  static ClientEditingWorkflow open(Screen parent) {
    return ClientEditingWorkflow.open(parent, Component.literal("Composed settings"), forms());
  }

  private PlatformWorkflowExample() {}
}
