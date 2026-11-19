package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.owner.FileOwner;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import java.util.Objects;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;

/** Future-world defaults retain their source; importing into a recipe remains explicit. */
public final class WorldDefaultsExample {
  public static final DocumentDefinition<CreationExample.Recipe> OPTIONAL =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":future_defaults"),
          1,
          CreationExample.DEFINITION.type(),
          () -> new CreationExample.Recipe(5));
  public static final DocumentDefinition<CreationExample.Recipe> EAGER =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":initial_defaults"),
          1,
          CreationExample.DEFINITION.type(),
          () -> new CreationExample.Recipe(4));
  private static ClientFileSettings<CreationExample.Recipe> optional, eager;

  static void register(IEventBus bus, ModContainer container) {
    optional =
        ClientFileSettings.newWorldDefaultsFile(
            bus,
            container,
            new JsonStore<>(
                OPTIONAL,
                new OwnedFile(
                    StorageBinding.relative(
                        FMLPaths.CONFIGDIR.get().toAbsolutePath(),
                        NativeExample.ID,
                        "future_defaults.json"),
                    262144),
                JsonStore.Format.EXISTING_BARE,
                new JsonLimits(262144, 64, 16384, 32768, 128, 4096),
                DraftLimits.CONFIGURATION),
            FileOwner.Initialization.DEFER_MISSING);
    eager = ClientFileSettings.newWorldDefaultsJson(bus, container, EAGER);
    optional.registerCommands("reformdefaults");
  }

  public static ClientFileSettings<CreationExample.Recipe> optional() {
    return Objects.requireNonNull(optional);
  }

  public static ClientFileSettings<CreationExample.Recipe> eager() {
    return Objects.requireNonNull(eager);
  }

  private WorldDefaultsExample() {}
}
