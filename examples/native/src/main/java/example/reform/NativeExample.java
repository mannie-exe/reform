package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.value.ValueTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

/** An ordinary consumer: no Compat, Skyweave or modpack linkage. */
@Mod(NativeExample.ID)
public final class NativeExample {
  public static final String ID = "reform_native_example";

  public record Preferences(boolean enabled, int distance, String label) {}

  public static final DocumentDefinition<Preferences> DEFINITION = definition();

  public NativeExample(IEventBus bus, ModContainer container) {
    if (FMLEnvironment.dist == Dist.CLIENT) ClientExample.register(bus, container);
  }

  private static DocumentDefinition<Preferences> definition() {
    return new DocumentDefinition<>(
        DocumentId.parse(ID + ":preferences"),
        1,
        RecordTypes.record(Preferences.class)
            .field("distance", FieldKey.of("distance", ValueTypes.integer(1, 512)))
            .field("label", FieldKey.of("label", ValueTypes.text(128)))
            .build(),
        () -> new Preferences(true, 64, "Example"));
  }
}
