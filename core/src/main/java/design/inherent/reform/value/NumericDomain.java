package design.inherent.reform.value;

import java.math.BigDecimal;
import java.util.Objects;

/** Mathematical bounds in stored units, independent of representation and interaction steps. */
public record NumericDomain(
    BigDecimal minimum, boolean minimumInclusive, BigDecimal maximum, boolean maximumInclusive) {
  public NumericDomain {
    if (minimum == null && !minimumInclusive || maximum == null && !maximumInclusive)
      throw new IllegalArgumentException("An absent numeric bound cannot be exclusive");
    if (minimum != null && maximum != null) {
      int order = minimum.compareTo(maximum);
      if (order > 0 || order == 0 && (!minimumInclusive || !maximumInclusive))
        throw new IllegalArgumentException("Numeric bounds describe an empty domain");
    }
  }

  public static NumericDomain closed(BigDecimal minimum, BigDecimal maximum) {
    return new NumericDomain(minimum, true, maximum, true);
  }

  public static NumericDomain open(BigDecimal minimum, BigDecimal maximum) {
    return new NumericDomain(
        Objects.requireNonNull(minimum), false, Objects.requireNonNull(maximum), false);
  }

  public static NumericDomain openClosed(BigDecimal minimum, BigDecimal maximum) {
    return new NumericDomain(Objects.requireNonNull(minimum), false, maximum, true);
  }

  public static NumericDomain closedOpen(BigDecimal minimum, BigDecimal maximum) {
    return new NumericDomain(minimum, true, Objects.requireNonNull(maximum), false);
  }

  public boolean below(BigDecimal value) {
    Objects.requireNonNull(value);
    return minimum != null
        && (value.compareTo(minimum) < 0 || !minimumInclusive && value.compareTo(minimum) == 0);
  }

  public boolean above(BigDecimal value) {
    Objects.requireNonNull(value);
    return maximum != null
        && (value.compareTo(maximum) > 0 || !maximumInclusive && value.compareTo(maximum) == 0);
  }

  public boolean contains(BigDecimal value) {
    return !below(value) && !above(value);
  }

  /** Native numeric conversion may round, but neither its input nor result may cross a bound. */
  public boolean acceptsRepresentation(ValueShape.ScalarKind kind, BigDecimal value) {
    if (!contains(value)) return false;
    try {
      return switch (kind) {
        case BYTE -> {
          value.byteValueExact();
          yield true;
        }
        case SHORT -> {
          value.shortValueExact();
          yield true;
        }
        case INTEGER -> {
          value.intValueExact();
          yield true;
        }
        case LONG -> {
          value.longValueExact();
          yield true;
        }
        case FLOAT -> {
          float converted = value.floatValue();
          yield Float.isFinite(converted)
              && (converted != 0 || value.signum() == 0)
              && contains(new BigDecimal(Float.toString(converted)));
        }
        case DOUBLE -> {
          double converted = value.doubleValue();
          yield Double.isFinite(converted)
              && (converted != 0 || value.signum() == 0)
              && contains(BigDecimal.valueOf(converted));
        }
        default -> false;
      };
    } catch (ArithmeticException outsideType) {
      return false;
    }
  }

  /** A caption names a stored value, rather than a distinct decimal that rounds to that value. */
  public boolean isStoredValue(ValueShape.ScalarKind kind, BigDecimal value) {
    if (!acceptsRepresentation(kind, value)) return false;
    return switch (kind) {
      case FLOAT -> new BigDecimal(Float.toString(value.floatValue())).compareTo(value) == 0;
      case DOUBLE -> BigDecimal.valueOf(value.doubleValue()).compareTo(value) == 0;
      default -> true;
    };
  }

  /** A shared explanation for typed validation and presentation, without rounded endpoints. */
  public String description() {
    String lower = minimum == null ? "" : "value " + (minimumInclusive ? ">= " : "> ") + minimum;
    String upper = maximum == null ? "" : "value " + (maximumInclusive ? "<= " : "< ") + maximum;
    return lower.isEmpty()
        ? upper.isEmpty() ? "any finite value" : upper
        : upper.isEmpty() ? lower : lower + " and " + upper;
  }

  /** Reject a mathematically nonempty interval that contains no value of the declared type. */
  void requireRepresentable(ValueShape.ScalarKind kind) {
    boolean available =
        switch (kind) {
          case BYTE -> containsInteger(Byte.MIN_VALUE, Byte.MAX_VALUE);
          case SHORT -> containsInteger(Short.MIN_VALUE, Short.MAX_VALUE);
          case INTEGER -> containsInteger(Integer.MIN_VALUE, Integer.MAX_VALUE);
          case LONG -> containsInteger(Long.MIN_VALUE, Long.MAX_VALUE);
          case FLOAT -> containsFloating(true);
          case DOUBLE -> containsFloating(false);
          default -> true;
        };
    if (!available)
      throw new IllegalArgumentException(
          "Numeric domain contains no representable " + kind + " value");
  }

  private boolean containsInteger(long nativeMinimum, long nativeMaximum) {
    var first = BigDecimal.valueOf(nativeMinimum);
    var last = BigDecimal.valueOf(nativeMaximum);
    if (minimum != null && minimum.compareTo(first) >= 0) {
      if (minimum.compareTo(last) > 0) return false;
      // Already bounded by the native interval; avoid constructing powers of ten for tiny input.
      first = BigDecimal.valueOf(minimum.longValue());
      if (first.compareTo(minimum) < 0) first = first.add(BigDecimal.ONE);
      if (below(first)) first = first.add(BigDecimal.ONE);
    }
    return first.compareTo(last) <= 0 && contains(first);
  }

  private boolean containsFloating(boolean singlePrecision) {
    if (singlePrecision) {
      float first = minimum == null ? -Float.MAX_VALUE : minimum.floatValue();
      if (first == Float.NEGATIVE_INFINITY) first = -Float.MAX_VALUE;
      if (!Float.isFinite(first)) return false;
      if (below(new BigDecimal(Float.toString(first)))) first = Math.nextUp(first);
      return Float.isFinite(first) && contains(new BigDecimal(Float.toString(first)));
    }
    double first = minimum == null ? -Double.MAX_VALUE : minimum.doubleValue();
    if (first == Double.NEGATIVE_INFINITY) first = -Double.MAX_VALUE;
    if (!Double.isFinite(first)) return false;
    if (below(BigDecimal.valueOf(first))) first = Math.nextUp(first);
    return Double.isFinite(first) && contains(BigDecimal.valueOf(first));
  }
}
