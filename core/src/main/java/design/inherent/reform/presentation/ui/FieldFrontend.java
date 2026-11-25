package design.inherent.reform.presentation.ui;

import java.util.Objects;
import java.util.Optional;

/**
 * Cached frontend input and extra edit/submit restrictions; actual owner checks remain mandatory.
 */
public interface FieldFrontend {
  record Input(String raw, long epoch, int caret, int anchor, boolean accepted, boolean pending) {
    public Input {
      Objects.requireNonNull(raw);
      if (epoch < 0 || caret < 0 || anchor < 0 || caret > raw.length() || anchor > raw.length())
        throw new IllegalArgumentException("Invalid native input position or epoch");
    }
  }

  Optional<Input> input();

  UiAvailability editing();

  UiAvailability submission();

  FieldFrontend NONE =
      new FieldFrontend() {
        public Optional<Input> input() {
          return Optional.empty();
        }

        public UiAvailability editing() {
          return UiAvailability.READY;
        }

        public UiAvailability submission() {
          return UiAvailability.blocked(
              "field.no_submission_surface", "No submission surface is attached.");
        }
      };
}
