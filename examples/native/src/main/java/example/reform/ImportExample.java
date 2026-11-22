package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Ordinary client registration; import uses the generated form and shared public services. */
public final class ImportExample {
  public record Profile(int count, String label, List<String> roles, Optional<String> note) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 10));
  private static final FieldKey<String> LABEL = FieldKey.of("label", ValueTypes.text(128));
  private static final FieldKey<List<String>> ROLES =
      FieldKey.of("roles", ValueTypes.list(ValueTypes.text(64), 8));
  private static final FieldKey<Optional<String>> NOTE =
      FieldKey.of(
          "note",
          ValueTypes.optional(ValueTypes.text(128), ValueShape.OptionalEncoding.SINGLETON_LIST));
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":imports"),
          2,
          RecordType.<Profile>builder(
                  v -> new Profile(v.get(COUNT), v.get(LABEL), v.get(ROLES), v.get(NOTE)))
              .field(COUNT, Profile::count)
              .field(LABEL, Profile::label)
              .field(ROLES, Profile::roles)
              .field(NOTE, Profile::note)
              .build(),
          () -> new Profile(1, "Original", List.of("old"), Optional.empty()));
  private static ClientFileSettings<Profile> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings =
        ClientFileSettings.clientJson(NeoForgeClientRegistration.of(bus, container), DEFINITION);
    var preset =
        ImportSource.snapshot(
            DocumentId.parse(NativeExample.ID + ":sample_profile"),
            new ImportInput(
                "Example profile",
                "{\"count\":3,\"label\":\"Example\",\"roles\":[],\"note\":[]}"
                    .getBytes(StandardCharsets.UTF_8),
                1024));
    settings.registerCommands("reformimports", settings.importCatalog(List.of(preset)));
  }

  public static ClientFileSettings<Profile> settings() {
    return java.util.Objects.requireNonNull(settings);
  }

  public static Screen screen(Screen parent) {
    return settings.screen(parent, Component.literal("Native profile import"));
  }

  private ImportExample() {}
}
