package design.inherent.reform.presentation;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.Optional;

/** Shared numeric annotations over captured input; never a parser for rendered report strings. */
public final class NumericPresentation {
  public static Optional<NumericDisplay.Caption> caption(ValueType<?> type, DraftNode node) {
    if (type.shape() instanceof ValueShape.Defaulted<?> shape
        && node instanceof DraftNode.Defaulted value)
      return value.overridden() ? caption(shape.element(), value.value()) : Optional.empty();
    if (type.shape() instanceof ValueShape.OptionalValue<?> shape
        && node instanceof DraftNode.OptionalValue value)
      return value.present() ? caption(shape.element(), value.value()) : Optional.empty();
    return type.shape() instanceof ValueShape.Scalar shape && node instanceof DraftNode.Scalar value
        ? caption(shape, value)
        : Optional.empty();
  }

  public static Optional<NumericDisplay.Caption> caption(
      ValueShape.Scalar shape, DraftNode.Scalar value) {
    if (shape.numericDisplay().isEmpty()) return Optional.empty();
    var display = shape.numericDisplay().orElseThrow();
    if (display.captions().isEmpty()) return Optional.empty();
    try {
      var stored = display.limits().parse(value.raw());
      if (!shape.domain().acceptsRepresentation(shape.kind(), stored)) return Optional.empty();
      if (value.unitInput().isPresent()) {
        var input = value.unitInput().orElseThrow();
        if (input.state() != NumericDisplay.State.EXACT) return Optional.empty();
        var converted = display.toStored(input.text(), input.unit());
        if (converted.convertedText().isEmpty()
            || display.limits().parse(converted.text()).compareTo(stored) != 0)
          return Optional.empty();
      }
      return display.caption(stored);
    } catch (IllegalArgumentException unavailable) {
      return Optional.empty();
    }
  }

  private NumericPresentation() {}
}
