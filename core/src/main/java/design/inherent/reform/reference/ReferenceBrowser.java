package design.inherent.reform.reference;

import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only browsing of one admitted source snapshot. This is neither an authoring target nor a
 * write capability; editors separately bind their actual draft and owner operations.
 */
public final class ReferenceBrowser<V> implements AutoCloseable {
  public record Search(ReferenceQuery query, OperationHandle<ReferencePage<String>> operation) {}

  public record Lookup<V>(String raw, OperationHandle<ReferenceResolution<V>> operation) {}

  private final Thread thread = Thread.currentThread();
  private final ReferenceActions<String, V> source;
  private final ReferenceStamp stamp;
  private Search search;
  private Lookup<V> lookup;
  private ReferencePage<String> page;
  private ReferenceResolution<V> resolution;
  private Object searchEpoch;
  private Object lookupEpoch;
  private boolean closed;

  public ReferenceBrowser(ReferenceActions<String, V> source) {
    this.source = Objects.requireNonNull(source);
    stamp = source.requireStamp();
  }

  public ReferenceStamp stamp() {
    return stamp;
  }

  /** Cached lifecycle evidence; operations recheck actual source READ authority. */
  public boolean current() {
    thread();
    return !closed && source.isOpen() && source.observedStamp().filter(stamp::equals).isPresent();
  }

  public void requireCurrent() {
    if (!current() || !stamp.equals(source.requireStamp()) || !current())
      throw new PolicyRefusal("reference.stale", "Reference source changed; refresh its choices");
  }

  public Search search(EntryPoint entry, ReferenceQuery query) {
    thread();
    var epoch = new Object();
    searchEpoch = epoch;
    forgetSearch();
    requireRequest(epoch, searchEpoch);
    requireCurrent();
    requireRequest(epoch, searchEpoch);
    if (query
        .cursor()
        .filter(
            cursor ->
                !cursor.snapshot().equals(stamp.snapshot()) || cursor.match() != query.match())
        .isPresent())
      throw new PolicyRefusal("reference.cursor", "The continuation belongs to another source");
    var operation = source.search(entry, query);
    if (closed || epoch != searchEpoch) {
      cancel(operation);
      throw superseded();
    }
    search = new Search(query, operation);
    return search;
  }

  public Lookup<V> resolve(EntryPoint entry, String raw) {
    thread();
    var epoch = new Object();
    lookupEpoch = epoch;
    forgetLookup();
    requireRequest(epoch, lookupEpoch);
    requireCurrent();
    requireRequest(epoch, lookupEpoch);
    var operation =
        source.resolve(entry, new ReferenceActions.Request<>(raw, Optional.of(stamp.snapshot())));
    if (closed || epoch != lookupEpoch) {
      cancel(operation);
      throw superseded();
    }
    lookup = new Lookup<>(raw, operation);
    return lookup;
  }

  /** Only this browser's actual latest handle may publish; forged wrappers are not accepted. */
  public boolean accept(Search request) {
    thread();
    if (request == null || request != search || !current()) return false;
    requireCurrent();
    if (request != search || !current()) return false;
    var result = request.operation().snapshot().result();
    if (result.isEmpty() || !result.orElseThrow().stamp().equals(stamp)) return false;
    page = result.orElseThrow();
    return true;
  }

  public boolean accept(Lookup<V> request) {
    thread();
    if (request == null || request != lookup || !current()) return false;
    requireCurrent();
    if (request != lookup || !current()) return false;
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

  /** Forget prior results before a new request, including when its admission later fails. */
  public void clearSearch() {
    thread();
    searchEpoch = new Object();
    forgetSearch();
  }

  private void forgetSearch() {
    var previous = search;
    search = null;
    page = null;
    cancel(previous == null ? null : previous.operation());
  }

  public void clearLookup() {
    thread();
    lookupEpoch = new Object();
    forgetLookup();
  }

  private void forgetLookup() {
    var previous = lookup;
    lookup = null;
    resolution = null;
    cancel(previous == null ? null : previous.operation());
  }

  private void requireRequest(Object requested, Object current) {
    if (closed || requested != current) throw superseded();
  }

  private static PolicyRefusal superseded() {
    return new PolicyRefusal(
        "reference.request_superseded", "A newer request replaced this reference request");
  }

  private static void cancel(OperationHandle<?> operation) {
    if (operation != null && !operation.snapshot().terminal()) operation.requestCancellation();
  }

  private void thread() {
    if (Thread.currentThread() != thread)
      throw new IllegalStateException("Reference browser belongs to its creating owner thread");
  }

  /** Cancels only this browser's work; the actual source remains borrowed and open. */
  @Override
  public void close() {
    thread();
    if (closed) return;
    closed = true;
    clearSearch();
    clearLookup();
  }
}
