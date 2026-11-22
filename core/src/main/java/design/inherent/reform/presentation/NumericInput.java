package design.inherent.reform.presentation;

import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.value.ValueShape;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** Local display state over an exact draft; no codecs, owner callbacks or effects. */
public final class NumericInput {
  public record Change(NumericInput input, boolean accepted, String reason) {
    public Change {
      Objects.requireNonNull(input);
      Objects.requireNonNull(reason);
    }
  }

  private final ValueShape.Scalar shape;
  private final NumericDisplay display;
  private final DraftNode.Scalar expected;
  private final String unit;
  private final String text;
  private final Optional<DraftEdit.SetUnitRaw> action;
  private final String notice;
  private final Optional<NumericDisplay.Caption> caption;

  private NumericInput(
      ValueShape.Scalar shape,
      DraftNode.Scalar expected,
      String unit,
      String text,
      Optional<DraftEdit.SetUnitRaw> action,
      String notice) {
    this.shape = Objects.requireNonNull(shape);
    display = shape.numericDisplay().orElseThrow();
    this.expected = Objects.requireNonNull(expected);
    this.unit = Objects.requireNonNull(unit);
    this.text = Objects.requireNonNull(text);
    this.action = Objects.requireNonNull(action);
    this.notice = Objects.requireNonNull(notice);
    caption = NumericPresentation.caption(shape, expected);
  }

  public static NumericInput open(ValueShape.Scalar shape, DraftNode.Scalar source) {
    String canonical = shape.numericDisplay().orElseThrow().canonicalUnit();
    return new NumericInput(shape, source, canonical, source.raw(), Optional.empty(), "")
        .synchronize(source);
  }

  public NumericDisplay display() {
    return display;
  }

  public String unit() {
    return unit;
  }

  public String text() {
    return text;
  }

  public String notice() {
    return notice;
  }

  public Optional<NumericDisplay.Caption> caption() {
    return caption;
  }

  public DraftNode.Scalar expected() {
    return expected;
  }

  public Optional<DraftEdit.SetUnitRaw> action() {
    return action;
  }

  public boolean matches(DraftNode source) {
    return expected.equals(source);
  }

  /** Actual text input carries its selected unit into the shared draft. */
  public NumericInput edit(String raw) {
    var mapped = display.toStored(raw, unit);
    var node =
        new DraftNode.Scalar(mapped.text(), new DraftNode.UnitInput(unit, raw, mapped.state()));
    return new NumericInput(
        shape, node, unit, raw, Optional.of(new DraftEdit.SetUnitRaw(unit, raw)), mapped.reason());
  }

  /** Display-only switch; both the expected draft and any retry action remain exact. */
  public Change select(String nextUnit) {
    display.unit(nextUnit);
    if (unit.equals(nextUnit)) return new Change(this, true, "");
    final NumericDisplay.Conversion converted;
    try {
      converted = display.convert(text, unit, nextUnit);
    } catch (IllegalArgumentException unavailable) {
      return refused("The original input unit is unavailable; repair or revert this input");
    }
    if (converted.state() == NumericDisplay.State.EXACT
        || converted.state() == NumericDisplay.State.EMPTY
        || converted.state() == NumericDisplay.State.INCOMPLETE
        || converted.state() == NumericDisplay.State.INVALID)
      return new Change(
          new NumericInput(shape, expected, nextUnit, converted.text(), action, ""), true, "");
    return refused(converted.reason());
  }

  /** Explicit declared step, with no clamping, owner validation or cross-field modification. */
  public Change step(int direction) {
    if (direction != -1 && direction != 1)
      throw new IllegalArgumentException("A numeric step has one direction");
    final NumericDisplay.Conversion canonical;
    try {
      canonical = display.toStored(text, unit);
    } catch (IllegalArgumentException unavailable) {
      return refused("The input unit is unavailable");
    }
    if (canonical.convertedText().isEmpty()) return refused(canonical.reason());
    final BigDecimal next;
    try {
      next =
          display
              .limits()
              .parse(canonical.text())
              .add(display.step().multiply(BigDecimal.valueOf(direction)));
      display.limits().check(next);
    } catch (IllegalArgumentException limit) {
      return refused("The step exceeds the declared numeric representation budget");
    }
    if (!shape.domain().contains(next))
      return refused("The step requires " + shape.domain().description());
    if (!shape.domain().acceptsRepresentation(shape.kind(), next))
      return refused("The step cannot be represented by this numeric type within its bounds");
    var converted = display.fromStored(next.toString(), unit);
    if (converted.convertedText().isEmpty()) return refused(converted.reason());
    return new Change(edit(converted.text()), true, "");
  }

  /** Approximate thumb position only; observing it never edits or rounds the stored number. */
  public OptionalDouble position() {
    if (display.slider().isEmpty()) return OptionalDouble.empty();
    try {
      var mapped = display.toStored(text, unit);
      if (mapped.convertedText().isEmpty()) return OptionalDouble.empty();
      var number = display.limits().parse(mapped.text());
      if (!shape.domain().acceptsRepresentation(shape.kind(), number))
        return OptionalDouble.empty();
      return OptionalDouble.of(
          number
              .subtract(shape.minimum())
              .divide(shape.maximum().subtract(shape.minimum()), MathContext.DECIMAL64)
              .doubleValue());
    } catch (IllegalArgumentException unavailable) {
      return OptionalDouble.empty();
    }
  }

  /** Only an explicitly declared slider quantizes a pointer to the nearest canonical step. */
  public Change slide(double position) {
    if (display.slider().isEmpty()) return refused("This field declares no slider policy");
    if (!Double.isFinite(position) || position < 0 || position > 1)
      return refused("A slider position must be finite and within its declared track");
    final BigDecimal number;
    try {
      var span = shape.maximum().subtract(shape.minimum());
      var index =
          span.multiply(BigDecimal.valueOf(position))
              .divide(display.step(), 0, RoundingMode.HALF_UP);
      index = display.grid(shape.domain()).constrain(index);
      number = shape.minimum().add(index.multiply(display.step())).stripTrailingZeros();
      display.limits().check(number);
    } catch (IllegalArgumentException | ArithmeticException unavailable) {
      return refused("The slider value exceeds the declared numeric representation budget");
    }
    if (!shape.domain().contains(number))
      return refused("The slider value is outside its declared bounds");
    if (!shape.domain().acceptsRepresentation(shape.kind(), number))
      return refused(
          "The slider value cannot be represented by this numeric type within its bounds");
    final NumericDisplay.Conversion converted;
    try {
      converted = display.fromStored(number.toString(), unit);
    } catch (IllegalArgumentException unavailable) {
      return refused("The input unit is unavailable");
    }
    if (converted.convertedText().isEmpty()) return refused(converted.reason());
    return new Change(edit(converted.text()), true, "");
  }

  /** Observe an admitted actor change; an unrepresentable display falls back explicitly. */
  public NumericInput synchronize(DraftNode.Scalar source) {
    var retained = source.unitInput();
    if (retained.isPresent()) {
      var input = retained.orElseThrow();
      if (input.unit().equals(unit))
        return new NumericInput(shape, source, unit, input.text(), Optional.empty(), "");
      try {
        var converted = display.convert(input.text(), input.unit(), unit);
        if (converted.convertedText().isPresent())
          return new NumericInput(shape, source, unit, converted.text(), Optional.empty(), "");
      } catch (IllegalArgumentException unavailable) {
        // Retain the original unit identity below, including unavailable input.
      }
      return new NumericInput(
          shape,
          source,
          input.unit(),
          input.text(),
          Optional.empty(),
          "Showing the retained input in its original unit");
    }
    try {
      var current = display.toStored(text, unit);
      if (current.convertedText().isPresent()) {
        var number = display.limits().parse(current.text());
        if (number.compareTo(display.limits().parse(source.raw())) == 0
            && (number.signum() != 0
                || current.text().strip().startsWith("-") == source.raw().strip().startsWith("-")))
          return new NumericInput(shape, source, unit, text, Optional.empty(), "");
      }
    } catch (IllegalArgumentException unavailable) {
      // A failed or unavailable display cannot be promoted by an equal-looking stored string.
    }
    final NumericDisplay.Conversion converted;
    try {
      converted = display.fromStored(source.raw(), unit);
    } catch (IllegalArgumentException unavailable) {
      return new NumericInput(
          shape,
          source,
          display.canonicalUnit(),
          source.raw(),
          Optional.empty(),
          "The previous display unit is unavailable; showing the stored unit");
    }
    if (converted.convertedText().isPresent()
        || converted.state() == NumericDisplay.State.EMPTY
        || converted.state() == NumericDisplay.State.INCOMPLETE
        || converted.state() == NumericDisplay.State.INVALID)
      return new NumericInput(shape, source, unit, converted.text(), Optional.empty(), "");
    return new NumericInput(
        shape,
        source,
        display.canonicalUnit(),
        source.raw(),
        Optional.empty(),
        "This value cannot be displayed exactly in the selected unit; showing the stored unit");
  }

  private Change refused(String reason) {
    return new Change(this, false, reason);
  }
}
