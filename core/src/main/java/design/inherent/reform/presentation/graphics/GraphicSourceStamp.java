package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.UUID;

/** Actual source provenance, without attaching a fabricated draft to loaded resources. */
public sealed interface GraphicSourceStamp
    permits GraphicRequest.Stamp, LoadedGraphicRequest.Stamp {
  enum Kind {
    DRAFT,
    LOADED
  }

  DocumentId provider();

  UUID view();

  UUID request();

  UUID context();

  ReferenceStamp resources();

  Kind kind();
}
