package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;

/** Ordinary optional and eager defaults; a separate malformed source exercises explicit repair. */
final class PlatformWorldDefaultsExample {
  private static ClientFileSettings<PlatformCreationExample.Recipe> optional, invalid, eager;

  static void register(ClientRegistration registration) {
    optional = optional(registration, "future_defaults");
    invalid = optional(registration, "future_invalid");
    eager =
        ClientFileSettings.newWorldDefaultsJson(registration, definition("initial_defaults", 4));
    optional.registerCommands("reformdefaults");
  }

  private static DocumentDefinition<PlatformCreationExample.Recipe> definition(
      String name, int depth) {
    return new DocumentDefinition<>(
        DocumentId.parse(PlatformServer.ID + ":" + name),
        1,
        PlatformCreationExample.DEFINITION.type(),
        () -> new PlatformCreationExample.Recipe(depth));
  }

  private static ClientFileSettings<PlatformCreationExample.Recipe> optional(
      ClientRegistration registration, String name) {
    return ClientFileSettings.newWorldDefaultsFile(
        registration,
        new JsonStore<>(
            definition(name, 5),
            new OwnedFile(
                StorageBinding.relative(
                    registration.configurationDirectory().toAbsolutePath(),
                    registration.modId(),
                    name + ".json"),
                262144),
            JsonStore.Format.EXISTING_BARE,
            new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
            DraftLimits.CONFIGURATION),
        FileOwner.Initialization.DEFER_MISSING);
  }

  static ClientFileSettings<PlatformCreationExample.Recipe> optional() {
    return optional;
  }

  static ClientFileSettings<PlatformCreationExample.Recipe> invalid() {
    return invalid;
  }

  static ClientFileSettings<PlatformCreationExample.Recipe> eager() {
    return eager;
  }

  private PlatformWorldDefaultsExample() {}
}
