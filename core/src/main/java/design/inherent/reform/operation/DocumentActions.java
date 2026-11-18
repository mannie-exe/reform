package design.inherent.reform.operation;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.DefaultedType;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

  public record DraftRef(
      DocumentId document, UUID context, long generation, long version, String sourceRevision) {}

  public record FieldDescription(
      FieldPath path, String selector, ValueShape shape, Optional<String> defaultText) {}

  public record Description(DocumentId document, UUID context, List<FieldDescription> fields) {
    public Description {
      fields = List.copyOf(fields);
    }
  }

  public record Change(FieldPath path, String beforeJson, String afterJson) {}

  public record Comparison(
      DraftRef draft, boolean authoredChanges, List<Change> changes, ValidationReport report) {
    public Comparison {
      changes = List.copyOf(changes);
    }
  }

  /** In-memory value JSON. This is not the owner's file format, a file write or publication. */
  public static final class ExportArtifact {
    private final DraftRef draft;
    private final int schemaVersion;
    private final byte[] bytes;
    private final String sha256;

    private ExportArtifact(DraftRef draft, int schemaVersion, byte[] bytes) {
      this.draft = draft;
      this.schemaVersion = schemaVersion;
      this.bytes = bytes.clone();
      try {
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    public DraftRef draft() {
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
  private final EditorActor<T> actor;
  private final DocumentDefinition<T> definition;
  private final OperationScope scope;
  private final FieldIndex fields;
  private final int maximumExportBytes;
  private boolean ownsContext;
  private OperationHandle<EditorActor.View<T>> initialLoad;
  private final OperationDescriptor<Empty, EditorActor.View<T>> load,
      undo,
      redo,
      revert,
      defaults,
      inspect;
  private final OperationDescriptor<FieldInput, EditorActor.View<T>> edit;
  private final OperationDescriptor<Empty, EditorActor.Commit<T>> save, apply, admit;
  private final OperationDescriptor<Empty, EditorActor.Validation> validate;
  private final OperationDescriptor<Empty, EditorActor.Inspection<T>> explain;
  private final OperationDescriptor<Empty, Comparison> compare;
  private final OperationDescriptor<Empty, Description> describe;
  private final OperationDescriptor<Empty, ExportArtifact> export;

  public DocumentActions(
      OwnerContext context,
      OwnerContext.Access access,
      EditorActor<T> actor,
      OperationScope.Limits limits,
      int maximumExportBytes) {
    context.requireOwner();
    if (!context.id().equals(actor.context())
        || maximumExportBytes < 1
        || limits.maximumDefinitions() < 15)
      throw new IllegalArgumentException(
          "Actions require their actual editing context and export bound");
    this.context = context;
    this.actor = actor;
    this.definition = actor.definition();
    this.maximumExportBytes = maximumExportBytes;
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

  public DocumentSession.CommitKind commitKind() {
    return actor.commitKind();
  }

  public OperationHandle<EditorActor.Commit<T>> commit(OperationDescriptor.EntryPoint entry) {
    return commitKind() == DocumentSession.CommitKind.PERSIST ? save(entry) : admit(entry);
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
    if (actor.view().availability() == EditorActor.Availability.READY)
      return CompletableFuture.completedStage(null);
    if (actor.view().availability() == EditorActor.Availability.LOADING)
      return result(ensureLoaded(entry))
          .thenApply(
              view -> {
                if (view.availability() != EditorActor.Availability.READY)
                  throw new PolicyRefusal(
                      view.validation().valid()
                          ? new ValidationReport(
                              List.of(
                                  design.inherent.reform.validation.Issue.error(
                                      "editor.unavailable",
                                      FieldPath.ROOT,
                                      "No editing baseline is ready")),
                              false)
                          : view.validation());
                return null;
              });
    return CompletableFuture.failedFuture(
        new PolicyRefusal("editor.unavailable", "No editing baseline is ready"));
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

  private DraftRef ref(EditorActor.Inspection<T> observed) {
    var snapshot = observed.snapshot();
    return new DraftRef(
        definition.id(),
        snapshot.context(),
        snapshot.generation(),
        snapshot.version(),
        snapshot.sourceRevision());
  }

  private Comparison compare(EditorActor.Inspection<T> observed) {
    var before = observed.baseline().inspect(definition, observed.snapshot().jsonLimits());
    var after = observed.snapshot().inspect();
    var report = before.report().and(after.report());
    var changes = new ArrayList<Change>();
    if (before.candidate().isPresent() && after.candidate().isPresent()) {
      var first =
          definition
              .type()
              .codec()
              .encodeStart(JsonOps.INSTANCE, before.candidate().orElseThrow().value())
              .getOrThrow();
      var second =
          definition
              .type()
              .codec()
              .encodeStart(JsonOps.INSTANCE, after.candidate().orElseThrow().value())
              .getOrThrow();
      differences(definition.type(), FieldPath.ROOT, first, second, changes);
    }
    return new Comparison(
        ref(observed),
        !observed.snapshot().draft().sameValue(observed.baseline()),
        changes,
        report);
  }

  private static <V> boolean equivalent(ValueType<V> type, JsonElement first, JsonElement second) {
    return type.equivalent(comparisonValue(type, first), comparisonValue(type, second));
  }

  private static <V> V comparisonValue(ValueType<V> type, JsonElement encoded) {
    if (encoded == null && type instanceof DefaultedType<V> defaults) return defaults.fallback();
    if (encoded == null) throw new IllegalStateException("A required comparison field is missing");
    var value = type.decode(JsonOps.INSTANCE, encoded, FieldPath.ROOT);
    if (value.value().isEmpty() || !value.report().valid())
      throw new IllegalStateException("Previously validated comparison could not decode");
    return value.value().orElseThrow();
  }

  private static <V> String comparisonJson(ValueType<V> type, JsonElement encoded) {
    return encoded == null
        ? type.codec()
            .encodeStart(JsonOps.INSTANCE, comparisonValue(type, null))
            .getOrThrow()
            .toString()
        : encoded.toString();
  }

  private static void differences(
      ValueType<?> type,
      FieldPath path,
      JsonElement first,
      JsonElement second,
      List<Change> changes) {
    if (equivalent(type, first, second)) return;
    if (type.shape() instanceof ValueShape.ObjectValue object) {
      for (var field : object.fields())
        differences(
            field.type(),
            path.field(field.id()),
            first.getAsJsonObject().get(field.id()),
            second.getAsJsonObject().get(field.id()),
            changes);
    } else changes.add(new Change(path, comparisonJson(type, first), comparisonJson(type, second)));
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
