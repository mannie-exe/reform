package design.inherent.reform.minecraft.client;

import design.inherent.reform.operation.DocumentImports;
import java.util.Optional;

/** Cached actual import comparison. Observation never reads a source, stages, or saves. */
public interface ClientImportInspection {
  Optional<DocumentImports.Preview> reviewedInput();
}
