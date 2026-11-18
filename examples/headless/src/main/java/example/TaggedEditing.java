package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.storage.JsonStore;
import design.inherent.reform.storage.OwnedFile;
import design.inherent.reform.storage.StorageBinding;
import design.inherent.reform.value.TaggedType;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;

/** Registered variants, retained inactive input and selected-only file persistence. */
final class TaggedEditing {
  interface Mode {}

  record Fixed(int count) implements Mode {}

  record Automatic(boolean enabled) implements Mode {}

  static void verify() throws Exception {
    var count = FieldKey.of("count", ValueTypes.integer(0, 100));
    var enabled = FieldKey.of("enabled", ValueTypes.bool());
    var fixed =
        RecordType.<Fixed>builder(v -> new Fixed(v.get(count))).field(count, Fixed::count).build();
    var automatic =
        RecordType.<Automatic>builder(v -> new Automatic(v.get(enabled)))
            .field(enabled, Automatic::enabled)
            .build();
    var type =
        TaggedType.<Mode>builder("kind", "settings")
            .variant("fixed", Fixed.class, fixed)
            .variant("automatic", Automatic.class, automatic)
            .build();
    var definition =
        new DocumentDefinition<Mode>(
            DocumentId.parse("example:tagged"), 1, type, () -> new Fixed(4));
    var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
    var root = Files.createTempDirectory("reform-public-tagged-");
    try {
      var path = root.resolve("prefs.json");
      Files.writeString(path, "{\"kind\":\"fixed\",\"settings\":{\"count\":4}}");
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(StorageBinding.relative(root, "prefs.json"), 8192),
              JsonStore.Format.EXISTING_BARE,
              json,
              DraftLimits.CONFIGURATION);
      var before = store.read();
      var selected =
          before
              .draft()
              .orElseThrow()
              .change(
                  FieldPath.ROOT.variant("fixed").field("count"), n -> new DraftNode.Scalar("-"))
              .change(
                  FieldPath.ROOT,
                  n ->
                      ((DraftNode.Alternative) n)
                          .select(
                              "automatic",
                              DraftTree.from(
                                      automatic, new Automatic(true), DraftLimits.CONFIGURATION)
                                  .root()));
      var saved = store.write(before, selected.inspect(definition, json).candidate().orElseThrow());
      if (saved.persistence().state() != OwnedFile.Persistence.SAVED
          || !JsonInput.read(Files.readString(path), json)
              .decode(type)
              .value()
              .orElseThrow()
              .equals(new Automatic(true))
          || Files.readString(path).contains("count"))
        throw new AssertionError("Inactive branch leaked into the persisted variant");
      if (selected
          .change(
              FieldPath.ROOT,
              n -> ((DraftNode.Alternative) n).select("fixed", new DraftNode.Missing()))
          .inspect(definition, json)
          .candidate()
          .isPresent())
        throw new AssertionError("Selecting an existing branch discarded its invalid input");
    } finally {
      Files.deleteIfExists(root.resolve("prefs.json"));
      Files.delete(root);
    }
  }

  private TaggedEditing() {}
}
