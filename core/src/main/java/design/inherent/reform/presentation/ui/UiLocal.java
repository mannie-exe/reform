package design.inherent.reform.presentation.ui;

/** View-local data only. This capability cannot edit, validate, or save a document. */
public interface UiLocal<T> extends UiRead<T> {
  void replace(T value);
}
