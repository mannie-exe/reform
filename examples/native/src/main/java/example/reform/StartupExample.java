package example.reform;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordTypes;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.neoforge.client.ClientFileSettings;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.PropertiesStore;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;

/** Actual immutable consumer selections are independent of editable next-start files. */
public final class StartupExample {
  public record Choice(int level) {}

  private static Choice knownSelected, brokenSelected;
  public static final DocumentDefinition<Choice> KNOWN = definition("startup_known");
  public static final DocumentDefinition<Choice> UNKNOWN = definition("startup_unknown");
  public static final DocumentDefinition<Choice> BROKEN = definition("startup_broken");
  private static ClientFileSettings<Choice> known, unknown, broken;

  private static DocumentDefinition<Choice> definition(String key) {
    return new DocumentDefinition<>(
        DocumentId.parse(NativeExample.ID + ":" + key),
        1,
        RecordTypes.record(Choice.class)
            .field("level", FieldKey.of("level", ValueTypes.integer(1, 32)))
            .build(),
        () -> new Choice(4));
  }

  private static final JsonLimits VALUES = new JsonLimits(8192, 16, 256, 256, 32, 256);

  private static PropertiesStore<Choice> source(
      DocumentDefinition<Choice> definition, String name) {
    return new PropertiesStore<>(
        definition,
        new OwnedFile(
            StorageBinding.relative(
                FMLPaths.CONFIGDIR.get().toAbsolutePath(), "startup-tools", name),
            8192),
        VALUES,
        DraftLimits.CONFIGURATION);
  }

  /** Explicit consumer bootstrap. This precedes registration and never creates a file. */
  private static Candidate<Choice> select(PropertiesStore<Choice> source) {
    try {
      var read = source.read();
      if (read.source().present())
        return read.candidate()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "The example cannot activate malformed startup input"));
      return DraftTree.from(
              source.definition().type(), source.definition().defaults(), source.draftLimits())
          .inspect(source.definition(), source.valueLimits())
          .candidate()
          .orElseThrow();
    } catch (IOException failed) {
      throw new IllegalStateException("The example could not read its bootstrap input", failed);
    }
  }

  static void register(IEventBus bus, ModContainer container) {
    var knownSource = source(KNOWN, "known.properties");
    var brokenSource = source(BROKEN, "broken.properties");
    var knownInput = select(knownSource);
    var brokenInput = select(brokenSource);
    knownSelected = knownInput.value();
    brokenSelected = brokenInput.value();
    known = ClientFileSettings.startupFile(bus, container, knownSource, Optional.of(knownInput));
    unknown =
        ClientFileSettings.startupProperties(
            bus, container, UNKNOWN, Optional.empty(), "startup-tools", "unknown.properties");
    broken = ClientFileSettings.startupFile(bus, container, brokenSource, Optional.of(brokenInput));
    known.registerCommands("reformstartup");
  }

  public static Choice knownSelected() {
    return Objects.requireNonNull(knownSelected);
  }

  public static Choice brokenSelected() {
    return Objects.requireNonNull(brokenSelected);
  }

  public static ClientFileSettings<Choice> known() {
    return Objects.requireNonNull(known);
  }

  public static ClientFileSettings<Choice> unknown() {
    return Objects.requireNonNull(unknown);
  }

  public static ClientFileSettings<Choice> broken() {
    return Objects.requireNonNull(broken);
  }

  private StartupExample() {}
}
