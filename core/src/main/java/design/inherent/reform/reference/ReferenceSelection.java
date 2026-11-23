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
  private final ReferenceBrowser<V> browser;
  private final DraftTarget target;
  private final String raw;
  private Search search;
  private Lookup<V> lookup;
  private ReferenceBrowser.Search searchWork;
  private ReferenceBrowser.Lookup<V> lookupWork;
  private boolean closed;

  public ReferenceSelection(
      DocumentActions<T> document, FieldPath path, ReferenceActions<String, V> source) {
    this.document = Objects.requireNonNull(document);
    Objects.requireNonNull(source);
    var field = field(document, path, source.source());
    raw = field.raw();
    target = field.target();
    browser = new ReferenceBrowser<>(source);
  }

  private record Field(DraftTarget target, String raw) {}

  private static <T> Field field(
      DocumentActions<T> document,
      FieldPath path,
      design.inherent.reform.definition.DocumentId source) {
    var view = document.view();
    if (view.availability() != EditorActor.Availability.READY || view.snapshot().isEmpty())
      throw new PolicyRefusal("reference.draft", "The editing context is not ready");
    var snapshot = view.snapshot().orElseThrow();
    var type = DraftTypes.at(document.definition().type(), snapshot.draft(), path);
    if (!(type.shape() instanceof ValueShape.Reference<?> reference)
        || !reference.source().equals(source))
      throw new IllegalArgumentException(
          "Selection requires the field's declared reference source");
    if (!(snapshot.draft().at(path) instanceof DraftNode.Scalar scalar))
      throw new PolicyRefusal("reference.draft", "Repair this field before selecting a reference");
    return new Field(snapshot.target(path), scalar.raw());
  }

  /**
   * Explicitly stage a browser's inspected choice into one current field. Browsing does not bind or
   * mutate that field; activation captures its exact current target and rechecks source READ. The
   * borrowed browser stays open, so another independent field can use the same catalogue.
   */
  public static <T, V> OperationHandle<EditorActor.View<T>> chooseFromBrowser(
      EntryPoint entry, DocumentActions<T> document, FieldPath path, ReferenceBrowser<V> browser) {
    Objects.requireNonNull(entry);
    Objects.requireNonNull(document);
    Objects.requireNonNull(path);
    Objects.requireNonNull(browser);
    if (!browser.current()) browser.requireCurrent();
    String selected =
        browser
            .selectedKey()
            .orElseThrow(
                () ->
                    new PolicyRefusal("reference.unresolved", "Select a resolved reference first"));
    var target = field(document, path, browser.stamp().source()).target();
    browser.requireCurrent();
    if (browser.selectedKey().filter(selected::equals).isEmpty())
      throw new PolicyRefusal(
          "reference.selection_stale", "The inspected choice changed; inspect it again");
    return stage(entry, document, target, selected);
  }

  private static <T> OperationHandle<EditorActor.View<T>> stage(
      EntryPoint entry, DocumentActions<T> document, DraftTarget target, String selected) {
    return document.edit(entry, new DraftEdit(target, new DraftEdit.SetRaw(selected)));
  }

  public DraftTarget target() {
    return target;
  }

  public String raw() {
    return raw;
  }

  public ReferenceStamp stamp() {
    return browser.stamp();
  }

  /** Cached observation only; effect admission checks fresh source READ again. */
  public boolean current() {
    thread();
    var view = document.view();
    return !closed
        && browser.current()
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
  }

  public Search search(EntryPoint entry, ReferenceQuery query) {
    thread();
    search = null;
    searchWork = null;
    if (!current()) {
      browser.clearSearch();
      requireCurrent();
    }
    var work = browser.search(entry, query);
    searchWork = work;
    search = new Search(query, searchWork.operation());
    return search;
  }

  public Lookup<V> resolve(EntryPoint entry, String key) {
    thread();
    lookup = null;
    lookupWork = null;
    if (!current()) {
      browser.clearLookup();
      requireCurrent();
    }
    var work = browser.resolve(entry, key);
    lookupWork = work;
    lookup = new Lookup<>(key, lookupWork.operation());
    return lookup;
  }

  /**
   * Only the actual latest completed handle can publish its result; callers cannot substitute one.
   */
  public boolean accept(Search request) {
    thread();
    if (request == null || request != search || !current()) return false;
    return browser.accept(searchWork);
  }

  public boolean accept(Lookup<V> request) {
    thread();
    if (request == null || request != lookup || !current()) return false;
    return browser.accept(lookupWork);
  }

  public Optional<ReferencePage<String>> page() {
    return current() ? browser.page() : Optional.empty();
  }

  public Optional<ReferenceResolution<V>> resolution() {
    return current() ? browser.resolution() : Optional.empty();
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
    browser.requireCurrent();
    var selected =
        selectedKey()
            .orElseThrow(
                () ->
                    new PolicyRefusal("reference.unresolved", "Select a resolved reference first"));
    return stage(entry, document, target, selected);
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
    browser.close();
    search = null;
    lookup = null;
    searchWork = null;
    lookupWork = null;
  }
}
