package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.neoforge.client.ClientJsonSettings;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Public opt-in display metadata; the owner file continues storing ordinary canonical numbers. */
public final class NumericExample {
  public record Profile(
      int duration,
      int distance,
      double probability,
      double percent,
      int thirds,
      double tiny,
      int minimum,
      int maximum) {}

  private static final FieldKey<Integer> DURATION =
      FieldKey.of(
          "duration",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 12000), NumericDisplay.ticks(BigDecimal.valueOf(20))));
  private static final FieldKey<Integer> DISTANCE =
      FieldKey.of(
          "distance",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 4096), NumericDisplay.blocks().withSlider()));
  private static final FieldKey<Integer> MINIMUM = FieldKey.of("minimum", DURATION.type());
  private static final FieldKey<Integer> MAXIMUM = FieldKey.of("maximum", DURATION.type());
  public static final FormPresentation PRESENTATION =
      new FormPresentation(
          List.of(
              new FormPresentation.Range(
                  "duration_bounds",
                  "example.numeric.duration_bounds",
                  "Duration bounds",
                  "Both values are stored in ticks. Minimum must not exceed maximum; editing never moves the other field.",
                  FieldPath.ROOT.field("minimum"),
                  FieldPath.ROOT.field("maximum"))));
  private static final FieldKey<Double> PROBABILITY =
      FieldKey.of(
          "probability",
          ValueTypes.numericDisplay(
              ValueTypes.decimal(0, 1), NumericDisplay.probability().withSlider()));
  private static final FieldKey<Double> PERCENT =
      FieldKey.of(
          "percent",
          ValueTypes.numericDisplay(
              ValueTypes.decimal(0, 100), NumericDisplay.percentStored().withSlider()));
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
                          v.get(TINY),
                          v.get(MINIMUM),
                          v.get(MAXIMUM)))
              .field(DURATION, Profile::duration)
              .field(DISTANCE, Profile::distance)
              .field(PROBABILITY, Profile::probability)
              .field(PERCENT, Profile::percent)
              .field(THIRDS, Profile::thirds)
              .field(TINY, Profile::tiny)
              .field(MINIMUM, Profile::minimum)
              .field(MAXIMUM, Profile::maximum)
              .build(),
          () -> new Profile(20, 64, 0.35, 35, 3, 0.1, 10, 40),
          p ->
              p.minimum() <= p.maximum()
                  ? ValidationReport.VALID
                  : new ValidationReport(
                      List.of(
                          new Issue(
                              "example.range_order",
                              Issue.Severity.ERROR,
                              FieldPath.ROOT.field("minimum"),
                              List.of(FieldPath.ROOT.field("maximum")),
                              Map.of(),
                              "Minimum must not exceed maximum")),
                      true));
  private static ClientJsonSettings<Profile> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings = ClientJsonSettings.clientJson(bus, container, DEFINITION);
  }

  public static ClientJsonSettings<Profile> settings() {
    return Objects.requireNonNull(settings);
  }

  private NumericExample() {}
}
