package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;

/** Ordinary typed consumer data, independent of game/client/loader classes. */
public final class PlatformData {
  public record Values(int distance, String label) {}

  public static DocumentDefinition<Values> definition(String name) {
    return definition(name, ValueTypes.integer(1, 64));
  }

  public static DocumentDefinition<Values> definition(
      String name, ValueType<Integer> distanceType) {
    return new DocumentDefinition<>(
        DocumentId.parse("reform_platform_example:" + name),
        1,
        RecordTypes.record(Values.class)
            .field("distance", FieldKey.of("distance", distanceType))
            .field("label", FieldKey.of("label", ValueTypes.text(64)))
            .build(),
        () -> new Values(7, "platform"));
  }

  private PlatformData() {}
}
