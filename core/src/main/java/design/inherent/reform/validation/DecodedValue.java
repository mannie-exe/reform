package design.inherent.reform.validation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete decoded values only; native partial recovery is not an admissible candidate. */
public record DecodedValue<T>(Optional<T> value, ValidationReport report) {
  public DecodedValue {
    Objects.requireNonNull(value);
    Objects.requireNonNull(report);
    if (value.isPresent() != report.valid()) {
      throw new IllegalArgumentException("Decoded values require complete successful validation");
    }
  }

  public static <T> DecodedValue<T> success(T value) {
    return new DecodedValue<>(Optional.of(value), ValidationReport.VALID);
  }

  public static <T> DecodedValue<T> failure(Issue issue) {
    return new DecodedValue<>(Optional.empty(), new ValidationReport(List.of(issue), true));
  }

  public static <T> DecodedValue<T> failure(ValidationReport report) {
    return new DecodedValue<>(Optional.empty(), report);
  }
}
