package design.inherent.reform.presentation.ui;

/** An owner-thread read of a cached presentation observation, never a source or effect callback. */
public interface UiRead<T> {
  UiObservation<T> snapshot();
}
