package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;

/** Declared field defaults and explicit source overrides through the packaged public API. */
final class FieldDefaults {
  record Profile(int height) {}

  static void verify() throws Exception {
    var height = FieldKey.of("height", ValueTypes.defaultedField(ValueTypes.integer(0, 100), 4));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(height)))
            .field(height, Profile::height)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:field_defaults"), 1, type, () -> new Profile(4));
    var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
    var root = Files.createTempDirectory("reform-public-defaults-");
    try {
      var path = root.resolve("prefs.json");
      Files.writeString(path, "{}");
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(StorageBinding.relative(root, "prefs.json"), 8192),
              JsonStore.Format.EXISTING_BARE,
              json,
              DraftLimits.CONFIGURATION);
      var before = store.read();
      var field = FieldPath.ROOT.field("height");
      var explicit =
          before
              .draft()
              .orElseThrow()
              .change(field, n -> ((DraftNode.Defaulted) n).withOverride(true))
              .inspect(definition, json)
              .candidate()
              .orElseThrow();
      var saved = store.write(before, explicit);
      if (saved.persistence().state() != OwnedFile.Persistence.SAVED
          || !saved
              .observation()
              .orElseThrow()
              .candidate()
              .orElseThrow()
              .value()
              .equals(new Profile(4))
          || !JsonInput.read(Files.readString(path), json)
              .copyTree()
              .getAsJsonObject()
              .has("height"))
        throw new AssertionError("Explicit equal-default override was discarded as a value no-op");
      var observed = saved.observation().orElseThrow();
      var omitted =
          observed
              .draft()
              .orElseThrow()
              .change(field, n -> ((DraftNode.Defaulted) n).withOverride(false))
              .inspect(definition, json)
              .candidate()
              .orElseThrow();
      var removed = store.write(observed, omitted);
      if (removed.persistence().state() != OwnedFile.Persistence.SAVED
          || ((DraftNode.Defaulted)
                  removed.observation().orElseThrow().draft().orElseThrow().at(field))
              .overridden())
        throw new AssertionError("Use-default source transition did not persist");
    } finally {
      Files.deleteIfExists(root.resolve("prefs.json"));
      Files.delete(root);
    }
  }

  private FieldDefaults() {}
}
