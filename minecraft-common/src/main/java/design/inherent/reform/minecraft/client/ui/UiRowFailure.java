package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;

/**
 * A failed row declaration, identified within its actual mounted collection. No domain effect is
 * implied.
 */
public record UiRowFailure(
    String collectionId, String rowKey, ValidationReport report, Optional<Throwable> cause) {
  public UiRowFailure {
    Objects.requireNonNull(collectionId);
    Objects.requireNonNull(rowKey);
    Objects.requireNonNull(report);
    Objects.requireNonNull(cause);
  }
}
