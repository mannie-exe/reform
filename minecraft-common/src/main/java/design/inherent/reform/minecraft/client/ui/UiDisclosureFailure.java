package design.inherent.reform.minecraft.client.ui;

import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;

/** A content-boundary failure; its expansion trigger remains independently usable. */
public record UiDisclosureFailure(
    String componentId, ValidationReport report, Optional<Throwable> cause) {
  public UiDisclosureFailure {
    Objects.requireNonNull(componentId);
    Objects.requireNonNull(report);
    Objects.requireNonNull(cause);
  }
}
