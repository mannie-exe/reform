package design.inherent.reform.presentation.ui;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.operation.OperationSnapshot;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceResolution;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.ValidationReport;
import java.util.List;
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
    Window window,
    Optional<String> active,
    Phase inspecting,
    Optional<String> inspectionRequest,
    Optional<Inspection> inspection,
    Map<String, Requested> requested,
    Optional<Work> searchWork,
    Optional<Work> lookupWork,
    Optional<Work> editWork,
    Optional<Acceptance> acceptance,
    Optional<Accepted> accepted,
    ValidationReport report) {
  /** Source-compatible unknown success history, never inferred from a COMPLETED label. */
  public PickerState(
      Source source,
      Query query,
      long queryGeneration,
      Optional<ReferenceStamp> stamp,
      Phase searching,
      Optional<ReferencePage<String>> page,
      Window window,
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
    this(
        source,
        query,
        queryGeneration,
        stamp,
        searching,
        page,
        window,
        active,
        inspecting,
        inspectionRequest,
        inspection,
        requested,
        searchWork,
        lookupWork,
        editWork,
        acceptance,
        Optional.empty(),
        report);
  }

  /** Metadata for one actual accepted field edit, not a writer, candidate, or save receipt. */
  public record Accepted(
      UUID operation,
      String role,
      FieldPath field,
      DraftRevision before,
      DraftRevision after,
      ReferenceStamp source) {
    public Accepted {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(role);
      Objects.requireNonNull(field);
      Objects.requireNonNull(before);
      Objects.requireNonNull(after);
      Objects.requireNonNull(source);
      if (role.isBlank()
          || role.length() > 128
          || !before.document().equals(after.document())
          || !before.context().equals(after.context())
          || !before.session().equals(after.session()))
        throw new IllegalArgumentException("An accepted selection retains its actual document");
    }
  }

  /** Bounded admitted pages and cursor bookkeeping, not a known source total. */
  public record Window(
      List<ReferencePage.Choice<String>> choices,
      int firstPage,
      int pageCount,
      int retainedCursors,
      boolean before,
      boolean after) {
    public static final Window EMPTY = new Window(List.of(), 0, 0, 0, false, false);

    public Window {
      choices = List.copyOf(choices);
      if (firstPage < 0 || pageCount < 0 || retainedCursors < 0)
        throw new IllegalArgumentException("Invalid picker window");
    }
  }

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
      String key,
      ReferenceResolution.Status status,
      Map<String, String> facts,
      String reason,
      Optional<String> title) {
    public Inspection(
        String key, ReferenceResolution.Status status, Map<String, String> facts, String reason) {
      this(key, status, facts, reason, Optional.empty());
    }

    public Inspection {
      Objects.requireNonNull(title);
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
    Objects.requireNonNull(window);
    Objects.requireNonNull(active);
    Objects.requireNonNull(inspecting);
    Objects.requireNonNull(inspectionRequest);
    Objects.requireNonNull(inspection);
    requested = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(requested));
    Objects.requireNonNull(searchWork);
    Objects.requireNonNull(lookupWork);
    Objects.requireNonNull(editWork);
    Objects.requireNonNull(acceptance);
    Objects.requireNonNull(accepted);
    Objects.requireNonNull(report);
  }
}
