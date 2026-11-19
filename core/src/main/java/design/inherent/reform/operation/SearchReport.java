package design.inherent.reform.operation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Bounded result publication for one search. The caller owns candidate discovery and ranking. */
public final class SearchReport<R> {
  public record Limits(int maximumRows, int rowsPerPage, int maximumSnapshots, int noticeTicks) {
    public static final Limits STANDARD = new Limits(64, 10, 8, 40);

    public Limits {
      if (maximumRows < 1
          || rowsPerPage < 1
          || rowsPerPage > maximumRows
          || maximumSnapshots < 1
          || noticeTicks < 1) throw new IllegalArgumentException("Invalid search reporting limits");
    }
  }

  public enum Outcome {
    RUNNING,
    COMPLETE,
    PARTIAL,
    FAILED,
    CANCELLED
  }

  /** Counts use the same candidate population. An empty total means enumeration is still open. */
  public record Progress(long enumerated, long verified, long rejected, OptionalLong total) {
    public static final Progress INITIAL = new Progress(0, 0, 0, OptionalLong.empty());

    public Progress {
      Objects.requireNonNull(total);
      if (enumerated < 0
          || verified < 0
          || rejected < 0
          || verified > enumerated
          || rejected > enumerated - verified
          || total.isPresent() && total.getAsLong() < enumerated)
        throw new IllegalArgumentException("Candidate counts disagree");
    }

    public long checked() {
      return verified + rejected;
    }

    public boolean checkedAll() {
      return total.isPresent() && checked() == total.getAsLong();
    }
  }

  /** Found counts apply to this admitted search. The sort/display limit is a separate fact. */
  public record Status(
      UUID job,
      long revision,
      Progress progress,
      long found,
      int retained,
      Outcome outcome,
      boolean cancellationRequested,
      String reason) {
    public boolean terminal() {
      return outcome != Outcome.RUNNING;
    }

    public boolean fullCoverage() {
      return outcome == Outcome.COMPLETE;
    }

    public boolean rowsTruncated() {
      return found > retained;
    }
  }

  public record Cursor(UUID job, long revision, int page) {
    public Cursor {
      Objects.requireNonNull(job);
      if (revision < 0 || page < 1) throw new IllegalArgumentException("Invalid search cursor");
    }
  }

  public record Page<R>(Cursor cursor, int pages, Status status, List<R> rows) {
    public Page {
      rows = List.copyOf(rows);
    }

    public Optional<Cursor> previous() {
      return cursor.page() > 1 ? Optional.of(at(cursor.page() - 1)) : Optional.empty();
    }

    public Optional<Cursor> next() {
      return cursor.page() < pages ? Optional.of(at(cursor.page() + 1)) : Optional.empty();
    }

    private Cursor at(int number) {
      return new Cursor(cursor.job(), cursor.revision(), number);
    }
  }

  public record Notice<R>(long newlyFound, Page<R> firstPage) {}

  private record Captured<R>(Status status, List<R> rows) {}

  private final UUID id = UUID.randomUUID();
  private final Limits limits;
  private final Runnable ownerThread;
  private final Map<Long, Captured<R>> published = new LinkedHashMap<>();
  private Progress progress = Progress.INITIAL;
  private List<R> rows = List.of();
  private long revision, found, notified;
  private long lastNoticeTick = -1;
  private Outcome outcome = Outcome.RUNNING;
  private boolean cancellationRequested;
  private String reason = "";

  public SearchReport(Limits limits, Runnable ownerThread) {
    this.limits = Objects.requireNonNull(limits);
    this.ownerThread = Objects.requireNonNull(ownerThread);
    ownerThread.run();
  }

  public UUID id() {
    return id;
  }

  public Status status() {
    ownerThread.run();
    return new Status(
        id, revision, progress, found, rows.size(), outcome, cancellationRequested, reason);
  }

  /** Submit immutable, already ranked rows and the number of distinct admitted matches. */
  public boolean observe(Progress next, long distinctFound, List<? extends R> ranked) {
    ownerThread.run();
    if (outcome != Outcome.RUNNING || cancellationRequested) return false;
    Objects.requireNonNull(next);
    Objects.requireNonNull(ranked);
    if (next.enumerated() < progress.enumerated()
        || next.verified() < progress.verified()
        || next.rejected() < progress.rejected()
        || progress.total().isPresent() && !progress.total().equals(next.total())
        || distinctFound < found
        || ranked.size() > limits.maximumRows()
        || ranked.size() > distinctFound)
      throw new IllegalArgumentException("Search observation exceeds its counts or limits");
    var nextRows = List.<R>copyOf(ranked);
    if (!next.equals(progress) || distinctFound != found || !nextRows.equals(rows)) {
      advance();
      progress = next;
      found = distinctFound;
      rows = nextRows;
    }
    return true;
  }

  /** Freeze result admission. The provider still owns the outstanding work and its completion. */
  public boolean requestCancellation(UUID job) {
    ownerThread.run();
    if (!id.equals(job) || outcome != Outcome.RUNNING || cancellationRequested) return false;
    advance();
    cancellationRequested = true;
    return true;
  }

  public boolean finish(Outcome terminal, String explanation) {
    ownerThread.run();
    if (outcome != Outcome.RUNNING) return false;
    Objects.requireNonNull(terminal);
    Objects.requireNonNull(explanation);
    if (terminal == Outcome.RUNNING
        || explanation.length() > 512
        || terminal != Outcome.COMPLETE && explanation.isBlank()
        || terminal == Outcome.COMPLETE && (cancellationRequested || !progress.checkedAll()))
      throw new IllegalArgumentException("Search outcome requires qualified coverage or a reason");
    advance();
    outcome = terminal;
    reason = explanation;
    return true;
  }

  /** Capture current ranked results only when issuing a page or notice. */
  public Optional<Page<R>> capture(int page) {
    ownerThread.run();
    if (page < 1 || page > pages(rows.size())) return Optional.empty();
    var selected = published.get(revision);
    if (selected == null) {
      if (published.size() == limits.maximumSnapshots())
        published.remove(published.keySet().iterator().next());
      selected = new Captured<>(status(), rows);
      published.put(revision, selected);
    }
    return Optional.of(page(selected, new Cursor(id, revision, page)));
  }

  /** Follow the exact capture. Expiration or another job's identity yields no page. */
  public Optional<Page<R>> page(Cursor cursor) {
    ownerThread.run();
    if (!id.equals(cursor.job())) return Optional.empty();
    var selected = published.get(cursor.revision());
    return selected == null || cursor.page() > pages(selected.rows().size())
        ? Optional.empty()
        : Optional.of(page(selected, cursor));
  }

  /** Call only while an admitted recipient is watching. Terminal results flush an unseen delta. */
  public Optional<Notice<R>> notice(long tick) {
    ownerThread.run();
    if (tick < 0 || tick < lastNoticeTick)
      throw new IllegalArgumentException("Use a monotonic owner tick for search notices");
    if (found == notified
        || (outcome == Outcome.RUNNING
            && lastNoticeTick >= 0
            && tick - lastNoticeTick < limits.noticeTicks())) return Optional.empty();
    var notice = new Notice<>(found - notified, capture(1).orElseThrow());
    notified = found;
    lastNoticeTick = tick;
    return Optional.of(notice);
  }

  private int pages(int size) {
    return Math.max(1, Math.ceilDiv(size, limits.rowsPerPage()));
  }

  private Page<R> page(Captured<R> selected, Cursor cursor) {
    int first = (cursor.page() - 1) * limits.rowsPerPage();
    int last = first + Math.min(limits.rowsPerPage(), selected.rows().size() - first);
    return new Page<>(
        cursor,
        pages(selected.rows().size()),
        selected.status(),
        selected.rows().subList(first, last));
  }

  private void advance() {
    revision = Math.incrementExact(revision);
  }
}
