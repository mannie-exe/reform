package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Published values only. Reading a handle performs no file IO, permission change or publication.
 */
public final class SettingsHandle<T> {
  public enum Availability {
    UNINITIALIZED,
    AVAILABLE,
    UNAVAILABLE,
    CLOSED
  }

  /** Effective identity, not a saved-byte hash, permission or transferable context token. */
  public static final class Revision {
    private final long sequence;

    Revision(long sequence) {
      this.sequence = sequence;
    }

    public long sequence() {
      return sequence;
    }
  }

  public static final class View<T> {
    private final UUID context;
    private final Revision revision;
    private final Availability availability;
    private final ValueType<T> type;
    private final T value;
    private final ValidationReport observationReport;

    View(
        UUID context,
        Revision revision,
        Availability availability,
        ValueType<T> type,
        T value,
        ValidationReport observationReport) {
      this.context = context;
      this.revision = revision;
      this.availability = availability;
      this.type = type;
      this.value = value;
      this.observationReport = observationReport;
    }

    public UUID context() {
      return context;
    }

    public Revision revision() {
      return revision;
    }

    public Availability availability() {
      return availability;
    }

    public Optional<T> value() {
      return value == null ? Optional.empty() : Optional.of(type.freeze(value));
    }

    /** A failed reload can coexist with an unchanged, previously qualified active value. */
    public ValidationReport observationReport() {
      return observationReport;
    }
  }

  private final DocumentId document;
  private final AtomicReference<View<T>> current;

  SettingsHandle(DocumentId document, View<T> initial) {
    this.document = document;
    current = new AtomicReference<>(initial);
  }

  public DocumentId document() {
    return document;
  }

  public View<T> view() {
    return current.get();
  }

  void publish(View<T> view) {
    current.set(view);
  }
}
