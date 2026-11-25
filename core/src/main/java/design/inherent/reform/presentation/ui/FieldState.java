package design.inherent.reform.presentation.ui;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;
import java.util.Optional;

/** Owned current field data, not an independent draft, writer, or portable authorization. */
public record FieldState<V>(
    DraftRevision revision,
    DraftTarget target,
    DraftNode draft,
    Optional<V> value,
    DraftTree.InputState parse,
    ValidationReport validation,
    Optional<FieldFrontend.Input> input,
    boolean active,
    boolean changed,
    UiAvailability editing,
    UiAvailability submission,
    int encodedValueBytes,
    Optional<Throwable> observationFailure) {
  public FieldState {
    Objects.requireNonNull(revision);
    Objects.requireNonNull(target);
    Objects.requireNonNull(draft);
    Objects.requireNonNull(value);
    Objects.requireNonNull(parse);
    Objects.requireNonNull(validation);
    Objects.requireNonNull(input);
    Objects.requireNonNull(editing);
    Objects.requireNonNull(submission);
    Objects.requireNonNull(observationFailure);
    if (encodedValueBytes < 0)
      throw new IllegalArgumentException("Invalid field payload measurement");
  }
}
