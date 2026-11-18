package design.inherent.reform.operation;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.importing.ImportFormat;
import design.inherent.reform.importing.ImportInput;
import design.inherent.reform.importing.ImportSource;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueTypes;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Local import review over one actual editor and admitted source; never a save or world effect. */
public final class DocumentImports<T> implements AutoCloseable {
  public record Limits(
      int maximumFormats,
      int maximumPrepared,
      int maximumBytes,
      int maximumChanges,
      int maximumComparisonCharacters,
      OperationScope.Limits operations) {
    public static final Limits STANDARD =
        new Limits(
            16,
            8,
            1_048_576,
            256,
            65_536,
            new OperationScope.Limits(2, 4, 8, new JsonLimits(1024, 4, 8, 256, 32, 32)));

    public Limits {
      Objects.requireNonNull(operations);
      if (maximumFormats < 1
          || maximumPrepared < 1
          || maximumBytes < 1
          || maximumChanges < 1
          || maximumComparisonCharacters < 1
          || operations.maximumDefinitions() < 2)
        throw new IllegalArgumentException("Invalid import admission limits");
    }
  }

  public record Format(
      DocumentId id,
      String label,
      int maximumBytes,
      ImportFormat.InvalidDraftPolicy invalidDraftPolicy) {}

  public record Source(DocumentId id, String label, String sha256, int bytes) {}

  /** Immutable public review metadata. Only this service's actual issued instance can be staged. */
  public static final class Preview {
    private final UUID id = UUID.randomUUID();
    private final DocumentActions.DraftRef target;
    private final Source source;
    private final Format format;
    private final OptionalInt sourceSchemaVersion;
    private final ValidationReport currentValidation, importedValidation;
    private final List<DocumentActions.Change> changes;
    private final boolean comparisonAvailable, authoredChanges, inputChanges, stageable;

    private Preview(
        DocumentActions.DraftRef target,
        Source source,
        Format format,
        OptionalInt sourceSchemaVersion,
        ValidationReport currentValidation,
        ValidationReport importedValidation,
        List<DocumentActions.Change> changes,
        boolean comparisonAvailable,
        boolean authoredChanges,
        boolean inputChanges,
        boolean stageable) {
      this.target = target;
      this.source = source;
      this.format = format;
      this.sourceSchemaVersion = sourceSchemaVersion;
      this.currentValidation = currentValidation;
      this.importedValidation = importedValidation;
      this.changes = List.copyOf(changes);
      this.comparisonAvailable = comparisonAvailable;
      this.authoredChanges = authoredChanges;
      this.inputChanges = inputChanges;
      this.stageable = stageable;
    }

    public UUID id() {
      return id;
    }

    public DocumentActions.DraftRef target() {
      return target;
    }

    public Source source() {
      return source;
    }

    public Format format() {
      return format;
    }

    public OptionalInt sourceSchemaVersion() {
      return sourceSchemaVersion;
    }

    public ValidationReport currentValidation() {
      return currentValidation;
    }

    public ValidationReport importedValidation() {
      return importedValidation;
    }

    public List<DocumentActions.Change> changes() {
      return changes;
    }

    public boolean comparisonAvailable() {
      return comparisonAvailable;
    }

    public boolean authoredChanges() {
      return authoredChanges;
    }

    /** Includes inactive/session input absent from authored or typed comparisons. */
    public boolean inputChanges() {
      return inputChanges;
    }

    public boolean stageable() {
      return stageable;
    }
  }

  private record Parser<T>(Format descriptor, ImportFormat<T> format) {}

  private record Prepared(Preview preview, DraftTree draft) {}

  private record Captured<T>(
      EditSession.Snapshot<T> target, ImportSource.Capture reader, Parser<T> parser) {}

  private final OwnerContext context;
  private final OwnerContext.Access access;
  private final EditorActor<T> actor;
  private final DocumentDefinition<T> definition;
  private final ImportSource source;
  private final DocumentId sourceId;
  private final int sourceMaximumBytes;
  private final Limits limits;
  private final Function<OperationDescriptor.EntryPoint, CompletionStage<?>> readiness;
  private final Map<DocumentId, Parser<T>> parsers;
  private final Map<UUID, Prepared> prepared = new LinkedHashMap<>();
  private final OperationScope scope;
  private final OwnerContext.CloseHook cleanup;
  private final OperationDescriptor<String, Preview> preview;
  private final OperationDescriptor<String, EditorActor.View<T>> stage;
  private volatile boolean closed;

  DocumentImports(
      OwnerContext context,
      OwnerContext.Access access,
      EditorActor<T> actor,
      ImportSource source,
      List<? extends ImportFormat<T>> formats,
      Limits limits,
      Function<OperationDescriptor.EntryPoint, CompletionStage<?>> readiness) {
    this.context = Objects.requireNonNull(context);
    context.requireOwner();
    this.access = Objects.requireNonNull(access);
    this.actor = Objects.requireNonNull(actor);
    this.source = Objects.requireNonNull(source);
    this.limits = Objects.requireNonNull(limits);
    this.readiness = Objects.requireNonNull(readiness);
    access.require(OwnerContext.Right.READ);
    if (!actor.context().equals(context.id()) || !access.context().equals(context.id()))
      throw new IllegalArgumentException("Import requires its actual editor context/access");
    definition = actor.definition();
    sourceId = Objects.requireNonNull(source.id());
    sourceMaximumBytes = source.maximumBytes();
    checkLabel(source.label());
    if (sourceMaximumBytes < 1
        || sourceMaximumBytes > limits.maximumBytes()
        || formats.isEmpty()
        || formats.size() > limits.maximumFormats())
      throw new IllegalArgumentException("Source/formats exceed import admission");
    var registered = new LinkedHashMap<DocumentId, Parser<T>>();
    for (var format : formats) {
      if (format.definition() != definition
          || format.maximumBytes() < 1
          || format.maximumBytes() > limits.maximumBytes())
        throw new IllegalArgumentException(
            "Import format requires the actual target definition and bound");
      checkLabel(format.label());
      var descriptor =
          new Format(
              Objects.requireNonNull(format.id()),
              format.label(),
              format.maximumBytes(),
              Objects.requireNonNull(format.invalidDraftPolicy()));
      if (registered.putIfAbsent(descriptor.id(), new Parser<>(descriptor, format)) != null)
        throw new IllegalArgumentException("Duplicate import format identity");
    }
    parsers = java.util.Collections.unmodifiableMap(registered);
    scope = new OperationScope(context, access, limits.operations());
    try {
      preview = descriptor("preview", OperationDescriptor.Effect.INSPECT);
      stage = descriptor("stage", OperationDescriptor.Effect.EDIT_DRAFT);
      scope.register(preview, this::prepare);
      scope.register(stage, this::stagePrepared);
      scope.freeze();
      cleanup = context.onClose(this::close);
    } catch (RuntimeException | Error failure) {
      scope.close();
      throw failure;
    }
  }

  private static void checkLabel(String label) {
    if (Objects.requireNonNull(label).isBlank() || label.length() > 256)
      throw new IllegalArgumentException("Import labels must contain one to 256 characters");
  }

  private <O> OperationDescriptor<String, O> descriptor(
      String operation, OperationDescriptor.Effect effect) {
    return new OperationDescriptor<>(
        DocumentId.parse("reform:import/" + operation),
        ValueTypes.text(255),
        OwnerContext.Right.READ,
        Set.of(effect),
        Set.of(
            OperationDescriptor.EntryPoint.GUI,
            OperationDescriptor.EntryPoint.COMMAND,
            OperationDescriptor.EntryPoint.INTERNAL),
        Optional.of(definition.id()));
  }

  public List<Format> formats() {
    context.requireOwner();
    return parsers.values().stream().map(Parser::descriptor).toList();
  }

  public OperationHandle<Preview> preview(OperationDescriptor.EntryPoint entry, DocumentId format) {
    requireSource();
    return scope.submit(
        preview, entry, Objects.requireNonNull(format).toString(), readiness.apply(entry));
  }

  public OperationHandle<EditorActor.View<T>> stage(
      OperationDescriptor.EntryPoint entry, Preview selected) {
    requireSource();
    requirePrepared(selected);
    return scope.submit(stage, entry, selected.id().toString());
  }

  /** Explicit read-only draft inspection, not repeated parsing/validation during rendering. */
  public DraftTree draft(Preview selected) {
    requireSource();
    return requirePrepared(selected).draft();
  }

  /** Resolve only this review's retained ticket; its UUID is never cross-editor authority. */
  public Optional<Preview> review(UUID id) {
    requireSource();
    var retained = prepared.get(Objects.requireNonNull(id));
    return retained == null ? Optional.empty() : Optional.of(retained.preview());
  }

  public List<OperationSnapshot<?>> operations() {
    return scope.snapshots();
  }

  /** Cached lifetime only. Every actual lookup or operation rechecks document/source READ. */
  public boolean isOpen() {
    return !closed && context.isOpen() && access.isOpen();
  }

  /** Actual borrowed capability, for adapters checking provenance; no capture or IO occurs here. */
  public ImportSource admittedSource() {
    requireSource();
    return source;
  }

  public Optional<OperationHandle<?>> operation(UUID id) {
    requireSource();
    var found = scope.find(Objects.requireNonNull(id));
    requireSource();
    return found;
  }

  private CompletionStage<Preview> prepare(OperationScope.Execution execution, String requested) {
    execution.checkpoint();
    requireSource();
    var parser = parsers.get(DocumentId.parse(requested));
    if (parser == null)
      throw new PolicyRefusal("import.format", "Select a registered import format");
    return actor
        .inspect()
        .thenCompose(
            observed ->
                context.onOwner(
                    () -> {
                      execution.checkpoint();
                      requireSource();
                      final ImportSource.Capture reader;
                      try {
                        reader = Objects.requireNonNull(source.capture());
                      } catch (IOException unavailable) {
                        throw new PolicyRefusal(
                            "import.read",
                            "The admitted import source could not be captured",
                            false);
                      }
                      requireSource();
                      requireTarget(ref(observed.snapshot()));
                      return new Captured<>(observed.snapshot(), reader, parser);
                    }))
        .thenCompose(captured -> context.onWorker(() -> decode(execution, captured)))
        .thenCompose(
            result ->
                context.onOwner(
                    () -> {
                      execution.checkpoint();
                      requireSource();
                      requireTarget(result.preview().target());
                      while (prepared.size() >= limits.maximumPrepared())
                        prepared.remove(prepared.keySet().iterator().next());
                      prepared.put(result.preview().id(), result);
                      return result.preview();
                    }));
  }

  private Prepared decode(OperationScope.Execution execution, Captured<T> captured) {
    execution.checkpoint();
    access.requireCurrent();
    if (closed) throw expired();
    execution.progress(
        new OperationSnapshot.Progress(
            "import.read", 0, java.util.OptionalLong.empty(), "Reading admitted source"));
    final ImportInput input;
    final ImportFormat.Decoded decoded;
    var parser = captured.parser();
    try {
      input = Objects.requireNonNull(captured.reader().read());
      if (input.size() > sourceMaximumBytes || input.size() > parser.descriptor().maximumBytes())
        throw new PolicyRefusal("import.bytes", "Import source exceeds its admitted byte limit");
      execution.checkpoint();
      decoded = Objects.requireNonNull(parser.format().read(input));
    } catch (JsonInputException invalid) {
      throw new PolicyRefusal(
          new ValidationReport(
              List.of(
                  new Issue(
                      invalid.code(),
                      Issue.Severity.ERROR,
                      FieldPath.ROOT,
                      List.of(),
                      Map.of("sourceLocation", invalid.location()),
                      invalid.getMessage())),
              true));
    } catch (IOException unavailable) {
      throw new PolicyRefusal("import.read", "The admitted import source could not be read", false);
    }
    execution.checkpoint();
    final DraftTree imported;
    try {
      imported = new DraftTree(decoded.draft().root(), captured.target().draft().limits());
    } catch (IllegalArgumentException outsidePolicy) {
      throw new PolicyRefusal(
          "import.draft_limits", "Import exceeds the actual editor's draft bounds");
    }
    execution.progress(
        new OperationSnapshot.Progress(
            "import.compare", 0, java.util.OptionalLong.empty(), "Validating and comparing draft"));
    var current = captured.target().inspect();
    var after = imported.inspect(definition, captured.target().jsonLimits());
    boolean comparable = current.candidate().isPresent() && after.candidate().isPresent();
    var changes =
        comparable
            ? TypedDifferences.between(
                definition.type(),
                current.candidate().orElseThrow().value(),
                after.candidate().orElseThrow().value(),
                limits.maximumChanges(),
                limits.maximumComparisonCharacters())
            : List.<DocumentActions.Change>of();
    boolean canStage =
        after.report().complete()
            && (after.report().valid()
                || parser.descriptor().invalidDraftPolicy()
                    == ImportFormat.InvalidDraftPolicy.ALLOW_REPAIRABLE);
    var result =
        new Preview(
            ref(captured.target()),
            new Source(sourceId, input.label(), input.sha256(), input.size()),
            parser.descriptor(),
            decoded.sourceSchemaVersion(),
            current.report(),
            after.report(),
            changes,
            comparable,
            !imported.sameValue(captured.target().draft()),
            !DraftNode.sameInput(imported.root(), captured.target().draft().root()),
            canStage);
    return new Prepared(result, imported);
  }

  private CompletionStage<EditorActor.View<T>> stagePrepared(
      OperationScope.Execution execution, String id) {
    execution.checkpoint();
    requireSource();
    final UUID key;
    try {
      key = UUID.fromString(id);
    } catch (IllegalArgumentException invalid) {
      throw expired();
    }
    var selected = prepared.get(key);
    if (selected == null) throw expired();
    var reviewed = selected.preview();
    if (!reviewed.stageable()) throw new PolicyRefusal(reviewed.importedValidation());
    requireTarget(reviewed.target());
    execution.checkpoint();
    prepared.remove(key);
    var target =
        new DraftTarget(
            reviewed.target().session(),
            reviewed.target().generation(),
            Optional.of(reviewed.target().version()),
            FieldPath.ROOT);
    return actor.stage(target, selected.draft(), "Import profile");
  }

  private DocumentActions.DraftRef ref(EditSession.Snapshot<T> snapshot) {
    return new DocumentActions.DraftRef(
        definition.id(),
        snapshot.context(),
        snapshot.session(),
        snapshot.generation(),
        snapshot.version(),
        snapshot.sourceRevision());
  }

  private void requireTarget(DocumentActions.DraftRef target) {
    var actual = actor.view();
    if (actual.availability() != EditorActor.Availability.READY
        || actual.snapshot().filter(value -> target.equals(ref(value))).isEmpty())
      throw new PolicyRefusal(
          "import.stale_target", "The reviewed draft changed; compare the import again");
  }

  private Prepared requirePrepared(Preview selected) {
    if (selected == null) throw expired();
    var found = prepared.get(selected.id());
    if (found == null || found.preview() != selected) throw expired();
    return found;
  }

  private void requireSource() {
    context.requireOwner();
    access.require(OwnerContext.Right.READ);
    if (closed) throw expired();
    source.requireCurrent();
    context.requireOwner();
    access.require(OwnerContext.Right.READ);
    if (closed) throw expired();
  }

  private static PolicyRefusal expired() {
    return new PolicyRefusal("import.expired", "Import review closed or expired; prepare it again");
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
