package design.inherent.reform.operation;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftEdits;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Typed GUI/command services over one admitted editor. No paths, actors or writers are inferred.
 */
public final class DocumentActions<T> implements AutoCloseable {
  public record Empty() {
    public static final Empty VALUE = new Empty();
  }

  public record FieldInput(String selector, String text) {}

  public record FieldDescription(
      FieldPath path, String selector, ValueShape shape, Optional<String> defaultText) {}

  public record Description(DocumentId document, UUID context, List<FieldDescription> fields) {
    public Description {
      fields = List.copyOf(fields);
    }
  }

  public record Change(FieldPath path, String beforeJson, String afterJson) {}

  public record Comparison(
      DraftRevision draft, boolean authoredChanges, List<Change> changes, ValidationReport report) {
    public Comparison {
      changes = List.copyOf(changes);
    }
  }

  /** In-memory value JSON. This is not the owner's file format, a file write or publication. */
  public static final class ExportArtifact {
    private final DraftRevision draft;
    private final int schemaVersion;
    private final byte[] bytes;
    private final String sha256;

    private ExportArtifact(DraftRevision draft, int schemaVersion, byte[] bytes) {
      this.draft = draft;
      this.schemaVersion = schemaVersion;
      this.bytes = bytes.clone();
      try {
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    public DraftRevision draft() {
      return draft;
    }

    public int schemaVersion() {
      return schemaVersion;
    }

    public String mediaType() {
      return "application/json";
    }

    public String representation() {
      return "document-value";
    }

    public String sha256() {
      return sha256;
    }

    public int size() {
      return bytes.length;
    }

    public byte[] bytes() {
      return bytes.clone();
    }

    public String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static final ValueType<Empty> EMPTY =
      RecordType.<Empty>builder(values -> Empty.VALUE).build();
  private static final FieldKey<String> SELECTOR = FieldKey.of("selector", ValueTypes.text(4096));
  private static final FieldKey<String> TEXT = FieldKey.of("text", ValueTypes.text(32768));
  private static final ValueType<FieldInput> FIELD =
      RecordType.<FieldInput>builder(
              values -> new FieldInput(values.get(SELECTOR), values.get(TEXT)))
          .field(SELECTOR, FieldInput::selector)
          .field(TEXT, FieldInput::text)
          .build();

  private final OwnerContext context;
  private final OwnerContext.Access access;
  private final EditorActor<T> actor;
  private final DocumentDefinition<T> definition;
  private final OperationScope scope;
  private final FieldIndex fields;
  private final int maximumExportBytes;
  private final DocumentReview.Limits reviewLimits;
  private boolean ownsContext;
  private boolean closed;
  private DocumentImports<T> imports;
  private Object importEpoch;
  private DocumentConflicts<T> conflicts;
  private Object conflictEpoch;
  private OperationHandle<EditorActor.View<T>> initialLoad;
  private final OperationDescriptor<Empty, EditorActor.View<T>> load,
      undo,
      redo,
      revert,
      defaults,
      inspect;
  private final OperationDescriptor<FieldInput, EditorActor.View<T>> edit;
  private final OperationDescriptor<DraftEdit, EditorActor.View<T>> structure;
  private final OperationDescriptor<Empty, EditorActor.Commit<T>> save, apply, admit;
  private final OperationDescriptor<DraftRevision, EditorActor.Commit<T>> reviewedCommit;
  private final OperationDescriptor<Empty, EditorActor.Validation> validate;
  private final OperationDescriptor<Empty, EditorActor.Inspection<T>> explain;
  private final OperationDescriptor<Empty, Comparison> compare;
  private final OperationDescriptor<Empty, Description> describe;
  private final OperationDescriptor<Empty, ExportArtifact> export;
  private final OperationDescriptor<Empty, DocumentReview> review;

  public DocumentActions(
      OwnerContext context,
      OwnerContext.Access access,
      EditorActor<T> actor,
      OperationScope.Limits limits,
      int maximumExportBytes) {
    this(context, access, actor, limits, maximumExportBytes, DocumentReview.Limits.STANDARD);
  }

  public DocumentActions(
      OwnerContext context,
      OwnerContext.Access access,
      EditorActor<T> actor,
      OperationScope.Limits limits,
      int maximumExportBytes,
      DocumentReview.Limits reviewLimits) {
    context.requireOwner();
    if (!context.id().equals(actor.context()) || maximumExportBytes < 1)
      throw new IllegalArgumentException(
          "Actions require their actual editing context and export bound");
    if (limits.maximumDefinitions() < 18)
      throw new IllegalArgumentException("Actions require room for eighteen operation definitions");
    this.context = context;
    this.access = access;
    this.actor = actor;
    this.definition = actor.definition();
    this.maximumExportBytes = maximumExportBytes;
    this.reviewLimits = java.util.Objects.requireNonNull(reviewLimits);
    fields = new FieldIndex(definition.type(), 16384, 128);
    scope = new OperationScope(context, access, limits);
    load = query("reload", OperationDescriptor.Effect.INSPECT, execution -> actor.load());
    inspect =
        query(
            "inspect",
            OperationDescriptor.Effect.INSPECT,
            execution -> CompletableFuture.completedStage(actor.view()));
    undo = query("undo", OperationDescriptor.Effect.EDIT_DRAFT, execution -> actor.undo());
    redo = query("redo", OperationDescriptor.Effect.EDIT_DRAFT, execution -> actor.redo());
    revert = query("revert", OperationDescriptor.Effect.EDIT_DRAFT, execution -> actor.revert());
    defaults = query("defaults", OperationDescriptor.Effect.EDIT_DRAFT, execution -> actor.reset());
    edit =
        descriptor(
            "edit", FIELD, OwnerContext.Right.READ, Set.of(OperationDescriptor.Effect.EDIT_DRAFT));
    scope.register(
        edit,
        (execution, request) -> {
          execution.checkpoint();
          var selected =
              fields
                  .field(request.selector())
                  .filter(FieldIndex.Field::scalar)
                  .orElseThrow(
                      () ->
                          new PolicyRefusal(
                              "field.unavailable", "Select a declared editable scalar field"));
          return actor.setRaw(selected.path(), request.text());
        });
    structure =
        descriptor(
            "structure",
            DraftEdits.type(),
            OwnerContext.Right.READ,
            Set.of(OperationDescriptor.Effect.EDIT_DRAFT));
    scope.register(
        structure,
        (execution, request) -> {
          execution.checkpoint();
          return actor.edit(request);
        });
    save =
        descriptor(
            "save",
            EMPTY,
            OwnerContext.Right.WRITE,
            Set.of(OperationDescriptor.Effect.PERSIST, OperationDescriptor.Effect.APPLY));
    scope.register(
        save,
        (execution, request) ->
            actor.save(execution::checkpoint).thenApply(DocumentActions::checkedSave));
    admit =
        descriptor(
            "admit", EMPTY, OwnerContext.Right.WRITE, Set.of(OperationDescriptor.Effect.APPLY));
    scope.register(
        admit,
        (execution, request) ->
            actor.admit(execution::checkpoint).thenApply(DocumentActions::checkedSave));
    reviewedCommit =
        descriptor(
            "commit_reviewed",
            DraftRevision.type(),
            OwnerContext.Right.WRITE,
            actor.commitKind() == DocumentSession.CommitKind.PERSIST
                ? Set.of(OperationDescriptor.Effect.PERSIST, OperationDescriptor.Effect.APPLY)
                : Set.of(OperationDescriptor.Effect.APPLY));
    scope.register(
        reviewedCommit,
        (execution, request) ->
            (actor.commitKind() == DocumentSession.CommitKind.PERSIST
                    ? actor.save(request, execution::checkpoint)
                    : actor.admit(request, execution::checkpoint))
                .thenApply(DocumentActions::checkedSave));
    apply =
        descriptor(
            "apply_saved",
            EMPTY,
            OwnerContext.Right.WRITE,
            Set.of(OperationDescriptor.Effect.APPLY));
    scope.register(
        apply,
        (execution, request) ->
            actor.applySaved(execution::checkpoint).thenApply(DocumentActions::checkedSave));
    validate = query("validate", OperationDescriptor.Effect.INSPECT, execution -> actor.validate());
    explain = query("explain", OperationDescriptor.Effect.INSPECT, execution -> actor.inspect());
    compare =
        query(
            "compare",
            OperationDescriptor.Effect.INSPECT,
            execution ->
                actor.inspect().thenCompose(observed -> context.onWorker(() -> compare(observed))));
    describe =
        query(
            "describe",
            OperationDescriptor.Effect.INSPECT,
            execution -> context.onWorker(this::description));
    export =
        query(
            "export",
            OperationDescriptor.Effect.EXPORT,
            execution ->
                actor.inspect().thenCompose(observed -> context.onWorker(() -> export(observed))));
    review =
        query(
            "review",
            OperationDescriptor.Effect.INSPECT,
            execution ->
                actor
                    .inspect()
                    .thenCompose(
                        observed ->
                            context.onWorker(
                                () -> {
                                  execution.checkpoint();
                                  var admitted =
                                      DocumentReview.admittedFields(observed, fields, reviewLimits);
                                  var comparison =
                                      compare(
                                          observed,
                                          reviewLimits.maximumFields(),
                                          reviewLimits.maximumCharacters());
                                  execution.checkpoint();
                                  return DocumentReview.capture(
                                      observed, comparison, admitted, reviewLimits);
                                }))
                    .thenCompose(
                        captured ->
                            context.onOwner(
                                () -> {
                                  access.require(OwnerContext.Right.READ);
                                  execution.checkpoint();
                                  var current = actor.view();
                                  if (current.availability() != EditorActor.Availability.READY)
                                    throw unavailable(current);
                                  return captured;
                                })));
    scope.freeze();
  }

  public DocumentActions(OwnerContext context, OwnerContext.Access access, EditorActor<T> actor) {
    this(context, access, actor, OperationScope.Limits.STANDARD, 262144);
  }

  /**
   * Transfer this shorter-lived context to the editor. Ordinary shared document contexts stay
   * borrowed.
   */
  public static <T> DocumentActions<T> owningContext(
      OwnerContext context,
      OwnerContext.Access access,
      EditorActor<T> actor,
      OperationScope.Limits limits,
      int maximumExportBytes) {
    var actions = new DocumentActions<>(context, access, actor, limits, maximumExportBytes);
    actions.ownsContext = true;
    return actions;
  }

  private static <T> EditorActor.Commit<T> checkedSave(EditorActor.Commit<T> saved) {
    if (saved.receipt().isEmpty()
        && saved.editorReport().issues().stream()
            .anyMatch(issue -> issue.code().equals("operation.cancelled")))
      throw new PolicyRefusal(saved.editorReport());
    return saved;
  }

  private <I, O> OperationDescriptor<I, O> descriptor(
      String name,
      ValueType<I> input,
      OwnerContext.Right right,
      Set<OperationDescriptor.Effect> effects) {
    return new OperationDescriptor<>(
        DocumentId.parse("reform:document/" + name),
        input,
        right,
        effects,
        Set.of(
            OperationDescriptor.EntryPoint.GUI,
            OperationDescriptor.EntryPoint.COMMAND,
            OperationDescriptor.EntryPoint.INTERNAL),
        Optional.of(definition.id()));
  }

  private <O> OperationDescriptor<Empty, O> query(
      String name,
      OperationDescriptor.Effect effect,
      Function<OperationScope.Execution, CompletionStage<O>> handler) {
    var descriptor =
        this.<Empty, O>descriptor(name, EMPTY, OwnerContext.Right.READ, Set.of(effect));
    scope.register(
        descriptor,
        (execution, input) -> {
          execution.checkpoint();
          return handler.apply(execution);
        });
    return descriptor;
  }

  public DocumentDefinition<T> definition() {
    return definition;
  }

  /** Replace this editor's borrowed-source import review, preserving its actual draft/history. */
  public DocumentImports<T> imports(
      ImportSource source, List<? extends ImportFormat<T>> formats, DocumentImports.Limits limits) {
    context.requireOwner();
    var epoch = new Object();
    importEpoch = epoch;
    access.require(OwnerContext.Right.READ);
    requireImportEpoch(epoch);
    var previous = imports;
    imports = null;
    if (previous != null) previous.close();
    requireImportEpoch(epoch);
    var requested =
        new DocumentImports<>(context, access, actor, source, formats, limits, this::readiness);
    try {
      requireImportEpoch(epoch);
      imports = requested;
      return requested;
    } catch (RuntimeException | Error superseded) {
      requested.close();
      throw superseded;
    }
  }

  private void requireImportEpoch(Object requested) {
    if (closed || requested != importEpoch)
      throw new PolicyRefusal("import.superseded", "A newer import source replaced this request");
  }

  public DocumentImports<T> imports(ImportSource source, List<? extends ImportFormat<T>> formats) {
    return imports(source, formats, DocumentImports.Limits.STANDARD);
  }

  /** Current borrowed-source review only; no lookup creates an editor or grants source access. */
  public Optional<DocumentImports<T>> activeImports() {
    context.requireOwner();
    access.require(OwnerContext.Right.READ);
    if (closed) throw new PolicyRefusal("editor.closed", "This editing workflow is closed");
    return Optional.ofNullable(imports).filter(DocumentImports::isOpen);
  }

  /** Current manual review workflow; creating it reads no source and changes no draft. */
  public DocumentConflicts<T> conflicts() {
    context.requireOwner();
    access.require(OwnerContext.Right.READ);
    if (closed) throw new PolicyRefusal("editor.closed", "This editing workflow is closed");
    return conflicts != null && conflicts.isOpen()
        ? conflicts
        : conflicts(DocumentConflicts.Limits.STANDARD);
  }

  /** Noncreating lookup; retained review IDs never create another editor or review scope. */
  public Optional<DocumentConflicts<T>> activeConflicts() {
    context.requireOwner();
    access.require(OwnerContext.Right.READ);
    if (closed) throw new PolicyRefusal("editor.closed", "This editing workflow is closed");
    return Optional.ofNullable(conflicts).filter(DocumentConflicts::isOpen);
  }

  /** Explicitly replace this editor's review presentation limits, preserving its actual draft. */
  public DocumentConflicts<T> conflicts(DocumentConflicts.Limits limits) {
    context.requireOwner();
    var epoch = new Object();
    conflictEpoch = epoch;
    access.require(OwnerContext.Right.READ);
    requireConflictEpoch(epoch);
    var previous = conflicts;
    conflicts = null;
    if (previous != null) previous.close();
    requireConflictEpoch(epoch);
    var requested =
        new DocumentConflicts<>(context, access, actor, fields, limits, this::readiness);
    try {
      requireConflictEpoch(epoch);
      conflicts = requested;
      return requested;
    } catch (RuntimeException | Error superseded) {
      requested.close();
      throw superseded;
    }
  }

  private void requireConflictEpoch(Object requested) {
    if (closed || conflictEpoch != requested)
      throw new PolicyRefusal(
          "conflict.superseded", "A newer conflict workflow replaced this request");
  }

  public DocumentSession.CommitKind commitKind() {
    return actor.commitKind();
  }

  /** Cached owner declaration; every actual operation still checks its authority and context. */
  public Set<DocumentSession.Operation> supportedOperations() {
    return actor.supportedOperations();
  }

  public OperationHandle<EditorActor.Commit<T>> commit(OperationDescriptor.EntryPoint entry) {
    return commitKind() == DocumentSession.CommitKind.PERSIST ? save(entry) : admit(entry);
  }

  /** Refuses a changed capture before preparation; later edits retain normal acknowledgement. */
  public OperationHandle<EditorActor.Commit<T>> commit(
      OperationDescriptor.EntryPoint entry, DraftRevision reviewed) {
    return scope.submit(reviewedCommit, entry, reviewed, readiness(entry));
  }

  public OperationHandle<EditorActor.Commit<T>> admit(OperationDescriptor.EntryPoint entry) {
    return scope.submit(admit, entry, Empty.VALUE, readiness(entry));
  }

  public UUID context() {
    return context.id();
  }

  public Optional<OperationHandle<?>> operation(UUID id) {
    return scope.find(id);
  }

  /** Coalesces first-use loading. A failed initial attempt needs an explicit reload to retry. */
  public OperationHandle<EditorActor.View<T>> ensureLoaded(OperationDescriptor.EntryPoint entry) {
    context.requireOwner();
    if (actor.view().availability() != EditorActor.Availability.LOADING) return inspect(entry);
    if (initialLoad == null) initialLoad = load(entry);
    return initialLoad;
  }

  public FieldIndex fields() {
    return fields;
  }

  public EditorActor.View<T> view() {
    return actor.view();
  }

  public List<OperationSnapshot<?>> operations() {
    return scope.snapshots();
  }

  public OperationHandle<EditorActor.View<T>> load(OperationDescriptor.EntryPoint entry) {
    context.requireOwner();
    boolean initial = actor.view().availability() == EditorActor.Availability.LOADING;
    if (initial && initialLoad != null && !initialLoad.snapshot().terminal()) return initialLoad;
    var requested = scope.submit(load, entry, Empty.VALUE);
    if (initial) initialLoad = requested;
    return requested;
  }

  private CompletionStage<?> readiness(OperationDescriptor.EntryPoint entry) {
    var observed = actor.view();
    if (observed.availability() == EditorActor.Availability.READY)
      return CompletableFuture.completedStage(null);
    if (observed.availability() == EditorActor.Availability.LOADING)
      return result(ensureLoaded(entry))
          .thenApply(
              view -> {
                if (view.availability() != EditorActor.Availability.READY) throw unavailable(view);
                return null;
              });
    return CompletableFuture.failedFuture(unavailable(observed));
  }

  private static PolicyRefusal unavailable(EditorActor.View<?> observed) {
    return observed.validation().valid()
        ? new PolicyRefusal("editor.unavailable", "No editing baseline is ready")
        : new PolicyRefusal(observed.validation());
  }

  public OperationHandle<EditorActor.View<T>> inspect(OperationDescriptor.EntryPoint entry) {
    return scope.submit(inspect, entry, Empty.VALUE);
  }

  public OperationHandle<EditorActor.View<T>> undo(OperationDescriptor.EntryPoint entry) {
    return scope.submit(undo, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.View<T>> redo(OperationDescriptor.EntryPoint entry) {
    return scope.submit(redo, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.View<T>> revert(OperationDescriptor.EntryPoint entry) {
    return scope.submit(revert, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.View<T>> defaults(OperationDescriptor.EntryPoint entry) {
    return scope.submit(defaults, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.View<T>> edit(
      OperationDescriptor.EntryPoint entry, String selector, String text) {
    return scope.submit(edit, entry, new FieldInput(selector, text), readiness(entry));
  }

  public OperationHandle<EditorActor.View<T>> edit(
      OperationDescriptor.EntryPoint entry, DraftEdit request) {
    return scope.submit(structure, entry, request, readiness(entry));
  }

  public OperationHandle<EditorActor.Commit<T>> save(OperationDescriptor.EntryPoint entry) {
    return scope.submit(save, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.Commit<T>> applySaved(OperationDescriptor.EntryPoint entry) {
    return scope.submit(apply, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.Validation> validate(OperationDescriptor.EntryPoint entry) {
    return scope.submit(validate, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<EditorActor.Inspection<T>> explain(OperationDescriptor.EntryPoint entry) {
    return scope.submit(explain, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<Comparison> compare(OperationDescriptor.EntryPoint entry) {
    return scope.submit(compare, entry, Empty.VALUE, readiness(entry));
  }

  /** One captured draft/source/effective comparison; no reload, preparation or effect. */
  public OperationHandle<DocumentReview> review(OperationDescriptor.EntryPoint entry) {
    return scope.submit(review, entry, Empty.VALUE, readiness(entry));
  }

  public OperationHandle<Description> describe(OperationDescriptor.EntryPoint entry) {
    return scope.submit(describe, entry, Empty.VALUE);
  }

  public OperationHandle<ExportArtifact> export(OperationDescriptor.EntryPoint entry) {
    return scope.submit(export, entry, Empty.VALUE, readiness(entry));
  }

  public static <O> CompletionStage<O> result(OperationHandle<O> handle) {
    return handle
        .completion()
        .thenCompose(
            observed -> {
              if (observed.result().isPresent())
                return CompletableFuture.completedStage(observed.result().orElseThrow());
              var report = observed.report();
              return CompletableFuture.<O>failedFuture(
                      report.valid()
                          ? new PolicyRefusal(
                              "operation.unavailable", "Operation did not produce a result")
                          : new PolicyRefusal(report))
                  .minimalCompletionStage();
            });
  }

  private Description description() {
    var defaults =
        DraftTree.from(definition.type(), definition.defaults(), DraftLimits.CONFIGURATION);
    return new Description(
        definition.id(),
        context.id(),
        fields.fields().stream()
            .map(
                field -> {
                  var node = defaults.at(field.path());
                  if (node instanceof DraftNode.Defaulted defaulted) node = defaulted.value();
                  return new FieldDescription(
                      field.path(),
                      field.selector(),
                      field.type().shape(),
                      node instanceof DraftNode.Scalar scalar
                          ? Optional.of(scalar.raw())
                          : Optional.empty());
                })
            .toList());
  }

  private DraftRevision ref(EditorActor.Inspection<T> observed) {
    return DraftRevision.of(observed.snapshot());
  }

  private Comparison compare(EditorActor.Inspection<T> observed) {
    return compare(observed, Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  private Comparison compare(
      EditorActor.Inspection<T> observed, int maximumChanges, int maximumCharacters) {
    var before = observed.baseline().inspect(definition, observed.snapshot().jsonLimits());
    var after = observed.snapshot().inspect();
    var report = before.report().and(after.report());
    var changes =
        before.candidate().isPresent() && after.candidate().isPresent()
            ? TypedDifferences.between(
                definition.type(),
                before.candidate().orElseThrow().value(),
                after.candidate().orElseThrow().value(),
                maximumChanges,
                maximumCharacters)
            : List.<Change>of();
    return new Comparison(
        ref(observed),
        !observed.snapshot().draft().sameValue(observed.baseline()),
        changes,
        report);
  }

  private ExportArtifact export(EditorActor.Inspection<T> observed) {
    var inspection = observed.snapshot().inspect();
    if (inspection.candidate().isEmpty()) throw new PolicyRefusal(inspection.report());
    var bytes =
        inspection.candidate().orElseThrow().authoredJson().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maximumExportBytes)
      throw new PolicyRefusal("export.size", "Value export exceeds its declared byte limit");
    return new ExportArtifact(ref(observed), definition.schemaVersion(), bytes);
  }

  @Override
  public void close() {
    if (closed) return;
    if (context.isOpen()) context.checkOwnerThread();
    closed = true;
    importEpoch = new Object();
    conflictEpoch = new Object();
    try {
      if (imports != null) imports.close();
    } finally {
      try {
        if (conflicts != null) conflicts.close();
      } finally {
        try {
          scope.close();
        } finally {
          try {
            actor.close();
          } finally {
            if (ownsContext) context.close();
          }
        }
      }
    }
  }
}
