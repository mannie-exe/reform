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
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.util.Optional;

/** Native-style absent/present field semantics through only the packaged public API. */
final class OptionalEditing {
  record Profile(Optional<Integer> height) {}

  static void verify() throws Exception {
    var height =
        FieldKey.of(
            "height",
            ValueTypes.defaultedField(
                ValueTypes.optional(
                    ValueTypes.integer(0, 100), ValueShape.OptionalEncoding.NULL_OR_VALUE),
                Optional.empty()));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(height)))
            .field(height, Profile::height)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:optional"), 1, type, () -> new Profile(Optional.empty()));
    var json = new JsonLimits(8192, 32, 1024, 512, 64, 1024);
    var root = Files.createTempDirectory("reform-public-optional-");
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
      var contents = field.append(FieldPath.Part.VALUE);
      var candidate =
          before
              .draft()
              .orElseThrow()
              .change(field, n -> ((DraftNode.Defaulted) n).withOverride(true))
              .change(
                  contents, n -> new DraftNode.OptionalValue(true, new DraftNode.Scalar("7.00")))
              .inspect(definition, json)
              .candidate()
              .orElseThrow();
      var saved = store.write(before, candidate);
      if (saved.persistence().state() != OwnedFile.Persistence.SAVED
          || !JsonInput.read(Files.readString(path), json)
              .decode(type)
              .value()
              .orElseThrow()
              .equals(new Profile(Optional.of(7))))
        throw new AssertionError("Optional present field did not persist without a format change");
      var observed = saved.observation().orElseThrow();
      var clearedDraft =
          observed
              .draft()
              .orElseThrow()
              .change(
                  contents,
                  n ->
                      ((DraftNode.OptionalValue) n)
                          .withValue(new DraftNode.Scalar("-odd input-"))
                          .withPresent(false));
      var cleared =
          store.write(observed, clearedDraft.inspect(definition, json).candidate().orElseThrow());
      if (cleared.persistence().state() != OwnedFile.Persistence.SAVED
          || !JsonInput.read(Files.readString(path), json)
              .decode(type)
              .value()
              .orElseThrow()
              .equals(new Profile(Optional.empty())))
        throw new AssertionError("Inactive optional input entered saved membership");
      if (clearedDraft
          .change(contents, n -> ((DraftNode.OptionalValue) n).withPresent(true))
          .inspect(definition, json)
          .candidate()
          .isPresent()) throw new AssertionError("Re-enabled invalid input was silently recovered");
    } finally {
      Files.deleteIfExists(root.resolve("prefs.json"));
      Files.delete(root);
    }
  }

  private OptionalEditing() {}
}
