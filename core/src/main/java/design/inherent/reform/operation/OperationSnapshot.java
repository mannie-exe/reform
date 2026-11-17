package design.inherent.reform.operation;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One immutable operation observation. COMPLETED means a result was produced, not that a save,
 * application or generation succeeded. Cancellation is never a rollback receipt.
 */
public record OperationSnapshot<O>(
    UUID id,
    DocumentId operation,
    UUID context,
    Optional<DocumentId> target,
    long revision,
    State state,
    boolean started,
    boolean cancellationRequested,
    Progress progress,
    Optional<O> result,
    ValidationReport report) {
  public enum State {
    QUEUED,
    RUNNING,
    CANCELLATION_REQUESTED,
    COMPLETED,
    REFUSED,
    FAILED,
    CANCELLED
  }

  public record Progress(String phase, long completed, OptionalLong total, String unit) {
    public static final Progress QUEUED = new Progress("queued", 0, OptionalLong.empty(), "");

    public Progress {
      Objects.requireNonNull(phase);
      Objects.requireNonNull(total);
      Objects.requireNonNull(unit);
      if (phase.length() > 160
          || unit.length() > 64
          || completed < 0
          || total.isPresent() && total.getAsLong() < completed)
        throw new IllegalArgumentException("Invalid bounded operation progress");
    }
  }

  public OperationSnapshot {
    Objects.requireNonNull(id);
    Objects.requireNonNull(operation);
    Objects.requireNonNull(context);
    Objects.requireNonNull(target);
    Objects.requireNonNull(state);
    Objects.requireNonNull(progress);
    Objects.requireNonNull(result);
    Objects.requireNonNull(report);
    if (revision < 0 || (state == State.COMPLETED) != result.isPresent())
      throw new IllegalArgumentException("Inconsistent operation observation");
  }

  public boolean terminal() {
    return switch (state) {
      case COMPLETED, REFUSED, FAILED, CANCELLED -> true;
      default -> false;
    };
  }
}
