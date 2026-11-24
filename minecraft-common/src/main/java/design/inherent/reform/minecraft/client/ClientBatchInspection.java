package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationHandle;
import java.util.Optional;

/** Cached complete review and exact submission; observation never captures, saves, or applies. */
public interface ClientBatchInspection {
  Optional<DocumentBatch.Review> reviewedInput();

  Optional<OperationHandle<DocumentBatch.Result>> submission();

  Optional<DocumentId> selectedDocument();
}
