package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.neoforge.client.NeoForgeClientRegistration;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;

/** Ordinary definition/factory use, with a complete-candidate relationship and whole collection. */
public final class ConflictExample {
  public record Profile(int minimum, int maximum, List<String> roles) {}

  private static final FieldKey<Integer> MIN = FieldKey.of("minimum", ValueTypes.integer(0, 20));
  private static final FieldKey<Integer> MAX = FieldKey.of("maximum", ValueTypes.integer(0, 20));
  private static final FieldKey<List<String>> ROLES =
      FieldKey.of("roles", ValueTypes.list(ValueTypes.text(64), 8));
  public static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse(NativeExample.ID + ":conflicts"),
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

  static void register(IEventBus bus, ModContainer container) {
    settings =
        ClientFileSettings.clientJson(NeoForgeClientRegistration.of(bus, container), DEFINITION);
    settings.registerCommands("reformconflicts");
  }

  public static ClientFileSettings<Profile> settings() {
    return java.util.Objects.requireNonNull(settings);
  }

  public static Screen screen(Screen parent) {
    return settings().screen(parent, Component.literal("Native manual conflict review"));
  }

  private ConflictExample() {}
}
