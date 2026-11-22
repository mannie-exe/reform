package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Ordinary definition/factory use, with a complete-candidate relationship and whole collection. */
public final class PlatformConflictExample {
  public record Profile(int minimum, int maximum, List<String> roles) {}

  private static final FieldKey<Integer> MIN = FieldKey.of("minimum", ValueTypes.integer(0, 20));
  private static final FieldKey<Integer> MAX = FieldKey.of("maximum", ValueTypes.integer(0, 20));
  private static final FieldKey<List<String>> ROLES =
      FieldKey.of("roles", ValueTypes.list(ValueTypes.text(64), 8));
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(PlatformServer.ID + ":conflicts"),
          1,
          RecordType.<Profile>builder(v -> new Profile(v.get(MIN), v.get(MAX), v.get(ROLES)))
              .field(MIN, Profile::minimum)
              .field(MAX, Profile::maximum)
              .field(ROLES, Profile::roles)
              .build(),
          () -> new Profile(1, 10, List.of("base")),
          value ->
              value.minimum() <= value.maximum()
                  ? ValidationReport.VALID
                  : new ValidationReport(
                      List.of(
                          Issue.error(
                              "range.order",
                              FieldPath.ROOT.field("minimum"),
                              "Minimum exceeds maximum")),
                      true));
  private static ClientFileSettings<Profile> settings;

  static void register(ClientRegistration registration) {
    settings = ClientFileSettings.clientJson(registration, DEFINITION);
    settings.registerCommands("reformconflicts");
  }

  public static ClientFileSettings<Profile> settings() {
    return java.util.Objects.requireNonNull(settings);
  }

  public static Screen screen(Screen parent) {
    return settings().screen(parent, Component.literal("Native manual conflict review"));
  }

  private PlatformConflictExample() {}
}
