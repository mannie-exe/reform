package example.reform.platform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.minecraft.client.ClientFileSettings;
import design.inherent.reform.minecraft.client.ClientForm;
import design.inherent.reform.minecraft.client.ClientRegistration;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;

/** Portable owners where Fabric has no native FML configuration registry. */
public final class PlatformWorkflowSources {
  public record ClientValues(int live, int world, int game) {}

  public record Defaults(int level) {}

  private static ClientFileSettings<ClientValues> client;
  private static ClientFileSettings<Defaults> defaults;
  private static Path directory;

  static void register(ClientRegistration registration) {
    directory = registration.configurationDirectory();
    client =
        ClientFileSettings.clientJson(
            registration,
            new DocumentDefinition<>(
                DocumentId.parse(PlatformServer.ID + ":workflow_client"),
                1,
                RecordTypes.record(ClientValues.class)
                    .field("live", FieldKey.of("live", ValueTypes.integer(0, 32)))
                    .field("world", FieldKey.of("world", ValueTypes.integer(0, 32)))
                    .field("game", FieldKey.of("game", ValueTypes.integer(0, 32)))
                    .build(),
                () -> new ClientValues(4, 1, 1)));
    defaults =
        ClientFileSettings.clientJson(
            registration,
            new DocumentDefinition<>(
                DocumentId.parse(PlatformServer.ID + ":workflow_defaults"),
                1,
                RecordTypes.record(Defaults.class)
                    .field("level", FieldKey.of("level", ValueTypes.integer(0, 32)))
                    .build(),
                () -> new Defaults(3)));
  }

  static boolean ready() {
    return client.status().lifecycle() == ClientFileSettings.Lifecycle.READY
        && defaults.status().lifecycle() == ClientFileSettings.Lifecycle.READY;
  }

  static ClientForm client() {
    return client.form(Component.literal("Local client settings"));
  }

  static ClientForm defaults() {
    return defaults.form(Component.literal("Installation defaults"));
  }

  static Path clientPath() {
    return directory.resolve(PlatformServer.ID + "/workflow_client.json");
  }

  static Path defaultsPath() {
    return directory.resolve(PlatformServer.ID + "/workflow_defaults.json");
  }

  static int live() {
    return client.runtime().orElseThrow().view().value().orElseThrow().live();
  }

  static int level() {
    return defaults.runtime().orElseThrow().view().value().orElseThrow().level();
  }

  static String sourceKind() {
    return "Portable JSON owners; no FML registry";
  }

  static String saveLabel() {
    return "Save and apply";
  }

  static boolean defaultSaveObserved() {
    return level() == 10;
  }

  static com.google.gson.JsonObject sourceReport() {
    var report = new com.google.gson.JsonObject();
    report.addProperty("kind", sourceKind());
    report.addProperty("clientSource", clientPath().getFileName().toString());
    report.addProperty("defaultSource", defaultsPath().getFileName().toString());
    report.addProperty("nativeFmlCallbacks", false);
    return report;
  }

  private PlatformWorkflowSources() {}
}
