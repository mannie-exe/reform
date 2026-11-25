package design.inherent.reform.presentation.ui;

import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;

/**
 * Cached observation. An available snapshot owns bounded data; unavailability is never a default.
 */
public sealed interface UiObservation<T> {
  long revision();

  /** Restores an owned value; callers cache their projected presentation outside paint. */
  default T value() {
    if (this instanceof Available<T> available) return available.data().value();
    throw new PolicyRefusal(((Unavailable<T>) this).report());
  }

  record Available<T>(UiSnapshot<T> data, long revision) implements UiObservation<T> {
    public Available {
      Objects.requireNonNull(data);
      if (revision < 1) throw new IllegalArgumentException("Observation revision must be positive");
    }
  }

  record Unavailable<T>(ValidationReport report, Optional<Throwable> cause, long revision)
      implements UiObservation<T> {
    public Unavailable {
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
      if (report.valid() || revision < 1)
        throw new IllegalArgumentException(
            "Unavailable observation requires a failure and revision");
    }
  }
}
