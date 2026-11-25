package design.inherent.reform.presentation.ui;

import java.util.List;

/**
 * Actual service-bound invocation. Callers recheck readiness; the service still admits the effect.
 */
public interface UiCommand<I> {
  UiAvailability availability();

  UiActionResult invoke(I input);

  /** Declared kernel reads needed to project cached readiness, not a source permission override. */
  default List<UiRead<?>> dependencies() {
    return List.of();
  }
}
