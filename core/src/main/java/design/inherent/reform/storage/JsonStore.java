package design.inherent.reform.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonEnvelope;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
import java.util.Objects;

/** Explicit JSON envelope/bare-file format over the shared owned text-store contract. */
public final class JsonStore<T> extends TextStore<T> {
  public enum Format {
    NEW_ENVELOPE,
    EXISTING_BARE
  }

  private static final Gson JSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
  private final Format format;

  public JsonStore(
      DocumentDefinition<T> definition,
      OwnedFile file,
      Format format,
      JsonLimits jsonLimits,
      DraftLimits draftLimits) {
    super(definition, file, jsonLimits, draftLimits, "json", "Owned JSON source");
    this.format = Objects.requireNonNull(format);
  }

  @Override
  protected DraftTree decode(String text) {
    try {
      var parsed = JsonInput.read(text, jsonLimits);
      SourcePath selection =
          format == Format.NEW_ENVELOPE
              ? JsonEnvelope.values(parsed, definition.schemaVersion())
              : SourcePath.ROOT;
      return DraftTree.fromParsed(definition.type(), parsed, selection, draftLimits);
    } catch (JsonInputException invalid) {
      String code =
          switch (invalid.code()) {
            case "json.envelope" -> "storage.envelope";
            case "json.schema" -> "storage.schema";
            default -> invalid.code();
          };
      throw new InvalidData(
          new ValidationReport(
              List.of(Issue.error(code, FieldPath.ROOT, invalid.getMessage())), true));
    }
  }

  @Override
  protected String encode(Read<T> baseline, Candidate<T> candidate) {
    var values = JsonParser.parseString(candidate.authoredJson());
    if (format == Format.NEW_ENVELOPE) {
      var envelope = new JsonObject();
      envelope.addProperty("schemaVersion", definition.schemaVersion());
      envelope.add("values", values);
      return JSON.toJson(envelope) + "\n";
    }
    return JSON.toJson(values) + "\n";
  }

  @Override
  protected void validateEncoded(String text) throws JsonInputException {
    JsonInput.read(text, jsonLimits);
  }
}
