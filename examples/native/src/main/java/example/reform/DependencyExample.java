package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.presentation.FormDependencies;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Display-only dependencies over ordinary stored fields, including a retained inactive input. */
public final class DependencyExample {
  public enum Mode {
    LAND,
    WATER
  }

  public record Profile(
      boolean enabled,
      Mode mode,
      int height,
      List<Integer> layers,
      Optional<Boolean> advanced,
      int detail) {}

  private static FieldPath path(String id) {
    return FieldPath.ROOT.field(id);
  }

  private static final FieldKey<Boolean> ENABLED = FieldKey.of("enabled", ValueTypes.bool());
  private static final FieldKey<Mode> MODE =
      FieldKey.of("mode", ValueTypes.enumeration(Mode.class));
  private static final FieldKey<Integer> HEIGHT =
      FieldKey.of(
          "height",
          ValueTypes.numericDisplay(
              ValueTypes.integer(0, 100), NumericDisplay.blocks().withSlider()));
  private static final FieldKey<List<Integer>> LAYERS =
      FieldKey.of("layers", ValueTypes.list(ValueTypes.integer(0, 32), 4));
  private static final FieldKey<Optional<Boolean>> ADVANCED =
      FieldKey.of(
          "advanced",
          ValueTypes.optional(ValueTypes.bool(), ValueShape.OptionalEncoding.NULL_OR_VALUE));
  private static final FieldKey<Integer> DETAIL = FieldKey.of("detail", ValueTypes.integer(0, 100));

  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":dependencies"),
          1,
          RecordType.<Profile>builder(
                  v ->
                      new Profile(
                          v.get(ENABLED),
                          v.get(MODE),
                          v.get(HEIGHT),
                          v.get(LAYERS),
                          v.get(ADVANCED),
                          v.get(DETAIL)))
              .field(ENABLED, Profile::enabled)
              .field(MODE, Profile::mode)
              .field(HEIGHT, Profile::height)
              .field(LAYERS, Profile::layers)
              .field(ADVANCED, Profile::advanced)
              .field(DETAIL, Profile::detail)
              .build(),
          () -> new Profile(true, Mode.LAND, 24, List.of(4, 8), Optional.empty(), 4),
          value ->
              value.height() != 90
                  ? ValidationReport.VALID
                  : new ValidationReport(
                      List.of(
                          Issue.error(
                              "example.height",
                              path("height"),
                              "Choose another height; this rule applies even while inactive")),
                      true));

  private static FormDependencies.Rule gate(
      String id, FieldPath target, FieldPath source, String match, String reason) {
    return new FormDependencies.Rule(
        id, target, source, Set.of(match), "example.dependencies." + id, reason);
  }

  public static final FormPresentation PRESENTATION =
      new FormPresentation(
          List.of(),
          new FormDependencies(
              List.of(
                  gate(
                      "mode",
                      path("mode"),
                      path("enabled"),
                      "true",
                      "Enable terrain to choose a mode"),
                  gate(
                      "height",
                      path("height"),
                      path("mode"),
                      "LAND",
                      "Choose LAND mode to edit height"),
                  gate(
                      "layers",
                      path("layers"),
                      path("enabled"),
                      "true",
                      "Enable terrain to edit its layers"),
                  gate(
                      "detail",
                      path("detail"),
                      path("advanced").append(FieldPath.Part.VALUE),
                      "true",
                      "Enable advanced detail"))));

  private static ClientFileSettings<Profile> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings =
        ClientFileSettings.clientJson(NeoForgeClientRegistration.of(bus, container), DEFINITION);
  }

  public static ClientFileSettings<Profile> settings() {
    return Objects.requireNonNull(settings);
  }

  private DependencyExample() {}
}
