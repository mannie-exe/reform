package design.inherent.reform.presentation.ui;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.IdentityHashMap;
import java.util.Map;

/** Only the declared, revision-qualified inputs of one pure computation. */
public final class UiInputs {
  private final Map<UiRead<?>, UiObservation<?>> values;

  UiInputs(Map<UiRead<?>, UiObservation<?>> values) {
    this.values = new IdentityHashMap<>(values);
  }

  public <T> T value(UiRead<T> input) {
    var observation = values.get(input);
    if (observation == null)
      throw new PolicyRefusal(
          "ui.undeclared_dependency", "Declare this presentation input before reading it");
    // Each entry was supplied by the same typed UiRead, not a key or caller-provided cast.
    @SuppressWarnings("unchecked")
    var typed = (UiObservation<T>) observation;
    return typed.value();
  }
}
