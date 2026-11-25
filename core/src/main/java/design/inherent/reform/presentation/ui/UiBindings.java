package design.inherent.reform.presentation.ui;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Restricted adapters. Local bindings carry no document or persistence capability. */
public final class UiBindings {
  public static <T> UiBinding<T> local(UiLocal<T> state) {
    Objects.requireNonNull(state);
    var command =
        new UiCommand<T>() {
          @Override
          public UiAvailability availability() {
            try {
              state.snapshot();
              return UiAvailability.READY;
            } catch (PolicyRefusal unavailable) {
              return new UiAvailability(false, unavailable.report());
            }
          }

          @Override
          public UiActionResult invoke(T input) {
            try {
              state.replace(input);
              return new UiActionResult.Applied();
            } catch (PolicyRefusal refused) {
              return new UiActionResult.Refused(refused.report(), Optional.of(refused));
            } catch (IllegalArgumentException invalid) {
              return new UiActionResult.Refused(
                  new PolicyRefusal(
                          "ui.input_invalid", "This value does not fit the declared input")
                      .report(),
                  Optional.of(invalid));
            }
          }

          @Override
          public List<UiRead<?>> dependencies() {
            return List.of(state);
          }
        };
    return new UiBinding<>() {
      @Override
      public UiRead<T> value() {
        return state;
      }

      @Override
      public UiCommand<T> changes() {
        return command;
      }
    };
  }

  private UiBindings() {}
}
