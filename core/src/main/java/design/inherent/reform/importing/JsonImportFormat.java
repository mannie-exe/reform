package design.inherent.reform.importing;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonEnvelope;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Explicit document-value or versioned-file JSON. No format inference or legacy migration. */
public final class JsonImportFormat<T> implements ImportFormat<T> {
  public enum Mode {
    VALUE_JSON,
    SCHEMA_ENVELOPE
  }

  private final DocumentId id;
  private final DocumentDefinition<T> definition;
  private final Mode mode;
  private final JsonLimits json;
  private final DraftLimits drafts;
  private final InvalidDraftPolicy invalid;

  /** Standard value/envelope choices; caller declares the actual definition, limits and policy. */
  public static <T> List<ImportFormat<T>> documentFormats(
      DocumentDefinition<T> definition,
      JsonLimits json,
      DraftLimits drafts,
      InvalidDraftPolicy invalid) {
    return List.of(
        new JsonImportFormat<>(
            DocumentId.parse("reform:import/value_json"),
            definition,
            Mode.VALUE_JSON,
            json,
            drafts,
            invalid),
        new JsonImportFormat<>(
            DocumentId.parse("reform:import/schema_json"),
            definition,
            Mode.SCHEMA_ENVELOPE,
            json,
            drafts,
            invalid));
  }

  public JsonImportFormat(
      DocumentId id,
      DocumentDefinition<T> definition,
      Mode mode,
      JsonLimits json,
      DraftLimits drafts,
      InvalidDraftPolicy invalid) {
    this.id = Objects.requireNonNull(id);
    this.definition = Objects.requireNonNull(definition);
    this.mode = Objects.requireNonNull(mode);
    this.json = Objects.requireNonNull(json);
    this.drafts = Objects.requireNonNull(drafts);
    this.invalid = Objects.requireNonNull(invalid);
  }

  @Override
  public DocumentId id() {
    return id;
  }

  @Override
  public DocumentDefinition<T> definition() {
    return definition;
  }

  @Override
  public String label() {
    return mode == Mode.VALUE_JSON ? "Document value JSON" : "Versioned JSON document";
  }

  @Override
  public int maximumBytes() {
    return json.maximumBytes();
  }

  @Override
  public InvalidDraftPolicy invalidDraftPolicy() {
    return invalid;
  }

  @Override
  public Decoded read(ImportInput input) throws JsonInputException {
    Objects.requireNonNull(input);
    if (input.size() > json.maximumBytes())
      throw new JsonInputException("json.bytes", "$", "Import exceeds the format's byte limit");
    var parsed = JsonInput.read(input.bytes(), json);
    boolean versioned = mode == Mode.SCHEMA_ENVELOPE;
    SourcePath values =
        versioned ? JsonEnvelope.values(parsed, definition.schemaVersion()) : SourcePath.ROOT;
    try {
      return new Decoded(
          DraftTree.fromParsed(definition.type(), parsed, values, drafts),
          versioned ? OptionalInt.of(definition.schemaVersion()) : OptionalInt.empty());
    } catch (IllegalArgumentException outsideDraftPolicy) {
      throw new JsonInputException(
          "import.draft_limits",
          values.toString(),
          "Imported draft exceeds the editor's depth, node or text bounds");
    }
  }
}
