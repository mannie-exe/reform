package design.inherent.reform.presentation.ui;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Restricted adapters. Local bindings carry no document or persistence capability. */
public final class UiBindings {
  /**
   * Project one typed part of the same local value. Replacement runs as a pure derivation; the
   * original local's complete codec/budget admission precedes its state change.
   */
  public static <S, V> UiBinding<V> lens(
      UiScope scope,
      UiLocal<S> state,
      design.inherent.reform.presentation.ViewStateKey<V> part,
      UiLens<S, V> lens) {
    Objects.requireNonNull(lens);
    var read = scope.derive(part, List.of(state), values -> lens.get().apply(values.value(state)));
    var parent = local(state);
    return new UiBinding<>() {
      public UiRead<V> value() {
        return read;
      }

      public UiCommand<V> changes() {
        return new UiCommand<>() {
          public List<UiRead<?>> dependencies() {
            return List.of(state, read);
          }

          public UiAvailability availability() {
            return parent.changes().availability();
          }

          public UiActionResult invoke(V input) {
            try {
              var frozen = part.capture(input).value();
              var replacement =
                  scope.project(
                      List.of(state),
                      () ->
                          Objects.requireNonNull(
                              lens.replace().apply(state.snapshot().value(), frozen)));
              return parent.changes().invoke(replacement);
            } catch (PolicyRefusal refused) {
              return new UiActionResult.Refused(refused.report(), Optional.of(refused));
            } catch (IllegalArgumentException invalid) {
              return new UiActionResult.Refused(
                  new PolicyRefusal(
                          "ui.input_invalid", "This value does not fit the complete local state.")
                      .report(),
                  Optional.of(invalid));
            }
          }
        };
      }
    };
  }

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

  /**
   * Toggle the current controlled value; pending or refused writes never change it optimistically.
   */
  public static UiCommand<Void> toggle(UiBinding<Boolean> binding) {
    Objects.requireNonNull(binding);
    var dependencies = new java.util.ArrayList<UiRead<?>>();
    dependencies.add(binding.value());
    dependencies.addAll(binding.changes().dependencies());
    var reads = List.copyOf(dependencies);
    return new UiCommand<>() {
      @Override
      public UiAvailability availability() {
        var value = binding.value().snapshot();
        if (value instanceof UiObservation.Unavailable<Boolean> missing)
          return new UiAvailability(false, missing.report());
        return binding.changes().availability();
      }

      @Override
      public UiActionResult invoke(Void ignored) {
        var ready = availability();
        if (!ready.available()) return new UiActionResult.Refused(ready.report(), Optional.empty());
        return binding.changes().invoke(!binding.value().snapshot().value());
      }

      @Override
      public List<UiRead<?>> dependencies() {
        return reads;
      }
    };
  }

  private UiBindings() {}
}
