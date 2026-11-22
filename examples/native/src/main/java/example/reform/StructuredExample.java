package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Recursive public factory example; no additional native menu, backend or domain compiler. */
public final class StructuredExample {
  public interface Mode {}

  public record Counter(int amount) implements Mode {}

  public record Named(String label) implements Mode {}

  public record Profile(
      List<Integer> numbers,
      Map<String, Integer> roles,
      Set<String> tags,
      Optional<String> note,
      int retries,
      Mode mode) {}

  private static final FieldKey<Integer> AMOUNT = FieldKey.of("amount", ValueTypes.integer(0, 100));
  private static final FieldKey<String> LABEL = FieldKey.of("label", ValueTypes.text(64));
  private static final ValueType<Mode> MODE =
      TaggedType.<Mode>builder("kind", "settings")
          .variant(
              "counter",
              Counter.class,
              RecordType.<Counter>builder(v -> new Counter(v.get(AMOUNT)))
                  .field(AMOUNT, Counter::amount)
                  .build())
          .variant(
              "named",
              Named.class,
              RecordType.<Named>builder(v -> new Named(v.get(LABEL)))
                  .field(LABEL, Named::label)
                  .build())
          .build();
  private static final FieldKey<List<Integer>> NUMBERS =
      FieldKey.of("numbers", ValueTypes.list(ValueTypes.integer(0, 100), 4));
  private static final FieldKey<Map<String, Integer>> ROLES =
      FieldKey.of(
          "roles", ValueTypes.objectMap(ValueTypes.text(32), ValueTypes.integer(0, 100), 4));
  private static final FieldKey<Set<String>> TAGS =
      FieldKey.of("tags", ValueTypes.set(ValueTypes.text(32), 4));
  private static final FieldKey<Optional<String>> NOTE =
      FieldKey.of(
          "note",
          ValueTypes.optional(ValueTypes.text(64), ValueShape.OptionalEncoding.SINGLETON_LIST));
  private static final FieldKey<Integer> RETRIES =
      FieldKey.of("retries", ValueTypes.defaultedField(ValueTypes.integer(0, 100), 7));
  private static final FieldKey<Mode> MODE_FIELD = FieldKey.of("mode", MODE);
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":structured"),
          1,
          RecordType.<Profile>builder(
                  v ->
                      new Profile(
                          v.get(NUMBERS),
                          v.get(ROLES),
                          v.get(TAGS),
                          v.get(NOTE),
                          v.get(RETRIES),
                          v.get(MODE_FIELD)))
              .field(NUMBERS, Profile::numbers)
              .field(ROLES, Profile::roles)
              .field(TAGS, Profile::tags)
              .field(NOTE, Profile::note)
              .field(RETRIES, Profile::retries)
              .field(MODE_FIELD, Profile::mode)
              .build(),
          () ->
              new Profile(
                  List.of(1, 2),
                  Map.of("anchor", 3),
                  Set.of("base"),
                  Optional.empty(),
                  7,
                  new Counter(4)));
  private static ClientFileSettings<Profile> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings =
        ClientFileSettings.clientJson(NeoForgeClientRegistration.of(bus, container), DEFINITION);
  }

  public static ClientFileSettings<Profile> settings() {
    return java.util.Objects.requireNonNull(settings);
  }

  private StructuredExample() {}
}
