package design.inherent.reform.reference;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.DraftTarget;
import design.inherent.reform.edit.DraftTypes;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.ValueShape;
import java.util.Objects;
import java.util.Optional;

/**
 * One exact draft/source-bound selection. Frontends marshal completion back to its creating thread;
 * this controller owns no executor, renderer, source lifetime or persistence capability.
 */
public final class ReferenceSelection<T, V> implements AutoCloseable {
  public record Search(ReferenceQuery query, OperationHandle<ReferencePage<String>> operation) {}

  public record Lookup<V>(String raw, OperationHandle<ReferenceResolution<V>> operation) {}

  private final Thread thread = Thread.currentThread();
  private final DocumentActions<T> document;
  private final ReferenceActions<String, V> source;
  private final DraftTarget target;
  private final String raw;
  private final ReferenceStamp stamp;
  private Search search;
  private Lookup<V> lookup;
  private ReferencePage<String> page;
  private ReferenceResolution<V> resolution;
  private boolean closed;

  public ReferenceSelection(
      DocumentActions<T> document, FieldPath path, ReferenceActions<String, V> source) {
    this.document = Objects.requireNonNull(document);
    this.source = Objects.requireNonNull(source);
    var view = document.view();
    if (view.availability() != EditorActor.Availability.READY || view.snapshot().isEmpty())
      throw new PolicyRefusal("reference.draft", "The editing context is not ready");
    var snapshot = view.snapshot().orElseThrow();
    var type = DraftTypes.at(document.definition().type(), snapshot.draft(), path);
    if (!(type.shape() instanceof ValueShape.Reference<?> reference)
        || !reference.source().equals(source.source()))
      throw new IllegalArgumentException(
          "Selection requires the field's declared reference source");
    if (!(snapshot.draft().at(path) instanceof DraftNode.Scalar scalar))
      throw new PolicyRefusal("reference.draft", "Repair this field before selecting a reference");
    raw = scalar.raw();
    target = snapshot.target(path);
    stamp = source.requireStamp();
  }

  public DraftTarget target() {
    return target;
  }

  public String raw() {
    return raw;
  }

  public ReferenceStamp stamp() {
    return stamp;
  }

  /** Cached observation only; effect admission checks fresh source READ again. */
  public boolean current() {
    thread();
    var view = document.view();
    return !closed
        && source.isOpen()
        && source.observedStamp().filter(stamp::equals).isPresent()
        && view.availability() == EditorActor.Availability.READY
        && view.snapshot()
            .filter(
                snapshot ->
                    snapshot.session().equals(target.session())
                        && snapshot.generation() == target.generation()
                        && snapshot.version() == target.version().orElseThrow()
                        && snapshot.draft().contains(target.path()))
            .isPresent();
  }

  private void requireCurrent() {
    if (!current())
      throw new PolicyRefusal(
          "reference.selection_stale", "The field or source changed; refresh this selection");
    if (!stamp.equals(source.requireStamp()))
      throw new PolicyRefusal("reference.stale", "Reference source changed; refresh its choices");
  }

  public Search search(EntryPoint entry, ReferenceQuery query) {
    thread();
    var previous = search;
    search = null;
    page = null;
    cancel(previous == null ? null : previous.operation());
    requireCurrent();
    if (query.cursor().filter(cursor -> !cursor.snapshot().equals(stamp.snapshot())).isPresent())
      throw new PolicyRefusal("reference.cursor", "The continuation belongs to another source");
    search = new Search(query, source.search(entry, query));
    return search;
  }

  public Lookup<V> resolve(EntryPoint entry, String key) {
    thread();
    var previous = lookup;
    lookup = null;
    resolution = null;
    cancel(previous == null ? null : previous.operation());
    requireCurrent();
    lookup =
        new Lookup<>(
            key,
            source.resolve(
                entry, new ReferenceActions.Request<>(key, Optional.of(stamp.snapshot()))));
    return lookup;
  }

  /**
   * Only the actual latest completed handle can publish its result; callers cannot substitute one.
   */
  public boolean accept(Search request) {
    thread();
    if (request != search || !current()) return false;
    requireCurrent();
    var result = request.operation().snapshot().result();
    if (result.isEmpty() || !result.orElseThrow().stamp().equals(stamp)) return false;
    page = result.orElseThrow();
    return true;
  }

  public boolean accept(Lookup<V> request) {
    thread();
    if (request != lookup || !current()) return false;
    requireCurrent();
    var result = request.operation().snapshot().result();
    if (result.isEmpty() || !result.orElseThrow().stamp().equals(stamp)) return false;
    resolution = result.orElseThrow();
    return true;
  }

  public Optional<ReferencePage<String>> page() {
    return current() ? Optional.ofNullable(page) : Optional.empty();
  }

  public Optional<ReferenceResolution<V>> resolution() {
    return current() ? Optional.ofNullable(resolution) : Optional.empty();
  }

  public Optional<String> selectedKey() {
    return resolution()
        .filter(value -> value.status() == ReferenceResolution.Status.RESOLVED)
        .map(ignored -> lookup.raw());
  }

  /**
   * Stage only, using the exact raw lookup key; actual document admission and validation remain.
   */
  public OperationHandle<EditorActor.View<T>> choose(EntryPoint entry) {
    requireCurrent();
    var selected =
        selectedKey()
            .orElseThrow(
                () ->
                    new PolicyRefusal("reference.unresolved", "Select a resolved reference first"));
    return document.edit(entry, new DraftEdit(target, new DraftEdit.SetRaw(selected)));
  }

  private static void cancel(OperationHandle<?> operation) {
    if (operation != null && !operation.snapshot().terminal()) operation.requestCancellation();
  }

  private void thread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Reference selection belongs to its creating owner thread");
  }

  /** Cancels only its own lookups; the borrowed source and document remain open. */
  @Override
  public void close() {
    thread();
    if (closed) return;
    closed = true;
    cancel(search == null ? null : search.operation());
    cancel(lookup == null ? null : lookup.operation());
    search = null;
    lookup = null;
    page = null;
    resolution = null;
  }
}
