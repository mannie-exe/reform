package design.inherent.reform.storage;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStoreTest {
  @TempDir Path root;

  record Profile(int count, String name) {}

  private static final FieldKey<Integer> COUNT = FieldKey.of("count", ValueTypes.integer(0, 1000));
  private static final FieldKey<String> NAME = FieldKey.of("name", ValueTypes.text(64));
  private static final RecordType<Profile> TYPE =
      RecordType.<Profile>builder(values -> new Profile(values.get(COUNT), values.get(NAME)))
          .field(COUNT, Profile::count)
          .field(NAME, Profile::name)
          .build();
  private static final DocumentDefinition<Profile> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:prefs"), 1, TYPE, () -> new Profile(1, "Default"));
  private static final JsonLimits LIMITS = new JsonLimits(8192, 16, 512, 128, 64, 1024);

  private JsonStore<Profile> store(JsonStore.Format format) {
    return new JsonStore<>(
        DEFINITION,
        new OwnedFile(StorageBinding.relative(root, "prefs.json"), 8192),
        format,
        LIMITS,
        DraftLimits.CONFIGURATION);
  }

  @Test
  void defaultPresenceChangesPersistEvenWhenTheTypedValueIsEqual() throws Exception {
    var count = FieldKey.of("count", ValueTypes.defaultedField(ValueTypes.integer(0, 1000), 1));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(count), v.get(NAME)))
            .field(count, Profile::count)
            .field(NAME, Profile::name)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:default_presence"), 1, type, () -> new Profile(1, "Default"));
    for (var format : JsonStore.Format.values()) {
      var path = root.resolve("defaulted.json");
      Files.writeString(
          path,
          format == JsonStore.Format.NEW_ENVELOPE
              ? "{\"schemaVersion\":1,\"values\":{\"name\":\"Default\"}}"
              : "{\"name\":\"Default\"}");
      var store =
          new JsonStore<>(
              definition,
              new OwnedFile(StorageBinding.relative(root, "defaulted.json"), 8192),
              format,
              LIMITS,
              DraftLimits.CONFIGURATION);
      var first = store.read();
      var field = FieldPath.ROOT.field("count");
      var explicit =
          first
              .draft()
              .orElseThrow()
              .change(field, n -> ((DraftNode.Defaulted) n).withOverride(true))
              .inspect(definition, LIMITS)
              .candidate()
              .orElseThrow();
      assertEquals(first.candidate().orElseThrow().value(), explicit.value());
      var added = store.write(first, explicit);
      assertEquals(OwnedFile.Persistence.SAVED, added.persistence().state());
      assertTrue(
          ((DraftNode.Defaulted) added.observation().orElseThrow().draft().orElseThrow().at(field))
              .overridden());
      var observed = added.observation().orElseThrow();
      var implicit =
          observed
              .draft()
              .orElseThrow()
              .change(field, n -> ((DraftNode.Defaulted) n).withOverride(false))
              .inspect(definition, LIMITS)
              .candidate()
              .orElseThrow();
      var removed = store.write(observed, implicit);
      assertEquals(OwnedFile.Persistence.SAVED, removed.persistence().state());
      assertFalse(
          ((DraftNode.Defaulted)
                  removed.observation().orElseThrow().draft().orElseThrow().at(field))
              .overridden());
      assertEquals(
          new Profile(1, "Default"),
          removed.observation().orElseThrow().candidate().orElseThrow().value());
    }
  }

  @Test
  void explicitInitializationUsesTheNewSchemaEnvelopeButReadDoesNotCreate() throws Exception {
    var store = store(JsonStore.Format.NEW_ENVELOPE);
    var missing = store.read();
    assertEquals(JsonStore.State.MISSING, missing.state());
    assertTrue(missing.candidate().isEmpty());
    assertFalse(Files.exists(root.resolve("prefs.json")));
    var initialized = store.initializeDefaultsIfMissing();
    assertEquals(OwnedFile.Persistence.SAVED, initialized.persistence().state());
    var read = initialized.observation().orElseThrow();
    assertEquals(JsonStore.State.READY, read.state());
    assertEquals(new Profile(1, "Default"), read.candidate().orElseThrow().value());
    assertTrue(Files.readString(root.resolve("prefs.json")).contains("\"schemaVersion\": 1"));
    assertEquals(
        OwnedFile.Persistence.UNCHANGED, store.initializeDefaultsIfMissing().persistence().state());
  }

  @Test
  void malformedUnknownAndUnsupportedVersionsAreRetainedInsteadOfDefaulted() throws Exception {
    var store = store(JsonStore.Format.NEW_ENVELOPE);
    for (String bad :
        List.of(
            "not-json",
            "{}",
            "{\"schemaVersion\":2,\"values\":{}}",
            "{\"schemaVersion\":1.5,\"values\":{}}",
            "{\"schemaVersion\":\"1\",\"values\":{}}",
            "{\"schemaVersion\":1,\"values\":{},\"extra\":true}",
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"values\":{}}")) {
      Files.writeString(root.resolve("prefs.json"), bad);
      var result = store.initializeDefaultsIfMissing();
      assertEquals(OwnedFile.Persistence.UNCHANGED, result.persistence().state());
      assertEquals(JsonStore.State.INVALID, result.observation().orElseThrow().state());
      assertTrue(result.observation().orElseThrow().candidate().isEmpty());
      assertEquals(bad, Files.readString(root.resolve("prefs.json")));
    }
  }

  @Test
  void existingBareFormatAndEnvelopeValuesRetainTheirNumberSpelling() throws Exception {
    for (var format : JsonStore.Format.values()) {
      String values = "{\"count\":1.00e2,\"name\":\"Original\"}";
      String saved =
          format == JsonStore.Format.EXISTING_BARE
              ? values
              : "{\"schemaVersion\":1,\"values\":" + values + "}";
      Files.writeString(root.resolve("prefs.json"), saved);
      var store = store(format);
      var baseline = store.read();
      assertEquals(JsonStore.State.READY, baseline.state());
      assertEquals(
          OwnedFile.Persistence.UNCHANGED,
          store.write(baseline, baseline.candidate().orElseThrow()).persistence().state());
      assertEquals(saved, Files.readString(root.resolve("prefs.json")));
      var changed =
          baseline
              .draft()
              .orElseThrow()
              .change(FieldPath.ROOT.field("name"), ignored -> new DraftNode.Scalar("Changed"))
              .inspect(DEFINITION, LIMITS)
              .candidate()
              .orElseThrow();
      var written = store.write(baseline, changed);
      assertEquals(OwnedFile.Persistence.SAVED, written.persistence().state());
      assertEquals(
          100, written.observation().orElseThrow().candidate().orElseThrow().value().count());
      assertTrue(Files.readString(root.resolve("prefs.json")).contains("1.00e2"));
      assertEquals(
          format == JsonStore.Format.NEW_ENVELOPE,
          Files.readString(root.resolve("prefs.json")).contains("schemaVersion"));
    }
  }

  @Test
  void actualFileReceiptAcknowledgesOnlyTheSubmittedDraftAndRetainsLaterEditing() throws Exception {
    var store = store(JsonStore.Format.NEW_ENVELOPE);
    var baseline = store.initializeDefaultsIfMissing().observation().orElseThrow();
    var session =
        EditSession.fromDraft(
            DEFINITION,
            UUID.randomUUID(),
            baseline.source().revision(),
            baseline.draft().orElseThrow(),
            LIMITS,
            DraftLimits.CONFIGURATION);
    session.setRaw(FieldPath.ROOT.field("count"), "3");
    var submission = session.prepareSubmission().submission().orElseThrow();
    session.markInFlight(submission);
    var result = store.write(baseline, submission.candidate());
    session.setRaw(FieldPath.ROOT.field("name"), "Later");
    var observed = result.observation().orElseThrow();
    assertEquals(
        EditSession.Acknowledgement.ACCEPTED,
        session.acknowledge(
            submission, observed.draft().orElseThrow(), observed.source().revision()));
    assertEquals(new Profile(3, "Default"), store.read().candidate().orElseThrow().value());
    assertEquals(
        new Profile(3, "Later"), session.snapshot().inspect().candidate().orElseThrow().value());
    assertTrue(session.dirty());
    assertEquals(
        OwnedFile.Persistence.REFUSED,
        store
            .write(baseline, session.snapshot().inspect().candidate().orElseThrow())
            .persistence()
            .state());
    assertEquals(new Profile(3, "Default"), store.read().candidate().orElseThrow().value());
  }

  @Test
  void foreignStoreObservationsAreNotAdmittedAsNoOpProof() throws Exception {
    var file = new OwnedFile(StorageBinding.relative(root, "prefs.json"), 8192);
    var first =
        new JsonStore<>(
            DEFINITION, file, JsonStore.Format.NEW_ENVELOPE, LIMITS, DraftLimits.CONFIGURATION);
    var observed = first.initializeDefaultsIfMissing().observation().orElseThrow();
    var second =
        new JsonStore<>(
            DEFINITION, file, JsonStore.Format.EXISTING_BARE, LIMITS, DraftLimits.CONFIGURATION);
    assertThrows(
        IllegalArgumentException.class,
        () -> second.write(observed, observed.candidate().orElseThrow()));
    assertTrue(Files.readString(root.resolve("prefs.json")).contains("schemaVersion"));
  }

  @Test
  void serializedAdmissionFailureDoesNotCreateDirectoriesOrData() throws Exception {
    var file = new OwnedFile(StorageBinding.relative(root, "new", "prefs.json"), 8192);
    var limits = new JsonLimits(40, 16, 512, 128, 64, 1024);
    var store =
        new JsonStore<>(
            DEFINITION, file, JsonStore.Format.NEW_ENVELOPE, limits, DraftLimits.CONFIGURATION);
    var initialized = store.initializeDefaultsIfMissing();
    assertEquals(OwnedFile.Persistence.REFUSED, initialized.persistence().state());
    assertFalse(Files.exists(root.resolve("new")));
  }
}
