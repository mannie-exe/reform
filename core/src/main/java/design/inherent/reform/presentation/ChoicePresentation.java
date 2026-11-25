package design.inherent.reform.presentation;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.Optional;

/** Typed annotations over actual captured input, never parsing a rendered description. */
public final class ChoicePresentation {
  public static Optional<ChoiceDisplay.Caption> caption(ValueType<?> type, DraftNode node) {
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

  public static Optional<ChoiceDisplay.Caption> caption(
      ValueShape.Scalar shape, DraftNode.Scalar value) {
    if (shape.kind() != ValueShape.ScalarKind.CHOICE
        || value.unitInput().isPresent()
        || !shape.choices().contains(value.raw())) return Optional.empty();
    return shape.choiceDisplay().flatMap(display -> display.caption(value.raw()));
  }

  private ChoicePresentation() {}
}
