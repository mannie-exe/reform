package design.inherent.reform.minecraft;

import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.value.ValueShape;
import java.util.Optional;
import net.minecraft.network.chat.Component;

/** The same owner-declared caption for native controls, observations, and command feedback. */
public final class NumericComponents {
  /** Declared stored bounds, separate from native type limits and complete owner validation. */
  public static Optional<Component> bounds(ValueShape shape) {
    for (int depth = 0; depth < 128; depth++) {
      if (shape instanceof ValueShape.Defaulted<?> field) shape = field.element().shape();
      else if (shape instanceof ValueShape.OptionalValue<?> field) shape = field.element().shape();
      else break;
    }
    if (!(shape instanceof ValueShape.Scalar scalar)
        || switch (scalar.kind()) {
          case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE -> false;
          default -> true;
        }) return Optional.empty();
    var domain = scalar.domain();
    var description =
        domain.minimum() == null && domain.maximum() == null
            ? Component.translatableWithFallback(
                "reform.numeric.unbounded", "No additional numeric bounds")
            : Component.literal(domain.description());
    scalar
        .numericDisplay()
        .ifPresent(
            display -> {
              var unit = display.unit(display.canonicalUnit());
              description
                  .append("; ")
                  .append(
                      Component.translatableWithFallback(
                          "reform.numeric.stored_unit",
                          "stored unit: %s",
                          Component.translatableWithFallback(
                              unit.translationKey(), unit.fallback())));
            });
    return Optional.of(description);
  }

  public static Component value(Optional<NumericDisplay.Caption> caption, Component number) {
    return caption
        .<Component>map(
            value ->
                Component.translatableWithFallback(value.translationKey(), value.fallback())
                    .append(" (")
                    .append(number)
                    .append(")"))
        .orElse(number);
  }

  private NumericComponents() {}
}
