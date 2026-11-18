package design.inherent.reform.edit;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit manual choices against an observed revision; collections are indivisible here. */
public final class ConflictReview {
  public enum Basis {
    OBSERVED_BASELINE,
    SUBMITTED_DRAFT
  }

  public enum Choice {
    BASELINE,
    OBSERVED,
    DRAFT
  }

  public record Entry(FieldPath path, DraftNode baseline, DraftNode observed, DraftNode draft) {}

  private final UUID session;
  private final UUID context;
  private final long generation;
  private final long draftVersion;
  private final String observedRevision;
  private final Basis basis;
  private final String baselineRevision;
  private final DraftTree observed;
  private final List<Entry> entries;

  ConflictReview(
      UUID session,
      UUID context,
      long generation,
      long draftVersion,
      String observedRevision,
      Basis basis,
      String baselineRevision,
      DraftTree baseline,
      DraftTree observed,
      DraftTree draft,
      ValueType<?> type) {
    this.session = session;
    this.context = context;
    this.generation = generation;
    this.draftVersion = draftVersion;
    this.observedRevision = observedRevision;
    this.basis = java.util.Objects.requireNonNull(basis);
    this.baselineRevision = java.util.Objects.requireNonNull(baselineRevision);
    this.observed = observed;
    var found = new ArrayList<Entry>();
    collect(type, FieldPath.ROOT, baseline.root(), observed.root(), draft.root(), found);
    entries = List.copyOf(found);
  }

  public UUID context() {
    return context;
  }

  public String observedRevision() {
    return observedRevision;
  }

  public Basis basis() {
    return basis;
  }

  /** Owner revision underlying the baseline or submitted draft; not a live source observation. */
  public String baselineRevision() {
    return baselineRevision;
  }

  public List<Entry> entries() {
    return entries;
  }

  /** Cached identity check only; this does not grant source access or resolution authority. */
  public boolean matches(EditSession.Snapshot<?> snapshot) {
    return matches(
        snapshot.session(), snapshot.context(), snapshot.generation(), snapshot.version());
  }

  DraftTree observed() {
    return observed;
  }

  boolean matches(UUID session, UUID context, long generation, long draftVersion) {
    return this.session.equals(session)
        && this.context.equals(context)
        && this.generation == generation
        && this.draftVersion == draftVersion;
  }

  DraftTree resolve(DraftTree current, Map<FieldPath, Choice> choices) {
    if (!choices
        .keySet()
        .equals(entries.stream().map(Entry::path).collect(java.util.stream.Collectors.toSet()))) {
      throw new IllegalArgumentException("Every review entry needs an explicit choice");
    }
    var replacements = new java.util.LinkedHashMap<FieldPath, DraftNode>();
    for (var entry : entries) {
      var choice = java.util.Objects.requireNonNull(choices.get(entry.path()));
      DraftNode value =
          switch (choice) {
            case BASELINE -> entry.baseline();
            case OBSERVED -> entry.observed();
            case DRAFT -> entry.draft();
          };
      replacements.put(entry.path(), value);
    }
    return current.replaceAll(replacements);
  }

  private static void collect(
      ValueType<?> type,
      FieldPath path,
      DraftNode baseline,
      DraftNode observed,
      DraftNode draft,
      List<Entry> entries) {
    if (DraftNode.sameValue(baseline, observed) && DraftNode.sameValue(observed, draft)) return;
    if (type.shape() instanceof ValueShape.ObjectValue object
        && baseline instanceof DraftNode.ObjectValue b
        && observed instanceof DraftNode.ObjectValue o
        && draft instanceof DraftNode.ObjectValue d) {
      var names =
          object.fields().stream()
              .map(field -> field.id())
              .collect(java.util.stream.Collectors.toSet());
      if (b.fields().keySet().equals(names)
          && o.fields().keySet().equals(names)
          && d.fields().keySet().equals(names)) {
        for (var field : object.fields())
          collect(
              field.type(),
              path.field(field.id()),
              b.fields().get(field.id()),
              o.fields().get(field.id()),
              d.fields().get(field.id()),
              entries);
        return;
      }
    }
    entries.add(new Entry(path, baseline, observed, draft));
  }
}
