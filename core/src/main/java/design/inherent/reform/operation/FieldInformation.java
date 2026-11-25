package design.inherent.reform.operation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import java.util.Objects;
import java.util.Optional;

/** One selected field's captured values. No source reread, save grant or inferred row alignment. */
public record FieldInformation(
    DraftRevision target,
    FieldPath path,
    SourceInfo source,
    Value baseline,
    Value draft,
    Value effective,
    Value declaredDefault,
    Optional<DocumentReview.Effective> effectiveObservation,
    Optional<ApplicationEffects.Rule> application,
    ValidationReport observationReport) {
  public enum State {
    PRESENT,
    ABSENT,
    UNAVAILABLE
  }

  public record Value(
      State state,
      Optional<String> text,
      Optional<String> reason,
      Optional<NumericDisplay.Caption> numericCaption,
      Optional<design.inherent.reform.presentation.ChoiceDisplay.Caption> choiceCaption) {
    public Value(
        State state,
        Optional<String> text,
        Optional<String> reason,
        Optional<NumericDisplay.Caption> numericCaption) {
      this(state, text, reason, numericCaption, Optional.empty());
    }

    public Value(State state, Optional<String> text, Optional<String> reason) {
      this(state, text, reason, Optional.empty());
    }

    public Value {
      Objects.requireNonNull(state);
      Objects.requireNonNull(text);
      Objects.requireNonNull(reason);
      Objects.requireNonNull(numericCaption);
      Objects.requireNonNull(choiceCaption);
      if ((state == State.PRESENT) != text.isPresent()
          || (state == State.UNAVAILABLE) != reason.isPresent()
          || state != State.PRESENT && (numericCaption.isPresent() || choiceCaption.isPresent()))
        throw new IllegalArgumentException(
            "Field information must distinguish known, absent and unavailable values");
    }

    static Value present(String value) {
      return new Value(State.PRESENT, Optional.of(value), Optional.empty());
    }

    static Value absent() {
      return new Value(State.ABSENT, Optional.empty(), Optional.empty());
    }

    static Value unavailable(String reason) {
      return new Value(State.UNAVAILABLE, Optional.empty(), Optional.of(reason));
    }
  }

  public FieldInformation {
    Objects.requireNonNull(target);
    Objects.requireNonNull(path);
    Objects.requireNonNull(source);
    Objects.requireNonNull(baseline);
    Objects.requireNonNull(draft);
    Objects.requireNonNull(effective);
    Objects.requireNonNull(declaredDefault);
    Objects.requireNonNull(effectiveObservation);
    Objects.requireNonNull(application);
    Objects.requireNonNull(observationReport);
  }

  static <T> FieldInformation capture(
      EditorActor.Inspection<T> observed, FieldPath path, DocumentReview.Limits limits) {
    var snapshot = observed.snapshot();
    try {
      DraftTypes.at(snapshot.definition().type(), snapshot.draft(), path);
    } catch (IllegalArgumentException absent) {
      throw new PolicyRefusal(
          "information.field", "That field is absent or inactive in this draft");
    }
    var text =
        new DraftDescriptions(
            limits.maximumNodes(),
            limits.maximumCharacters(),
            "information.limit",
            "Complete selected-field information exceeds its admitted limits");
    text.account(observed.provenance().kind());
    text.account(observed.provenance().description());
    observed.provenance().location().ifPresent(text::account);
    text.account(snapshot.sourceRevision());
    var application = observed.applicationEffects().forField(path);
    application.ifPresent(
        rule -> {
          text.account(rule.explanation());
          for (var timing : rule.timings()) text.account(timing.name());
        });
    var root = snapshot.definition().type();
    var baseline = value(root, observed.baseline(), path, text);
    var draft = value(root, snapshot.draft(), path, text);
    Value effective = Value.unavailable("No effective value has been observed");
    Optional<DocumentReview.Effective> effectiveObservation = Optional.empty();
    boolean localRow = path.segments().stream().anyMatch(FieldPath.Row.class::isInstance);
    if (observed.effective().isPresent()) {
      var view = observed.effective().orElseThrow();
      var report = ValidationReport.VALID;
      boolean captured = false;
      if (localRow)
        effective = Value.unavailable("Editor row identity does not identify an effective row");
      else {
        DraftTree values = null;
        try {
          var current = view.value();
          if (current.isPresent())
            values =
                DraftTree.from(
                    snapshot.definition().type(), current.orElseThrow(), snapshot.draft().limits());
        } catch (RuntimeException failed) {
          report =
              new ValidationReport(
                  java.util.List.of(
                      design.inherent.reform.validation.Issue.error(
                          "information.effective",
                          path,
                          "Effective values could not be projected")),
                  false);
        }
        if (values != null) {
          effective = value(root, values, path, text);
          captured = true;
        }
      }
      effectiveObservation =
          Optional.of(
              new DocumentReview.Effective(
                  view.context(),
                  view.revision().sequence(),
                  view.availability(),
                  view.observationReport(),
                  captured,
                  report));
    }
    Value defaults;
    if (localRow)
      defaults = Value.unavailable("Editor row identity does not identify a default row");
    else {
      DraftTree values = null;
      try {
        values =
            DraftTree.from(
                snapshot.definition().type(),
                snapshot.definition().defaults(),
                snapshot.draft().limits());
      } catch (RuntimeException failed) {
        /* A missing/invalid declared default is not the current value. */
      }
      defaults =
          values == null
              ? Value.unavailable("Declared defaults could not be projected")
              : value(root, values, path, text);
    }
    return new FieldInformation(
        DraftRevision.of(snapshot),
        path,
        observed.provenance(),
        baseline,
        draft,
        effective,
        defaults,
        effectiveObservation,
        application,
        observed.observationReport());
  }

  private static Value value(
      ValueType<?> root, DraftTree tree, FieldPath path, DraftDescriptions text) {
    if (!tree.contains(path)) return Value.absent();
    var node = tree.at(path);
    var type = DraftTypes.at(root, tree, path);
    return new Value(
        State.PRESENT,
        Optional.of(text.render(node)),
        Optional.empty(),
        text.caption(type, node),
        text.choiceCaption(type, node));
  }
}
