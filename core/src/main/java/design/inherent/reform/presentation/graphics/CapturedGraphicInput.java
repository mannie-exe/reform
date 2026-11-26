package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.Map;

/** Complete, attributable read-only input. Its stamps describe data, never grant authority. */
public sealed interface CapturedGraphicInput<T> permits DraftPreviewInput, AuditionPreviewInput {
  PreviewInputStamp stamp();

  Candidate<T> candidate();

  SourceInfo provenance();

  Map<DocumentId, ReferenceStamp> observedReferences();

  GraphicInputBasis basis();

  DraftPreviewInput<T> base();
}
