package design.inherent.reform.operation;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.ConflictReview;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Manual review/choice/resolution over one actual editor; no save or structural merge. */
public final class DocumentConflicts<T> implements AutoCloseable {
  public record Limits(
      int maximumEntries,
      int maximumNodes,
      int maximumCharacters,
      int maximumPrepared,
      OperationScope.Limits operations) {
    public static final Limits STANDARD =
        new Limits(
            256,
            16384,
            131072,
            4,
            new OperationScope.Limits(2, 4, 8, new JsonLimits(65536, 16, 4096, 4096, 32, 32)));

    public Limits {
      Objects.requireNonNull(operations);
      if (maximumEntries < 1
          || maximumNodes < 1
          || maximumCharacters < 1
          || maximumPrepared < 1
          || operations.maximumDefinitions() < 2)
        throw new IllegalArgumentException("Invalid manual conflict limits");
    }
  }

  public record Entry(
      FieldPath path,
      Optional<String> selector,
      boolean wholeValue,
      String baseline,
      String observed,
      String draft) {}

  /** This service's actual issued instance is required; descriptive IDs grant no authority. */
  public static final class Preview {
    private final UUID id = UUID.randomUUID();
    private final DraftRevision target;
    private final ConflictReview.Basis basis;
    private final String baselineRevision, observedRevision;
    private final SourceInfo source;
    private final ValidationReport draftValidation, observationReport;
    private final List<Entry> entries;

    private Preview(
        DraftRevision target,
        ConflictReview review,
        SourceInfo source,
        ValidationReport draftValidation,
        ValidationReport observationReport,
        List<Entry> entries) {
      this.target = target;
      basis = review.basis();
      baselineRevision = review.baselineRevision();
      observedRevision = review.observedRevision();
      this.source = source;
      this.draftValidation = draftValidation;
      this.observationReport = observationReport;
      this.entries = List.copyOf(entries);
    }

    public UUID id() {
      return id;
    }

    public DraftRevision target() {
      return target;
    }

    public ConflictReview.Basis basis() {
      return basis;
    }

    public String baselineRevision() {
      return baselineRevision;
    }

    public String observedRevision() {
      return observedRevision;
    }

    public SourceInfo source() {
      return source;
    }

    public ValidationReport draftValidation() {
      return draftValidation;
    }

    public ValidationReport observationReport() {
      return observationReport;
    }

    public List<Entry> entries() {
      return entries;
    }
  }

  /** A resolved draft is an effect even when its subsequent owner validation is unavailable. */
  public record Resolution<T>(
      EditorActor.View<T> resolved,
      Optional<EditorActor.Validation> validation,
      ValidationReport report) {}

  private record Input(String review, List<ConflictReview.Choice> choices) {}

  private record Captured<T>(
      EditorActor.Inspection<T> inspection,
      ConflictReview review,
      ValidationReport validation,
      ValidationReport observationReport) {}

  private static final class Prepared {
    final Preview preview;
    final ConflictReview review;
    final Map<FieldPath, ConflictReview.Choice> choices = new LinkedHashMap<>();

    Prepared(Preview preview, ConflictReview review) {
      this.preview = preview;
      this.review = review;
    }
  }

  private final OwnerContext context;
  private final OwnerContext.Access access;
  private final EditorActor<T> actor;
  private final FieldIndex fields;
  private final Limits limits;
  private final Function<OperationDescriptor.EntryPoint, CompletionStage<?>> readiness;
  private final OperationScope scope;
  private final OwnerContext.CloseHook cleanup;
  private final Map<UUID, Prepared> prepared = new LinkedHashMap<>();
  private final OperationDescriptor<String, Preview> preview;
  private final OperationDescriptor<Input, Resolution<T>> resolve;
  private volatile boolean closed;

  DocumentConflicts(
      OwnerContext context,
      OwnerContext.Access access,
      EditorActor<T> actor,
      FieldIndex fields,
      Limits limits,
      Function<OperationDescriptor.EntryPoint, CompletionStage<?>> readiness) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.access = Objects.requireNonNull(access);
    this.actor = Objects.requireNonNull(actor);
    this.fields = Objects.requireNonNull(fields);
    this.limits = Objects.requireNonNull(limits);
    this.readiness = Objects.requireNonNull(readiness);
    access.require(OwnerContext.Right.READ);
    if (!context.id().equals(actor.context()) || !context.id().equals(access.context()))
      throw new IllegalArgumentException("Manual conflict requires its actual editor/context");
    scope = new OperationScope(context, access, limits.operations());
    try {
      var entries =
          Set.of(
              OperationDescriptor.EntryPoint.GUI,
              OperationDescriptor.EntryPoint.COMMAND,
              OperationDescriptor.EntryPoint.INTERNAL);
      preview =
          new OperationDescriptor<>(
              DocumentId.parse("reform:conflict/preview"),
              ValueTypes.text(0),
              OwnerContext.Right.READ,
              Set.of(OperationDescriptor.Effect.INSPECT),
              entries,
              Optional.of(actor.definition().id()));
      var reviewId = FieldKey.of("review", ValueTypes.text(36));
      var choices =
          FieldKey.of(
              "choices",
              ValueTypes.list(
                  ValueTypes.enumeration(ConflictReview.Choice.class), limits.maximumEntries()));
      var input =
          RecordType.<Input>builder(v -> new Input(v.get(reviewId), v.get(choices)))
              .field(reviewId, Input::review)
              .field(choices, Input::choices)
              .build();
      resolve =
          new OperationDescriptor<>(
              DocumentId.parse("reform:conflict/resolve"),
              input,
              OwnerContext.Right.READ,
              Set.of(OperationDescriptor.Effect.EDIT_DRAFT),
              entries,
              Optional.of(actor.definition().id()));
      scope.register(preview, (execution, ignored) -> prepare(execution));
      scope.register(resolve, this::resolvePrepared);
      scope.freeze();
      cleanup = context.onClose(this::close);
    } catch (RuntimeException | Error failed) {
      scope.close();
      throw failed;
    }
  }

  private void requireCurrent() {
    context.requireOwner();
    access.require(OwnerContext.Right.READ);
    if (closed) throw expired();
  }

  public boolean isOpen() {
    return !closed && context.isOpen() && access.isOpen();
  }

  /** Cached native-thread freshness only; no permission callback, IO or validation. */
  public boolean isCurrent(Preview selected) {
    if (!isOpen()) return false;
    context.checkOwnerThread();
    if (selected == null) return false;
    var found = prepared.get(selected.id());
    var view = actor.view();
    return found != null
        && found.preview == selected
        && view.conflictObservationAvailable()
        && view.review().orElse(null) == found.review
        && view.snapshot()
            .filter(snapshot -> selected.target().equals(DraftRevision.of(snapshot)))
            .isPresent();
  }

  public OperationHandle<Preview> preview(OperationDescriptor.EntryPoint entry) {
    requireCurrent();
    return scope.submit(preview, entry, "", readiness.apply(entry));
  }

  public Optional<Preview> review(UUID id) {
    requireCurrent();
    var found = prepared.get(Objects.requireNonNull(id));
    return found == null ? Optional.empty() : Optional.of(found.preview);
  }

  /** Most recent retained current review; useful for restoring local choices after navigation. */
  public Optional<Preview> latest() {
    requireCurrent();
    Preview latest = null;
    for (var found : prepared.values()) if (isCurrent(found.preview)) latest = found.preview;
    return Optional.ofNullable(latest);
  }

  /** A local selection only. It neither edits the document nor supplies a default choice. */
  public void choose(Preview selected, FieldPath path, Optional<ConflictReview.Choice> choice) {
    requireCurrent();
    var found = requirePrepared(selected);
    requireTarget(found);
    Objects.requireNonNull(choice);
    if (selected.entries().stream().noneMatch(entry -> entry.path().equals(path)))
      throw new PolicyRefusal("conflict.field", "Select an actual field in this review");
    if (choice.isPresent()) found.choices.put(path, choice.orElseThrow());
    else found.choices.remove(path);
  }

  public Map<FieldPath, ConflictReview.Choice> choices(Preview selected) {
    requireCurrent();
    var found = requirePrepared(selected);
    requireTarget(found);
    return Map.copyOf(found.choices);
  }

  public OperationHandle<Resolution<T>> resolve(
      OperationDescriptor.EntryPoint entry, Preview selected) {
    return resolve(entry, selected, choices(selected));
  }

  public OperationHandle<Resolution<T>> resolve(
      OperationDescriptor.EntryPoint entry,
      Preview selected,
      Map<FieldPath, ConflictReview.Choice> choices) {
    requireCurrent();
    var found = requirePrepared(selected);
    requireTarget(found);
    var frozen = Map.copyOf(choices);
    var paths =
        selected.entries().stream().map(Entry::path).collect(java.util.stream.Collectors.toSet());
    if (!frozen.keySet().equals(paths))
      throw new PolicyRefusal("conflict.choices", "Choose every review field explicitly");
    return scope.submit(
        resolve,
        entry,
        new Input(
            selected.id().toString(),
            selected.entries().stream().map(value -> frozen.get(value.path())).toList()));
  }

  public Optional<OperationHandle<?>> operation(UUID id) {
    requireCurrent();
    var found = scope.find(Objects.requireNonNull(id));
    requireCurrent();
    return found;
  }

  public List<OperationSnapshot<?>> operations() {
    return scope.snapshots();
  }

  private CompletionStage<Preview> prepare(OperationScope.Execution execution) {
    execution.checkpoint();
    requireCurrent();
    return actor
        .inspect()
        .thenCompose(
            inspection ->
                context.onOwner(
                    () -> {
                      execution.checkpoint();
                      requireCurrent();
                      var view = actor.view();
                      if (!view.conflictObservationAvailable()
                          || view.snapshot()
                              .filter(
                                  value ->
                                      DraftRevision.of(value)
                                          .equals(DraftRevision.of(inspection.snapshot())))
                              .isEmpty()) throw unavailable(view);
                      return new Captured<>(
                          inspection,
                          view.review().orElseThrow(),
                          view.validation(),
                          view.observationReport());
                    }))
        .thenCompose(captured -> context.onWorker(() -> describe(execution, captured)))
        .thenCompose(
            result ->
                context.onOwner(
                    () -> {
                      execution.checkpoint();
                      requireCurrent();
                      requireTarget(result);
                      while (prepared.size() >= limits.maximumPrepared())
                        prepared.remove(prepared.keySet().iterator().next());
                      prepared.put(result.preview.id(), result);
                      return result.preview;
                    }));
  }

  private Prepared describe(OperationScope.Execution execution, Captured<T> captured) {
    execution.checkpoint();
    access.requireCurrent();
    if (closed) throw expired();
    var values = captured.review().entries();
    if (values.size() > limits.maximumEntries())
      throw new PolicyRefusal(
          "conflict.entries", "Complete conflict exceeds the admitted field count");
    var text =
        new DraftDescriptions(
            limits.maximumNodes(),
            limits.maximumCharacters(),
            "conflict.preview_limit",
            "Complete conflict input exceeds the admitted review limits");
    var entries = new java.util.ArrayList<Entry>();
    for (var value : values) {
      execution.checkpoint();
      entries.add(
          new Entry(
              value.path(),
              fields.field(value.path()).map(FieldIndex.Field::selector),
              !(value.baseline() instanceof DraftNode.Scalar
                  && value.observed() instanceof DraftNode.Scalar
                  && value.draft() instanceof DraftNode.Scalar),
              text.render(value.baseline()),
              text.render(value.observed()),
              text.render(value.draft())));
    }
    var preview =
        new Preview(
            DraftRevision.of(captured.inspection().snapshot()),
            captured.review(),
            captured.inspection().provenance(),
            captured.validation(),
            captured.observationReport(),
            entries);
    return new Prepared(preview, captured.review());
  }

  private void requireTarget(Prepared selected) {
    var view = actor.view();
    if (!view.conflictObservationAvailable()
        || view.review().orElse(null) != selected.review
        || view.snapshot()
            .filter(value -> selected.preview.target().equals(DraftRevision.of(value)))
            .isEmpty()) throw unavailable(view);
  }

  private Prepared requirePrepared(Preview selected) {
    if (selected == null) throw expired();
    var found = prepared.get(selected.id());
    if (found == null || found.preview != selected) throw expired();
    return found;
  }

  private CompletionStage<Resolution<T>> resolvePrepared(
      OperationScope.Execution execution, Input input) {
    execution.checkpoint();
    requireCurrent();
    final UUID identity;
    try {
      identity = UUID.fromString(input.review());
    } catch (IllegalArgumentException invalid) {
      throw expired();
    }
    var selected = prepared.get(identity);
    if (selected == null) throw expired();
    requireTarget(selected);
    if (input.choices().size() != selected.preview.entries().size())
      throw new PolicyRefusal("conflict.choices", "Choose every review field explicitly");
    var choices = new LinkedHashMap<FieldPath, ConflictReview.Choice>();
    for (int index = 0; index < input.choices().size(); index++)
      choices.put(selected.preview.entries().get(index).path(), input.choices().get(index));
    execution.checkpoint();
    prepared.remove(identity);
    return actor.resolve(selected.review, choices).thenCompose(this::validateResolved);
  }

  private CompletionStage<Resolution<T>> validateResolved(EditorActor.View<T> resolved) {
    final CompletionStage<EditorActor.Validation> validationStage;
    try {
      validationStage = actor.validate();
    } catch (RuntimeException unavailable) {
      return java.util.concurrent.CompletableFuture.completedStage(
          new Resolution<>(resolved, Optional.empty(), failure(unavailable)));
    }
    return validationStage.handle(
        (validation, failed) -> {
          if (failed != null) return new Resolution<>(resolved, Optional.empty(), failure(failed));
          var snapshot = resolved.snapshot().orElseThrow();
          boolean same =
              validation.context().equals(snapshot.context())
                  && validation.session().equals(snapshot.session())
                  && validation.generation() == snapshot.generation()
                  && validation.version() == snapshot.version()
                  && validation.sourceRevision().equals(snapshot.sourceRevision());
          var report =
              same
                  ? validation.report()
                  : new ValidationReport(
                      List.of(
                          Issue.error(
                              "conflict.validation_changed",
                              FieldPath.ROOT,
                              "Choices were staged; validation observed a later draft")),
                      false);
          return new Resolution<>(resolved, Optional.of(validation), report);
        });
  }

  private static ValidationReport failure(Throwable failed) {
    while (failed instanceof CompletionException && failed.getCause() != null)
      failed = failed.getCause();
    return failed instanceof PolicyRefusal refused
        ? refused.report()
        : new ValidationReport(
            List.of(
                Issue.error(
                    "conflict.validation_unavailable",
                    FieldPath.ROOT,
                    "Choices were staged; owner validation is unavailable")),
            false);
  }

  private static PolicyRefusal unavailable(EditorActor.View<?> view) {
    return new PolicyRefusal(
        "conflict.changed",
        view.observationReport().valid()
            ? "The draft or review changed; read current values and review it again"
            : "The current source could not be observed; read it again before resolving");
  }

  private static PolicyRefusal expired() {
    return new PolicyRefusal("conflict.expired", "The conflict review expired; prepare it again");
  }

  @Override
  public void close() {
    if (closed) return;
    context.checkOwnerThread();
    closed = true;
    prepared.clear();
    scope.close();
    cleanup.close();
  }
}
