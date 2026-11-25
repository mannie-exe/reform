package design.inherent.reform.presentation;

import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.Optional;

/** Typed annotations over actual captured input, never parsing a rendered description. */
public final class ChoicePresentation {
  private static final ChoiceDisplay.Caption ON =
      new ChoiceDisplay.Caption("true", "reform.settings.on", "On");
  private static final ChoiceDisplay.Caption OFF =
      new ChoiceDisplay.Caption("false", "reform.settings.off", "Off");

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
    if (value.unitInput().isPresent()) return Optional.empty();
    return switch (shape.kind()) {
      case BOOLEAN ->
          switch (value.raw()) {
            case "true" -> Optional.of(ON);
            case "false" -> Optional.of(OFF);
            default -> Optional.empty();
          };
      case CHOICE -> shape.choiceDisplay().flatMap(display -> display.caption(value.raw()));
      default -> Optional.empty();
    };
  }

  private ChoicePresentation() {}
}
