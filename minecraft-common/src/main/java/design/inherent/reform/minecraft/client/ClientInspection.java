package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentReview;
import java.util.Optional;

/** Read-only observation of the actual inspection capture, independent of its native host. */
public interface ClientInspection {
  Optional<DocumentReview> reviewedInput();

  Optional<FieldPath> selectedField();
}
