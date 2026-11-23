package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.Map;
import java.util.Objects;

/** Captured complete input and provenance, not a writer or an admission to prepare resources. */
public record DraftPreviewInput<T>(
    PreviewInputStamp stamp,
    Candidate<T> candidate,
    SourceInfo provenance,
    Map<DocumentId, ReferenceStamp> observedReferences) {
  public DraftPreviewInput {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(candidate);
    Objects.requireNonNull(provenance);
    observedReferences = Map.copyOf(observedReferences);
    if (!candidate.definition().id().equals(stamp.draft().document()))
      throw new IllegalArgumentException(
          "Preview input and candidate must identify the same document");
    observedReferences.forEach(
        (key, value) -> {
          if (!key.equals(value.source()))
            throw new IllegalArgumentException(
                "A captured reference stamp needs its actual source identity");
        });
  }
}
