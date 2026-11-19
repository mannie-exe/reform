package design.inherent.reform.operation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkBudgetTest {
  @Test
  void cancellationReleaseKeepsBothCountsUntilLateNativeSettlement() {
    var budget = new WorkBudget(2, () -> {});
    var oldJob = budget.group(2);
    var successor = budget.group(2);
    var oldWork = oldJob.tryReserve().orElseThrow();
    var other = successor.tryReserve().orElseThrow();
    var tickets = new ArrayList<>(List.of("old", "unrelated"));
    oldWork.attachTicket(() -> assertTrue(tickets.remove("old")));
    other.attachTicket(() -> assertTrue(tickets.remove("unrelated")));

    oldJob.stop();
    oldJob.releaseTickets();
    oldJob.releaseTickets();
    assertEquals(List.of("unrelated"), tickets);
    assertTrue(oldJob.draining());
    assertEquals(1, oldJob.pending());
    assertEquals(2, budget.inUse());
    assertTrue(successor.tryReserve().isEmpty());
    assertTrue(oldJob.tryReserve().isEmpty());

    oldWork.close();
    oldWork.close();
    assertFalse(oldJob.draining());
    assertEquals(0, oldJob.pending());
    assertEquals(1, budget.inUse());
    assertEquals(1, successor.pending());
    try (var newWork = successor.tryReserve().orElseThrow()) {
      newWork.attachTicket(() -> assertEquals(List.of("unrelated"), tickets));
      assertEquals(2, budget.inUse());
    }
    other.close();
    assertEquals(0, budget.inUse());
    assertTrue(tickets.isEmpty());
  }

  @Test
  void loweredRuntimeCeilingKeepsEarlierWorkAndRespectsGroupLimit() {
    var budget = new WorkBudget(3, () -> {});
    var job = budget.group(1);
    var competing = budget.group(3);
    var reservation = job.tryReserve().orElseThrow();
    assertTrue(job.tryReserve().isEmpty());
    assertTrue(competing.tryReserve(1).isEmpty());
    assertTrue(competing.tryReserve(0).isEmpty());
    assertEquals(1, job.pending());
    reservation.close();
    var admitted = competing.tryReserve(1).orElseThrow();
    assertTrue(competing.tryReserve(1).isEmpty());
    admitted.close();
    assertEquals(0, budget.inUse());
  }

  @Test
  void ticketFailureDoesNotSkipOtherOwnedTicketsOrLeakSettledSlots() {
    var budget = new WorkBudget(2, () -> {});
    var job = budget.group(2);
    var first = job.tryReserve().orElseThrow();
    var second = job.tryReserve().orElseThrow();
    var releases = new AtomicInteger();
    first.attachTicket(
        () -> {
          throw new IllegalStateException("native ticket cleanup failed");
        });
    second.attachTicket(releases::incrementAndGet);
    assertThrows(IllegalStateException.class, job::releaseTickets);
    assertEquals(1, releases.get());
    assertEquals(2, job.pending());
    first.close();
    second.close();
    assertEquals(0, budget.inUse());

    var failing = job.tryReserve().orElseThrow();
    failing.attachTicket(
        () -> {
          throw new IllegalStateException("settlement cleanup failed");
        });
    assertThrows(IllegalStateException.class, failing::close);
    failing.close();
    assertEquals(0, job.pending());
    assertEquals(0, budget.inUse());
    assertThrows(IllegalStateException.class, () -> failing.attachTicket(() -> {}));
  }
}
