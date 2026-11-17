package design.inherent.reform.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.DocumentRead;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonInputException;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.serialization.SourcePath;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Typed worker-side storage. Owners still supply authorization, execution and publication. */
public final class JsonStore<T> {
  public enum Format {
    NEW_ENVELOPE,
    EXISTING_BARE
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

  private static final Gson JSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private final Object identity = new Object();
  private final DocumentDefinition<T> definition;
  private final OwnedFile file;
  private final Format format;
  private final JsonLimits jsonLimits;
  private final DraftLimits draftLimits;

  public JsonStore(
      DocumentDefinition<T> definition,
      OwnedFile file,
      Format format,
      JsonLimits jsonLimits,
      DraftLimits draftLimits) {
    this.definition = Objects.requireNonNull(definition);
    this.file = Objects.requireNonNull(file);
    this.format = Objects.requireNonNull(format);
    this.jsonLimits = Objects.requireNonNull(jsonLimits);
    this.draftLimits = Objects.requireNonNull(draftLimits);
  }

  public Read<T> read() throws IOException {
    return inspect(file.read());
  }

  public DocumentDefinition<T> definition() {
    return definition;
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
      var values = JsonParser.parseString(checked.candidate().orElseThrow().authoredJson());
      if (format == Format.NEW_ENVELOPE) {
        var envelope = new JsonObject();
        envelope.addProperty("schemaVersion", definition.schemaVersion());
        envelope.add("values", values);
        serialized = JSON.toJson(envelope) + "\n";
      } else serialized = JSON.toJson(values) + "\n";
      JsonInput.read(serialized, jsonLimits);
    } catch (JsonInputException | RuntimeException invalid) {
      return refused("Serialized candidate exceeds storage admission");
    }
    var result = file.write(baseline.source(), serialized, currentLifetime);
    return new Write<>(result, result.observed().map(this::inspect));
  }

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
      var parsed = JsonInput.read(source.text().orElseThrow(), jsonLimits);
      SourcePath selection = SourcePath.ROOT;
      if (format == Format.NEW_ENVELOPE) {
        var root = parsed.copyTree();
        if (!root.isJsonObject()
            || !root.getAsJsonObject().keySet().equals(Set.of("schemaVersion", "values")))
          return problem(
              State.INVALID,
              source,
              "storage.envelope",
              "Expected schemaVersion and values only",
              true);
        var version =
            ExactCodecs.INT.parse(JsonOps.INSTANCE, root.getAsJsonObject().get("schemaVersion"));
        if (version.error().isPresent()
            || version.result().orElseThrow() != definition.schemaVersion())
          return problem(
              State.INVALID, source, "storage.schema", "Unsupported document schema version", true);
        selection = selection.property("values");
      }
      var draft = DraftTree.fromParsed(definition.type(), parsed, selection, draftLimits);
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
    } catch (JsonInputException invalid) {
      return problem(State.INVALID, source, invalid.code(), invalid.getMessage(), true);
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
