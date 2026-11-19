package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.neoforge.reference.NativeReferences;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Independent list/set multi-selection using admitted native sources shared by two documents. */
public final class CollectionExample {
  public enum Tone {
    CALM,
    BRIGHT,
    CAFE
  }

  public record Profile(
      List<Tone> tones,
      Set<Boolean> flags,
      List<String> blocks,
      Set<String> resources,
      List<String> optional,
      List<String> unbound) {}

  private static final FieldKey<List<Tone>> TONES =
      FieldKey.of("tones", ValueTypes.list(ValueTypes.enumeration(Tone.class), 12));
  private static final FieldKey<Set<Boolean>> FLAGS =
      FieldKey.of("flags", ValueTypes.set(ValueTypes.bool(), 4));
  private static final FieldKey<List<String>> BLOCKS =
      FieldKey.of(
          "blocks", ValueTypes.list(NativeReferences.type(ReferenceExample.BLOCKS, true, 512), 64));
  private static final FieldKey<Set<String>> RESOURCES =
      FieldKey.of(
          "resources",
          ValueTypes.set(NativeReferences.type(ReferenceExample.RESOURCES, false, 512), 64));
  private static final FieldKey<List<String>> OPTIONAL =
      FieldKey.of(
          "optional",
          ValueTypes.list(NativeReferences.type(ReferenceExample.OPTIONAL, true, 512), 8));
  private static final FieldKey<List<String>> UNBOUND =
      FieldKey.of(
          "unbound",
          ValueTypes.list(NativeReferences.type(ReferenceExample.UNBOUND, false, 512), 8));
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":collections"),
          1,
          RecordType.<Profile>builder(
                  v ->
                      new Profile(
                          v.get(TONES),
                          v.get(FLAGS),
                          v.get(BLOCKS),
                          v.get(RESOURCES),
                          v.get(OPTIONAL),
                          v.get(UNBOUND)))
              .field(TONES, Profile::tones)
              .field(FLAGS, Profile::flags)
              .field(BLOCKS, Profile::blocks)
              .field(RESOURCES, Profile::resources)
              .field(OPTIONAL, Profile::optional)
              .field(UNBOUND, Profile::unbound)
              .build(),
          () ->
              new Profile(
                  List.of(Tone.CALM, Tone.CALM),
                  Set.of(false),
                  List.of("future:unknown", "minecraft:stone"),
                  Set.of("minecraft:missing_resource"),
                  List.of("optional:missing"),
                  List.of("minecraft:manual")));
  private static ClientFileSettings<Profile> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings = ClientFileSettings.clientJson(bus, container, DEFINITION);
  }

  public static ClientFileSettings<Profile> settings() {
    return java.util.Objects.requireNonNull(settings);
  }

  public static Screen screen(Screen parent) {
    return settings.screen(
        parent, Component.literal("Native collection choices"), ReferenceExample.sources());
  }

  private CollectionExample() {}
}
