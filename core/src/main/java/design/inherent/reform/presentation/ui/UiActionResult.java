package design.inherent.reform.presentation.ui;

import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;

/**
 * An admitted local edit, an actual existing operation, or a durable refusal. No save is inferred.
 */
public sealed interface UiActionResult {
  record Applied() implements UiActionResult {}

  record Pending(OperationHandle<?> operation) implements UiActionResult {
    public Pending {
      Objects.requireNonNull(operation);
    }
  }

  record Refused(ValidationReport report, Optional<Throwable> cause) implements UiActionResult {
    public Refused {
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
      if (report.valid()) throw new IllegalArgumentException("A refusal needs a useful report");
    }
  }
}
