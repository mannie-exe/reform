package design.inherent.reform.validation;

import java.util.ArrayList;
import java.util.List;

/** An unavailable required check is not successful validation. */
public record ValidationReport(List<Issue> issues, boolean complete) {
  public static final ValidationReport VALID = new ValidationReport(List.of(), true);

  public ValidationReport {
    issues = List.copyOf(issues);
  }

  public boolean valid() {
    return complete && issues.stream().noneMatch(issue -> issue.severity() == Issue.Severity.ERROR);
  }

  public ValidationReport and(ValidationReport other) {
    var combined = new ArrayList<>(issues);
    combined.addAll(other.issues);
    return new ValidationReport(combined, complete && other.complete);
  }
}
