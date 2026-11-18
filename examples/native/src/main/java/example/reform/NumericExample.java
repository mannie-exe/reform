package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.neoforge.client.ClientJsonSettings;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Public opt-in display metadata; the owner file continues storing ordinary canonical numbers. */
public final class NumericExample {
  public record Profile(
      int duration, int distance, double probability, double percent, int thirds, double tiny) {}

  private static final FieldKey<Integer> DURATION =
      FieldKey.of(
          "duration",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 12000), NumericDisplay.ticks(BigDecimal.valueOf(20))));
  private static final FieldKey<Integer> DISTANCE =
      FieldKey.of(
          "distance",
          ValueTypes.numericDisplay(ValueTypes.integer(0, 4096), NumericDisplay.blocks()));
  private static final FieldKey<Double> PROBABILITY =
      FieldKey.of(
          "probability",
          ValueTypes.numericDisplay(ValueTypes.decimal(0, 1), NumericDisplay.probability()));
  private static final FieldKey<Double> PERCENT =
      FieldKey.of(
          "percent",
          ValueTypes.numericDisplay(ValueTypes.decimal(0, 100), NumericDisplay.percentStored()));
  private static final FieldKey<Integer> THIRDS =
      FieldKey.of(
          "thirds",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 100),
              new NumericDisplay(
                  "base",
                  List.of(
                      new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                      new NumericDisplay.Unit("triple", "triple", BigDecimal.valueOf(3))),
                  BigDecimal.ONE,
                  Optional.empty(),
                  DecimalText.Limits.CONFIGURATION)));
  private static final FieldKey<Double> TINY =
      FieldKey.of(
          "tiny",
          ValueTypes.numericDisplay(
              ValueTypes.decimal(0, 1),
              new NumericDisplay(
                  "base",
                  List.of(
                      new NumericDisplay.Unit("base", "base", BigDecimal.ONE),
                      new NumericDisplay.Unit("tiny", "tiny", new BigDecimal("1e-4096"))),
                  BigDecimal.ONE,
                  Optional.empty(),
                  DecimalText.Limits.CONFIGURATION)));
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":numeric"),
          1,
          RecordType.<Profile>builder(
                  v ->
                      new Profile(
                          v.get(DURATION),
                          v.get(DISTANCE),
                          v.get(PROBABILITY),
                          v.get(PERCENT),
                          v.get(THIRDS),
                          v.get(TINY)))
              .field(DURATION, Profile::duration)
              .field(DISTANCE, Profile::distance)
              .field(PROBABILITY, Profile::probability)
              .field(PERCENT, Profile::percent)
              .field(THIRDS, Profile::thirds)
              .field(TINY, Profile::tiny)
              .build(),
          () -> new Profile(20, 64, 0.35, 35, 3, 0.1));
  private static ClientJsonSettings<Profile> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings = ClientJsonSettings.clientJson(bus, container, DEFINITION);
  }

  public static ClientJsonSettings<Profile> settings() {
    return Objects.requireNonNull(settings);
  }

  private NumericExample() {}
}
