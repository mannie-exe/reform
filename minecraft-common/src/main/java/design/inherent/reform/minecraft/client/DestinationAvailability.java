package design.inherent.reform.minecraft.client;

import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Set;

/** Cached presentation only: inspecting never opens, loads, validates, or authorizes an owner. */
public record DestinationAvailability(Kind kind, ValidationReport report, Set<Recovery> recovery) {
  public enum Kind {
    READY,
    UNOPENED,
    PENDING,
    UNAVAILABLE,
    MISSING,
    CLOSED
  }

  public enum Recovery {
    RETRY,
    BACK
  }

  public DestinationAvailability {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(report);
    recovery = Set.copyOf(recovery);
  }
}
