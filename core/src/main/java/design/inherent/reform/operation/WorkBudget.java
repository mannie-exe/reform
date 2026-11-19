package design.inherent.reform.operation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact accounting for provider-owned asynchronous work on one owner thread. */
public final class WorkBudget {
  /** One admitted work item. Its optional native ticket and eventual settlement are distinct. */
  public final class Reservation implements AutoCloseable {
    private final Group group;
    private boolean outstanding = true;
    private boolean ticketAttached;
    private Runnable ticketRelease;

    private Reservation(Group group) {
      this.group = group;
    }

    /** Register the removal of this item's already acquired ticket. */
    public void attachTicket(Runnable release) {
      ownerThread.run();
      Objects.requireNonNull(release);
      if (!outstanding || ticketAttached)
        throw new IllegalStateException("Ticket already attached or reservation settled");
      ticketRelease = release;
      ticketAttached = true;
    }

    /** Release only this ticket. The native request still occupies its slot until it settles. */
    public void releaseTicket() {
      ownerThread.run();
      var remove = ticketRelease;
      ticketRelease = null;
      if (remove != null) remove.run();
    }

    /** Call after native work settles, including failed or refused submission. */
    @Override
    public void close() {
      ownerThread.run();
      if (!outstanding) return;
      outstanding = false;
      try {
        releaseTicket();
      } finally {
        group.pending.remove(this);
        inUse--;
      }
    }
  }

  /** Reservations for one job. Stopping admission preserves all still outstanding work. */
  public final class Group {
    private final int maximum;
    private final Set<Reservation> pending = new LinkedHashSet<>();
    private boolean accepting = true;

    private Group(int maximum) {
      this.maximum = maximum;
    }

    public Optional<Reservation> tryReserve() {
      return tryReserve(limit);
    }

    /** A live policy may lower the shared ceiling without retiring older reservations. */
    public Optional<Reservation> tryReserve(int currentCeiling) {
      ownerThread.run();
      if (currentCeiling < 0) throw new IllegalArgumentException("Negative work ceiling");
      if (!accepting || pending.size() >= maximum || inUse >= Math.min(limit, currentCeiling))
        return Optional.empty();
      var reservation = new Reservation(this);
      pending.add(reservation);
      inUse++;
      return Optional.of(reservation);
    }

    public int pending() {
      ownerThread.run();
      return pending.size();
    }

    public boolean draining() {
      ownerThread.run();
      return !accepting && !pending.isEmpty();
    }

    public void stop() {
      ownerThread.run();
      accepting = false;
    }

    /** Optional early release, following this provider's native ticket policy. */
    public void releaseTickets() {
      ownerThread.run();
      Throwable failure = null;
      for (var reservation : List.copyOf(pending))
        try {
          reservation.releaseTicket();
        } catch (RuntimeException | Error failed) {
          if (failure == null) failure = failed;
          else if (failure != failed) failure.addSuppressed(failed);
        }
      if (failure instanceof RuntimeException failed) throw failed;
      if (failure instanceof Error failed) throw failed;
    }
  }

  private final int limit;
  private final Runnable ownerThread;
  private int inUse;

  public WorkBudget(int limit, Runnable ownerThread) {
    if (limit < 1) throw new IllegalArgumentException("Work budget must be positive");
    this.limit = limit;
    this.ownerThread = Objects.requireNonNull(ownerThread);
    ownerThread.run();
  }

  public Group group(int maximum) {
    ownerThread.run();
    if (maximum < 1 || maximum > limit)
      throw new IllegalArgumentException("Group limit must fit the shared work budget");
    return new Group(maximum);
  }

  public int inUse() {
    ownerThread.run();
    return inUse;
  }
}
