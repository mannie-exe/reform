package design.inherent.reform.storage;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.DocumentRead;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;
import java.util.Optional;

/** Typed worker-side storage. Owners still supply authorization, execution and publication. */
public abstract class TextStore<T> {
  /**
   * A format error with bounded, owner-visible diagnostics rather than arbitrary exception text.
   */
  public static final class InvalidData extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final transient ValidationReport report;

    public InvalidData(ValidationReport report) {
      super("Source text is not admitted by its declared format");
      this.report = Objects.requireNonNull(report);
    }

    public ValidationReport report() {
      return report;
    }

    @Serial
    private void writeObject(ObjectOutputStream stream) throws NotSerializableException {
      throw new NotSerializableException(
          "Use explicit bounded diagnostics, not Java serialization");
    }

    @Serial
    private void readObject(ObjectInputStream stream) throws NotSerializableException {
      throw new NotSerializableException("Local format failures cannot be deserialized");
    }
  }

  public enum State {
    MISSING,
    INVALID,
    UNAVAILABLE,
    READY
  }

  public static final class Read<T> implements DocumentRead<T> {
    private final Object store;
    private final State state;
    private final OwnedFile.Snapshot source;
    private final Optional<DraftTree> draft;
    private final Optional<Candidate<T>> candidate;
    private final ValidationReport report;

    private Read(
        Object store,
        State state,
        OwnedFile.Snapshot source,
        Optional<DraftTree> draft,
        Optional<Candidate<T>> candidate,
        ValidationReport report) {
      this.store = store;
      this.state = state;
      this.source = source;
      this.draft = draft;
      this.candidate = candidate;
      this.report = report;
    }

    public State state() {
      return state;
    }

    @Override
    public String revision() {
      return source.revision();
    }

    public OwnedFile.Snapshot source() {
      return source;
    }

    public Optional<DraftTree> draft() {
      return draft;
    }

    public Optional<Candidate<T>> candidate() {
      return candidate;
    }

    public ValidationReport report() {
      return report;
    }
  }

  public record Write<T>(OwnedFile.Result persistence, Optional<Read<T>> observation) {}

  private final Object identity = new Object();
  protected final DocumentDefinition<T> definition;
  private final OwnedFile file;
  private final SourceInfo provenance;
  protected final JsonLimits jsonLimits;
  protected final DraftLimits draftLimits;

  protected TextStore(
      DocumentDefinition<T> definition,
      OwnedFile file,
      JsonLimits jsonLimits,
      DraftLimits draftLimits,
      String kind,
      String description) {
    this.definition = Objects.requireNonNull(definition);
    this.file = Objects.requireNonNull(file);
    this.provenance =
        new SourceInfo(kind, description, Optional.of(file.binding().path().toString()));
    this.jsonLimits = Objects.requireNonNull(jsonLimits);
    this.draftLimits = Objects.requireNonNull(draftLimits);
  }

  public Read<T> read() throws IOException {
    return inspect(file.read());
  }

  public DocumentDefinition<T> definition() {
    return definition;
  }

  public StorageBinding binding() {
    return file.binding();
  }

  /** Immutable value-projection limits, separate from this source's physical file format. */
  public JsonLimits valueLimits() {
    return jsonLimits;
  }

  public DraftLimits draftLimits() {
    return draftLimits;
  }

  /** Pure bounded preparation for this store's exact definition and serialization limits. */
  public DraftTree.Inspection<T> inspectDraft(DraftTree draft) {
    return new DraftTree(draft.root(), draftLimits).inspect(definition, jsonLimits);
  }

  /** Explicit lifecycle initialization, never a side effect of opening or inspecting a form. */
  public Write<T> initializeDefaultsIfMissing() throws IOException {
    return initializeDefaultsIfMissing(() -> {});
  }

  public Write<T> initializeDefaultsIfMissing(Runnable currentLifetime) throws IOException {
    Objects.requireNonNull(currentLifetime);
    var current = read();
    if (current.source().present())
      return new Write<>(
          new OwnedFile.Result(
              OwnedFile.Persistence.UNCHANGED, Optional.of(current.source()), Optional.empty()),
          Optional.of(current));
    var candidate =
        DraftTree.from(definition.type(), definition.defaults(), draftLimits)
            .inspect(definition, jsonLimits)
            .candidate()
            .orElseThrow(
                () -> new IllegalStateException("Defaults exceed storage admission or validation"));
    return write(current, candidate, currentLifetime);
  }

  /** A complete candidate is necessary but does not authorize this persistence operation. */
  public Write<T> write(Read<T> baseline, Candidate<T> candidate) {
    return write(baseline, candidate, () -> {});
  }

  public Write<T> write(Read<T> baseline, Candidate<T> candidate, Runnable currentLifetime) {
    Objects.requireNonNull(currentLifetime);
    if (baseline.store != identity)
      throw new IllegalArgumentException("Observation belongs to another store");
    if (candidate.definition() != definition)
      throw new IllegalArgumentException("Candidate belongs to another document definition");
    var checked = inspectDraft(candidate.draft());
    if (checked.candidate().isEmpty())
      return refused("Complete candidate validation did not finish successfully");
    if (baseline.state() == State.READY
        && baseline.draft().orElseThrow().sameValue(candidate.draft())) {
      var result =
          file.write(baseline.source(), baseline.source().text().orElseThrow(), currentLifetime);
      return new Write<>(result, result.observed().map(this::inspect));
    }
    final String serialized;
    try {
      serialized = encode(baseline, checked.candidate().orElseThrow());
      validateEncoded(serialized);
    } catch (IOException | RuntimeException invalid) {
      return refused("Serialized candidate exceeds storage admission");
    }
    var result = file.write(baseline.source(), serialized, currentLifetime);
    return new Write<>(result, result.observed().map(this::inspect));
  }

  /** Already bound metadata; no file IO or authority is supplied by its display path. */
  public final SourceInfo provenance() {
    return provenance;
  }

  /** Pure bounded format operations. Native/domain IO and effects belong in their actual owner. */
  protected abstract DraftTree decode(String text);

  protected abstract String encode(Read<T> baseline, Candidate<T> candidate);

  protected abstract void validateEncoded(String text) throws IOException;

  private Write<T> refused(String message) {
    return new Write<>(
        new OwnedFile.Result(
            OwnedFile.Persistence.REFUSED, Optional.empty(), Optional.of(new IOException(message))),
        Optional.empty());
  }

  private Read<T> inspect(OwnedFile.Snapshot source) {
    if (!source.present())
      return problem(State.MISSING, source, "storage.missing", "No saved document exists", false);
    try {
      var draft = decode(source.text().orElseThrow());
      var inspected = draft.inspect(definition, jsonLimits);
      return new Read<>(
          identity,
          inspected.candidate().isPresent()
              ? State.READY
              : inspected.report().complete() ? State.INVALID : State.UNAVAILABLE,
          source,
          Optional.of(draft),
          inspected.candidate(),
          inspected.report());
    } catch (InvalidData invalid) {
      return new Read<>(
          identity,
          invalid.report().complete() ? State.INVALID : State.UNAVAILABLE,
          source,
          Optional.empty(),
          Optional.empty(),
          invalid.report());
    } catch (RuntimeException unavailable) {
      return problem(
          State.UNAVAILABLE,
          source,
          "storage.validation",
          "Saved data could not be inspected",
          false);
    }
  }

  private Read<T> problem(
      State state, OwnedFile.Snapshot source, String code, String message, boolean complete) {
    return new Read<>(
        identity,
        state,
        source,
        Optional.empty(),
        Optional.empty(),
        new ValidationReport(
            java.util.List.of(Issue.error(code, FieldPath.ROOT, message)), complete));
  }
}
