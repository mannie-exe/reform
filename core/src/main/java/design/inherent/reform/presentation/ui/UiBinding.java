package design.inherent.reform.presentation.ui;

/** Controlled state: one authoritative cached read plus its actual guarded change command. */
public interface UiBinding<T> {
  UiRead<T> value();

  UiCommand<T> changes();
}
