package design.inherent.reform.edit;

import java.util.UUID;

/** Frozen session/context/revision identity and candidate; it grants no persistence permission. */
public final class Submission<T> {
  private final UUID id = UUID.randomUUID();
  private final UUID session;
  private final UUID context;
  private final long generation;
  private final long draftVersion;
  private final String savedRevision;
  private final Candidate<T> candidate;

  Submission(
      UUID session,
      UUID context,
      long generation,
      long draftVersion,
      String savedRevision,
      Candidate<T> candidate) {
    this.session = session;
    this.context = context;
    this.generation = generation;
    this.draftVersion = draftVersion;
    this.savedRevision = savedRevision;
    this.candidate = candidate;
  }

  public UUID id() {
    return id;
  }

  public UUID session() {
    return session;
  }

  public UUID context() {
    return context;
  }

  public long generation() {
    return generation;
  }

  public long draftVersion() {
    return draftVersion;
  }

  public String savedRevision() {
    return savedRevision;
  }

  public Candidate<T> candidate() {
    return candidate;
  }
}
