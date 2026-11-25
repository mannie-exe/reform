package design.inherent.reform.presentation.ui;

/**
 * Owned bounded observation data. This is neither a transferable capability nor necessarily a
 * bookmark.
 */
public interface UiSnapshot<T> {
  T value();

  /** Accounted retained payload; this is not a JVM heap measurement. */
  long retainedBytes();

  boolean sameData(UiSnapshot<?> other);
}
