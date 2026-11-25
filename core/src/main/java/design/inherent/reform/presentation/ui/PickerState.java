package design.inherent.reform.presentation.ui;

import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.ValidationReport;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable bounded presentation facts. Domain values stay in the borrowed reference services. */
public record PickerState(
    Source source,
    Query query,
    long queryGeneration,
    Optional<ReferenceStamp> stamp,
    Phase searching,
    Optional<ReferencePage<String>> page,
    Optional<String> active,
    Phase inspecting,
    Optional<String> inspectionRequest,
    Optional<Inspection> inspection,
    Map<String, Requested> requested,
    Optional<Work> searchWork,
    Optional<Work> lookupWork,
    Optional<Work> editWork,
    Optional<Acceptance> acceptance,
    ValidationReport report) {
  public enum Source {
    UNADMITTED,
    READY,
    STALE,
    UNAVAILABLE,
    SUSPENDED
  }

  public enum Phase {
    IDLE,
    DEBOUNCING,
    DRAINING,
    RUNNING,
    READY,
    REFUSED,
    FAILED,
    CANCELLED
  }

  public record Query(String text, int caret, int anchor) {
    public Query {
      Objects.requireNonNull(text);
      if (caret < 0 || anchor < 0 || caret > text.length() || anchor > text.length())
        throw new IllegalArgumentException("Invalid query selection");
    }

    public static Query atEnd(String text) {
      return new Query(text, text.length(), text.length());
    }
  }

  public record Requested(
      Optional<String> raw,
      Optional<FieldFrontend.Input> input,
      boolean active,
      UiAvailability editing) {
    public Requested {
      Objects.requireNonNull(raw);
      Objects.requireNonNull(input);
      Objects.requireNonNull(editing);
    }
  }

  public record Inspection(
      String key, ReferenceResolution.Status status, Map<String, String> facts, String reason) {
    public Inspection {
      Objects.requireNonNull(key);
      Objects.requireNonNull(status);
      facts = Map.copyOf(facts);
      Objects.requireNonNull(reason);
    }
  }

  public record Acceptance(
      String role, DraftRevision revision, Optional<FieldFrontend.Input> input) {
    public Acceptance {
      Objects.requireNonNull(role);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(input);
    }
  }

  public record Work(
      UUID id,
      OperationSnapshot.State state,
      boolean cancellationRequested,
      Optional<ValidationReport> report) {
    public Work {
      Objects.requireNonNull(id);
      Objects.requireNonNull(state);
      Objects.requireNonNull(report);
    }

    static Work of(design.inherent.reform.operation.OperationHandle<?> handle) {
      var value = handle.snapshot();
      return new Work(
          value.id(), value.state(), value.cancellationRequested(), Optional.of(value.report()));
    }
  }

  // Retiring requests keep lifecycle facts, but their previous source's details are withdrawn.
  static Work retiring(design.inherent.reform.operation.OperationHandle<?> handle) {
    var value = handle.snapshot();
    return new Work(value.id(), value.state(), value.cancellationRequested(), Optional.empty());
  }

  /** An option is bound to the currently presented source and query, never only its row number. */
  public record Option(ReferenceStamp stamp, long queryGeneration, String key) {
    public Option {
      Objects.requireNonNull(stamp);
      Objects.requireNonNull(key);
    }
  }

  public record Activation<T>(Option option, Optional<PickerRole<T>> role) {
    public Activation {
      Objects.requireNonNull(option);
      Objects.requireNonNull(role);
    }
  }

  public PickerState {
    Objects.requireNonNull(source);
    Objects.requireNonNull(query);
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(searching);
    Objects.requireNonNull(page);
    Objects.requireNonNull(active);
    Objects.requireNonNull(inspecting);
    Objects.requireNonNull(inspectionRequest);
    Objects.requireNonNull(inspection);
    requested = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(requested));
    Objects.requireNonNull(searchWork);
    Objects.requireNonNull(lookupWork);
    Objects.requireNonNull(editWork);
    Objects.requireNonNull(acceptance);
    Objects.requireNonNull(report);
  }
}
