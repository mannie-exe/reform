package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.DecimalText;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.NumericDomain;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Public opt-in display metadata; the owner file continues storing ordinary canonical numbers. */
public final class PlatformNumericExample {
  public record Profile(
      int duration,
      int distance,
      double probability,
      double percent,
      int thirds,
      double tiny,
      int minimum,
      int maximum,
      int initialZoom) {}

  private static final FieldKey<Integer> DURATION =
      FieldKey.of(
          "duration",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 12000),
              NumericDisplay.ticks(BigDecimal.valueOf(20))
                  .withCaptions(
                      List.of(
                          new NumericDisplay.Caption(
                              BigDecimal.ZERO, "example.timer.off", "Off")))));
  private static final FieldKey<Integer> DISTANCE =
      FieldKey.of(
          "distance",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 4096), NumericDisplay.blocks().withSlider()));
  private static final FieldKey<Integer> MINIMUM =
      FieldKey.of(
          "minimum",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 12000), NumericDisplay.ticks(BigDecimal.valueOf(20))));
  private static final FieldKey<Integer> MAXIMUM = FieldKey.of("maximum", MINIMUM.type());
  private static final FieldKey<Integer> INITIAL_ZOOM =
      FieldKey.of(
          "initialZoom",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 64),
              new NumericDisplay(
                      "scale",
                      List.of(new NumericDisplay.Unit("scale", "scale", BigDecimal.ONE)),
                      BigDecimal.ONE,
                      Optional.empty(),
                      DecimalText.Limits.CONFIGURATION)
                  .withSlider()
                  .withCaptions(
                      List.of(
                          new NumericDisplay.Caption(
                              BigDecimal.ZERO, "example.zoom.native", "Native initial view")))));
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
              ValueTypes.decimal(NumericDomain.openClosed(BigDecimal.ZERO, BigDecimal.ONE)),
              NumericDisplay.probability().withSlider()));
  private static final FieldKey<Double> PERCENT =
      FieldKey.of(
          "percent",
          ValueTypes.numericDisplay(
              ValueTypes.decimal(
                  NumericDomain.closedOpen(BigDecimal.ZERO, BigDecimal.valueOf(100))),
              NumericDisplay.percentStored().withSlider()));
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
          DocumentId.parse("reform_platform_example:numeric"),
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
                          v.get(MAXIMUM),
                          v.get(INITIAL_ZOOM)))
              .field(DURATION, Profile::duration)
              .field(DISTANCE, Profile::distance)
              .field(PROBABILITY, Profile::probability)
              .field(PERCENT, Profile::percent)
              .field(THIRDS, Profile::thirds)
              .field(TINY, Profile::tiny)
              .field(MINIMUM, Profile::minimum)
              .field(MAXIMUM, Profile::maximum)
              .field(INITIAL_ZOOM, Profile::initialZoom)
              .build(),
          () -> new Profile(20, 64, 0.35, 35, 3, 0.1, 10, 40, 0),
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
  private static ClientFileSettings<Profile> settings;

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
  }

  public static ClientFileSettings<Profile> settings() {
    return Objects.requireNonNull(settings);
  }

  private PlatformNumericExample() {}
}
