package design.inherent.reform.presentation.ui;

import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;

/** Cached feedback, never authority to perform the eventual action. */
public record UiAvailability(boolean available, ValidationReport report) {
  public static final UiAvailability READY = new UiAvailability(true, ValidationReport.VALID);

  public UiAvailability {
    Objects.requireNonNull(report);
    if (available != report.valid())
      throw new IllegalArgumentException("Action readiness must agree with its report");
  }

  public static UiAvailability blocked(String code, String reason) {
    return new UiAvailability(false, new PolicyRefusal(code, reason).report());
  }
}
