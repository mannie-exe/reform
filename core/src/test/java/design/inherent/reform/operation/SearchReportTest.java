package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SearchReportTest {
  private static SearchReport<String> report(int captures) {
    return new SearchReport<>(new SearchReport.Limits(4, 2, captures, 10), () -> {});
  }

  private static SearchReport.Progress searched(long candidates) {
    return new SearchReport.Progress(candidates, candidates, 0, OptionalLong.empty());
  }

  @Test
  void linkedPageKeepsOriginalRowsAndRejectsForeignJob() {
    var original = report(3);
    var callerRows = new ArrayList<>(List.of("B", "C", "D"));
    assertTrue(original.observe(searched(4), 3, callerRows));
    var published = original.capture(1).orElseThrow();
    var next = published.next().orElseThrow();
    callerRows.clear();
    original.observe(searched(5), 4, List.of("A", "B", "C", "D"));
    assertEquals(List.of("C", "D"), original.capture(2).orElseThrow().rows());
    assertEquals(List.of("B", "C"), original.page(published.cursor()).orElseThrow().rows());
    assertEquals(List.of("D"), original.page(next).orElseThrow().rows());
    var replacement = report(3);
    replacement.observe(searched(1), 1, List.of("other"));
    assertTrue(replacement.page(next).isEmpty());
    assertFalse(replacement.requestCancellation(original.id()));
    assertFalse(replacement.status().cancellationRequested());
  }

  @Test
  void resultUpdatesDoNotEvictAnIssuedCursorUntilAnotherCaptureIsPublished() {
    var report = report(2);
    var first = report.capture(1).orElseThrow().cursor();
    assertTrue(report.capture(Integer.MAX_VALUE).isEmpty());
    for (int i = 1; i <= 100; i++) report.observe(searched(i), i, List.of("match"));
    assertTrue(report.page(first).isPresent());
    var next = report.capture(1).orElseThrow();
    assertTrue(next.status().rowsTruncated());
    assertEquals(100, next.status().found());
    report.observe(searched(101), 101, List.of("another"));
    report.capture(1).orElseThrow();
    assertTrue(report.page(first).isEmpty());
    assertEquals(List.of("match"), report.page(next.cursor()).orElseThrow().rows());
  }

  @Test
  void laterMatchesAreCoalescedAndTerminalPartialFlushesBeforeNextInterval() {
    var report = report(6);
    assertTrue(report.notice(0).isEmpty());
    report.observe(searched(2), 1, List.of("A"));
    assertEquals(1, report.notice(1).orElseThrow().newlyFound());
    report.observe(searched(3), 2, List.of("B", "A"));
    assertTrue(report.notice(2).isEmpty());
    report.observe(searched(4), 3, List.of("C", "B", "A"));
    var later = report.notice(11).orElseThrow();
    assertEquals(2, later.newlyFound());
    assertEquals(List.of("C", "B"), later.firstPage().rows());
    report.observe(searched(5), 4, List.of("C", "D", "A", "B"));
    report.finish(SearchReport.Outcome.PARTIAL, "Time limit reached");
    var terminal = report.notice(12).orElseThrow();
    assertEquals(1, terminal.newlyFound());
    assertEquals(SearchReport.Outcome.PARTIAL, terminal.firstPage().status().outcome());
    assertFalse(terminal.firstPage().status().fullCoverage());
    assertTrue(report.notice(13).isEmpty());
  }

  @Test
  void unknownEmptyAndCompleteZeroRemainDifferent() {
    var report = report(2);
    assertEquals(SearchReport.Outcome.RUNNING, report.capture(1).orElseThrow().status().outcome());
    assertThrows(
        IllegalArgumentException.class, () -> report.finish(SearchReport.Outcome.COMPLETE, ""));
    report.observe(new SearchReport.Progress(2, 1, 0, OptionalLong.of(2)), 0, List.of());
    assertThrows(
        IllegalArgumentException.class, () -> report.finish(SearchReport.Outcome.COMPLETE, ""));
    report.observe(new SearchReport.Progress(2, 1, 1, OptionalLong.of(2)), 0, List.of());
    assertTrue(report.finish(SearchReport.Outcome.COMPLETE, ""));
    assertTrue(report.status().fullCoverage());
    assertEquals(0, report.status().found());
    assertFalse(report.status().rowsTruncated());
  }

  @Test
  void cancelledAndFailedSearchesKeepPartialRowsWithoutAdmittingLateResults() {
    var report = report(3);
    report.observe(searched(5), 2, List.of("A", "B"));
    assertTrue(report.requestCancellation(report.id()));
    assertEquals(SearchReport.Outcome.RUNNING, report.status().outcome());
    assertTrue(report.status().cancellationRequested());
    assertFalse(report.observe(searched(6), 3, List.of("A", "B", "C")));
    assertTrue(report.finish(SearchReport.Outcome.CANCELLED, "Requested by operator"));
    assertEquals(List.of("A", "B"), report.capture(1).orElseThrow().rows());
    assertFalse(report.finish(SearchReport.Outcome.FAILED, "Late completion"));
    var failed = report(2);
    failed.observe(searched(3), 1, List.of("confirmed"));
    assertTrue(failed.finish(SearchReport.Outcome.FAILED, "Native result was unavailable"));
    assertEquals(List.of("confirmed"), failed.capture(1).orElseThrow().rows());
  }

  @Test
  void impossibleCoverageAndRegressingOrOversizedUpdatesDoNotChangeReport() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new SearchReport.Progress(Long.MAX_VALUE, Long.MAX_VALUE, 1, OptionalLong.empty()));
    var report = report(2);
    report.observe(new SearchReport.Progress(5, 4, 1, OptionalLong.of(7)), 1, List.of("confirmed"));
    var before = report.status();
    assertThrows(
        IllegalArgumentException.class, () -> report.observe(searched(6), 2, List.of("new")));
    assertThrows(
        IllegalArgumentException.class,
        () -> report.observe(before.progress(), 5, List.of("a", "b", "c", "d", "e")));
    assertThrows(
        IllegalArgumentException.class, () -> report.observe(before.progress(), 0, List.of("new")));
    assertEquals(before, report.status());
  }
}
