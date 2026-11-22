package design.inherent.reform.operation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftBasis;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.ApplicationEffects;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SettingsHandle;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Identified source/value inspection; neither a fresh source read nor permission to save. */
public final class DocumentReview {
  /** Optional meanings accompany raw observations; they never replace raw report or export data. */
  public record NumericValues(
      Optional<NumericDisplay.Caption> baseline,
      Optional<NumericDisplay.Caption> draft,
      Optional<NumericDisplay.Caption> effective) {
    public static final NumericValues EMPTY =
        new NumericValues(Optional.empty(), Optional.empty(), Optional.empty());

    public NumericValues {
      Objects.requireNonNull(baseline);
      Objects.requireNonNull(draft);
      Objects.requireNonNull(effective);
    }
  }

  /** Complete raw-value projection bounds, separate from owner work and transport/heap limits. */
  public record Limits(int maximumFields, int maximumNodes, int maximumCharacters) {
    public static final Limits STANDARD = new Limits(512, 65536, 524288);

    public Limits {
      if (maximumFields < 1 || maximumNodes < 1 || maximumCharacters < 1)
        throw new IllegalArgumentException("Review bounds must be positive");
    }
  }

  /** Collections remain whole values; editor row IDs are not inferred in effective snapshots. */
  public record Entry(
      FieldPath path,
      String selector,
      boolean wholeValue,
      String baseline,
      String draft,
      Optional<String> effective,
      Optional<ApplicationEffects.Rule> application,
      NumericValues numericValues) {
    public Entry(
        FieldPath path,
        String selector,
        boolean wholeValue,
        String baseline,
        String draft,
        Optional<String> effective,
        Optional<ApplicationEffects.Rule> application) {
      this(
          path, selector, wholeValue, baseline, draft, effective, application, NumericValues.EMPTY);
    }

    public Entry {
      Objects.requireNonNull(path);
      Objects.requireNonNull(selector);
      Objects.requireNonNull(baseline);
      Objects.requireNonNull(draft);
      Objects.requireNonNull(effective);
      Objects.requireNonNull(application);
      Objects.requireNonNull(numericValues);
    }
  }

  /** Owner observation and captured value details are distinct, including a failed reload. */
  public record Effective(
      UUID context,
      long revision,
      SettingsHandle.Availability availability,
      ValidationReport observationReport,
      boolean valuesCaptured,
      ValidationReport captureReport) {
    public Effective {
      Objects.requireNonNull(context);
      Objects.requireNonNull(availability);
      Objects.requireNonNull(observationReport);
      Objects.requireNonNull(captureReport);
      if (revision < 0 || valuesCaptured && !captureReport.valid())
        throw new IllegalArgumentException("Invalid effective capture identity/state");
    }
  }

  private final DraftRevision target;
  private final SourceInfo source;
  private final Optional<DraftBasis> draftBasis;
  private final ValidationReport sourceReport;
  private final DocumentActions.Comparison comparison;
  private final List<Entry> entries;
  private final Optional<Effective> effective;
  private final ValidationReport report;
  private final ApplicationEffects applicationEffects;

  private DocumentReview(
      DraftRevision target,
      SourceInfo source,
      Optional<DraftBasis> draftBasis,
      ValidationReport sourceReport,
      DocumentActions.Comparison comparison,
      List<Entry> entries,
      Optional<Effective> effective,
      ApplicationEffects applicationEffects) {
    this.target = Objects.requireNonNull(target);
    this.source = Objects.requireNonNull(source);
    this.draftBasis = Objects.requireNonNull(draftBasis);
    this.sourceReport = Objects.requireNonNull(sourceReport);
    this.comparison = Objects.requireNonNull(comparison);
    this.entries = List.copyOf(entries);
    this.effective = Objects.requireNonNull(effective);
    this.applicationEffects = Objects.requireNonNull(applicationEffects);
    var observations = sourceReport.and(comparison.report());
    this.report =
        effective
            .map(value -> observations.and(value.observationReport()).and(value.captureReport()))
            .orElse(observations);
    if (!target.equals(comparison.draft()))
      throw new IllegalArgumentException("Review observations require one actual draft identity");
  }

  public DraftRevision target() {
    return target;
  }

  public SourceInfo source() {
    return source;
  }

  public Optional<DraftBasis> draftBasis() {
    return draftBasis;
  }

  public ValidationReport sourceReport() {
    return sourceReport;
  }

  public DocumentActions.Comparison comparison() {
    return comparison;
  }

  public List<Entry> entries() {
    return entries;
  }

  public Optional<Effective> effective() {
    return effective;
  }

  /** Captured source/comparison/effective diagnostics; not validation of a future save. */
  public ValidationReport report() {
    return report;
  }

  public ApplicationEffects applicationEffects() {
    return applicationEffects;
  }

  static <T> List<FieldIndex.Field> admittedFields(
      EditorActor.Inspection<T> observed, FieldIndex fields, Limits limits) {
    var declared = fields.fields();
    for (var field : declared)
      if (!observed.baseline().contains(field.path())
          || !observed.snapshot().draft().contains(field.path())) {
        declared =
            List.of(
                new FieldIndex.Field(FieldPath.ROOT, "$", observed.snapshot().definition().type()));
        break;
      }
    if (declared.size() > limits.maximumFields())
      throw new PolicyRefusal("review.fields", "Complete review exceeds its field limit");
    return declared;
  }

  static <T> DocumentReview capture(
      EditorActor.Inspection<T> observed,
      DocumentActions.Comparison comparison,
      FieldIndex fields,
      Limits limits) {
    return capture(observed, comparison, admittedFields(observed, fields, limits), limits);
  }

  static <T> DocumentReview capture(
      EditorActor.Inspection<T> observed,
      DocumentActions.Comparison comparison,
      List<FieldIndex.Field> declared,
      Limits limits) {
    var target = DraftRevision.of(observed.snapshot());
    if (!target.equals(comparison.draft()))
      throw new IllegalArgumentException("Review comparison belongs to another captured draft");
    var text =
        new DraftDescriptions(
            limits.maximumNodes(),
            limits.maximumCharacters(),
            "review.preview_limit",
            "Complete review input exceeds its admitted limits");
    text.account(observed.provenance().kind());
    text.account(observed.provenance().description());
    observed.provenance().location().ifPresent(text::account);
    text.account(target.sourceRevision());
    var basis = observed.snapshot().draft().basis();
    basis.ifPresent(
        selected -> {
          text.account(selected.revision());
          text.account(selected.source().kind());
          text.account(selected.source().description());
          selected.source().location().ifPresent(text::account);
        });
    for (var rule : observed.applicationEffects().rules()) account(text, rule);
    DraftTree values = null;
    Optional<Effective> effective = Optional.empty();
    if (observed.effective().isPresent()) {
      var view = observed.effective().orElseThrow();
      var report = ValidationReport.VALID;
      try {
        var value = view.value();
        if (value.isPresent())
          values =
              DraftTree.from(
                  observed.snapshot().definition().type(),
                  value.orElseThrow(),
                  observed.snapshot().draft().limits());
      } catch (RuntimeException unavailable) {
        report =
            new ValidationReport(
                List.of(
                    Issue.error(
                        "review.effective_unavailable",
                        FieldPath.ROOT,
                        "Effective value details could not be captured")),
                false);
      }
      effective =
          Optional.of(
              new Effective(
                  view.context(),
                  view.revision().sequence(),
                  view.availability(),
                  view.observationReport(),
                  values != null,
                  report));
    }
    if (values != null)
      for (var field : declared)
        if (!values.contains(field.path())) {
          declared =
              List.of(
                  new FieldIndex.Field(
                      FieldPath.ROOT, "$", observed.snapshot().definition().type()));
          break;
        }
    var entries = new ArrayList<Entry>();
    for (var field : declared) {
      text.account(field.selector());
      text.account(field.path().toString());
      var application = observed.applicationEffects().forField(field.path());
      application.ifPresent(rule -> account(text, rule));
      entries.add(
          new Entry(
              field.path(),
              field.selector(),
              !field.scalar(),
              text.render(observed.baseline().at(field.path())),
              text.render(observed.snapshot().draft().at(field.path())),
              values == null ? Optional.empty() : Optional.of(text.render(values.at(field.path()))),
              application,
              new NumericValues(
                  text.caption(field.type(), observed.baseline().at(field.path())),
                  text.caption(field.type(), observed.snapshot().draft().at(field.path())),
                  values == null
                      ? Optional.empty()
                      : text.caption(field.type(), values.at(field.path())))));
    }
    return new DocumentReview(
        target,
        observed.provenance(),
        basis,
        observed.observationReport(),
        comparison,
        entries,
        effective,
        observed.applicationEffects());
  }

  private static void account(DraftDescriptions text, ApplicationEffects.Rule rule) {
    text.account(rule.path().toString());
    text.account(rule.explanation());
    for (var timing : rule.timings()) text.account(timing.name());
  }
}
