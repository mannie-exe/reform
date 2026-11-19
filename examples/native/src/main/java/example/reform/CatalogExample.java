package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.neoforge.client.ClientJsonSettings;
import design.inherent.reform.presentation.FormCatalog;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Ordinary composition metadata over one real owner; no custom session, writer or executor. */
public final class CatalogExample {
  public record Role(int weight, int delay) {}

  public record Profile(String name, Map<String, Role> roles) {}

  public record Bounds(int minimum, int maximum) {}

  public record Profiles(List<Profile> profiles, Bounds global, Optional<Bounds> extra) {}

  private static final ValueType<Role> ROLE =
      RecordTypes.record(Role.class)
          .field("weight", FieldKey.of("weight", ValueTypes.integer(0, 10)))
          .field(
              "delay",
              FieldKey.of(
                  "delay",
                  ValueTypes.numericDisplay(
                      ValueTypes.integer(0, 1200), NumericDisplay.ticks(BigDecimal.valueOf(20)))))
          .build();
  private static final ValueType<Profile> PROFILE =
      RecordTypes.record(Profile.class)
          .field("name", FieldKey.of("name", ValueTypes.text(128)))
          .field("roles", FieldKey.of("roles", ValueTypes.objectMap(ValueTypes.text(64), ROLE, 8)))
          .build();
  private static final ValueType<Bounds> BOUNDS =
      RecordTypes.record(Bounds.class)
          .field("minimum", FieldKey.of("minimum", ValueTypes.integer(0, 100)))
          .field("maximum", FieldKey.of("maximum", ValueTypes.integer(0, 100)))
          .build();
  private static final ValueType<Profiles> TYPE =
      RecordTypes.record(Profiles.class)
          .field("profiles", FieldKey.of("profiles", ValueTypes.list(PROFILE, 8)))
          .field("global", FieldKey.of("global", BOUNDS))
          .field(
              "extra",
              FieldKey.of(
                  "extra", ValueTypes.optional(BOUNDS, ValueShape.OptionalEncoding.SINGLETON_LIST)))
          .build();

  public static final FieldPath PROFILES = FieldPath.ROOT.field("profiles");
  public static final FieldPath GLOBAL = FieldPath.ROOT.field("global");
  public static final FieldPath EXTRA = FieldPath.ROOT.field("extra").append(FieldPath.Part.VALUE);
  public static final DocumentDefinition<Profiles> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":catalog"),
          1,
          TYPE,
          () ->
              new Profiles(
                  List.of(
                      new Profile("Land", Map.of("anchor", new Role(3, 40))),
                      new Profile("Marine", Map.of("vessel", new Role(4, 60)))),
                  new Bounds(1, 8),
                  Optional.empty()),
          value ->
              value.global().minimum() <= value.global().maximum()
                  ? ValidationReport.VALID
                  : new ValidationReport(
                      List.of(
                          new Issue(
                              "catalog.bounds",
                              Issue.Severity.ERROR,
                              GLOBAL.field("minimum"),
                              List.of(GLOBAL.field("maximum")),
                              Map.of(),
                              "Minimum must not exceed maximum")),
                      false));

  public static final FormPresentation PRESENTATION =
      new FormPresentation(
              List.of(
                  new FormPresentation.Range(
                      "bounds",
                      "example.catalog.bounds",
                      "Global bounds",
                      "Both values are counts; editing never changes the other endpoint.",
                      GLOBAL.field("minimum"),
                      GLOBAL.field("maximum"))))
          .withCatalog(
              new FormCatalog(
                  List.of(
                      new FormCatalog.Section(
                          PROFILES,
                          "example.catalog.profiles",
                          "Profiles",
                          "Authored profiles and their roles"),
                      new FormCatalog.Section(
                          GLOBAL,
                          "example.catalog.global",
                          "Global bounds",
                          "Complete owner validation still applies"),
                      new FormCatalog.Section(
                          EXTRA,
                          "example.catalog.extra",
                          "Extra bounds",
                          "An inactive optional section")),
                  FormCatalog.Limits.STANDARD,
                  node ->
                      node.type().filter(type -> type == PROFILE).isPresent()
                          ? node.children().stream()
                              .filter(child -> child.name().equals("name"))
                              .map(child -> child.input())
                              .filter(DraftNode.Scalar.class::isInstance)
                              .map(DraftNode.Scalar.class::cast)
                              .map(DraftNode.Scalar::raw)
                              .findFirst()
                          : Optional.empty()));

  private static ClientJsonSettings<Profiles> settings;

  static void register(IEventBus bus, ModContainer container) {
    settings = ClientJsonSettings.clientJson(bus, container, DEFINITION);
  }

  public static ClientJsonSettings<Profiles> settings() {
    return Objects.requireNonNull(settings);
  }

  private CatalogExample() {}
}
