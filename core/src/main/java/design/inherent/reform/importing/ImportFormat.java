package design.inherent.reform.importing;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.serialization.JsonInputException;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * An explicitly registered owner-bound parser. Parsing does not grant stage, save or apply rights.
 */
public interface ImportFormat<T> {
  enum InvalidDraftPolicy {
    REFUSE,
    ALLOW_REPAIRABLE
  }

  record Decoded(DraftTree draft, OptionalInt sourceSchemaVersion) {
    public Decoded {
      Objects.requireNonNull(draft);
      Objects.requireNonNull(sourceSchemaVersion);
      if (sourceSchemaVersion.isPresent() && sourceSchemaVersion.getAsInt() < 1)
        throw new IllegalArgumentException("Expected a positive declared source version");
    }
  }

  DocumentId id();

  DocumentDefinition<T> definition();

  String label();

  int maximumBytes();

  InvalidDraftPolicy invalidDraftPolicy();

  /**
   * Pure worker preparation after admitted capture; custom native inputs need owner capture first.
   */
  Decoded read(ImportInput input) throws JsonInputException;
}
