package design.inherent.reform.presentation;

import design.inherent.reform.serialization.DecimalText;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact opt-in display mapping; canonical storage/owner validation are never replaced. */
public record NumericDisplay(
    String canonicalUnit,
    List<Unit> units,
    BigDecimal step,
    Optional<BigDecimal> referenceTicksPerSecond,
    DecimalText.Limits limits) {
  public record Unit(
      String id, String translationKey, String fallback, BigDecimal canonicalPerUnit) {
    public Unit(String id, String fallback, BigDecimal canonicalPerUnit) {
      this(id, "reform.unit." + id, fallback, canonicalPerUnit);
    }

    public Unit {
      if (!Objects.requireNonNull(id).matches("[a-z][a-z0-9_-]{0,63}")
          || Objects.requireNonNull(translationKey).isBlank()
          || translationKey.length() > 256
          || Objects.requireNonNull(fallback).length() > 256)
        throw new IllegalArgumentException("Unit identity/caption is not bounded");
      DecimalText.Limits.CONFIGURATION.check(Objects.requireNonNull(canonicalPerUnit));
      if (canonicalPerUnit.signum() <= 0)
        throw new IllegalArgumentException("Unit conversion needs a positive exact factor");
    }
  }

  public enum State {
    EXACT,
    EMPTY,
    INCOMPLETE,
    INVALID,
    UNREPRESENTABLE,
    LIMIT
  }

  /** Failed conversion retains the exact source input; it is never an invented numeric default. */
  public record Conversion(String sourceText, String text, State state, String reason) {
    public Conversion {
      Objects.requireNonNull(sourceText);
      Objects.requireNonNull(text);
      Objects.requireNonNull(state);
      Objects.requireNonNull(reason);
      if (state != State.EXACT && !text.equals(sourceText))
        throw new IllegalArgumentException("A failed display conversion must retain its input");
    }
  }

  public NumericDisplay {
    Objects.requireNonNull(canonicalUnit);
    units = List.copyOf(units);
    Objects.requireNonNull(step);
    Objects.requireNonNull(referenceTicksPerSecond);
    Objects.requireNonNull(limits);
    if (units.isEmpty() || units.size() > 16 || step.signum() <= 0)
      throw new IllegalArgumentException(
          "Numeric display requires bounded units and a positive step");
    limits.check(step);
    var ids = new HashSet<String>();
    for (var unit : units) {
      if (!ids.add(unit.id())) throw new IllegalArgumentException("Duplicate unit ID");
      limits.check(unit.canonicalPerUnit());
    }
    var canonical =
        units.stream()
            .filter(unit -> unit.id().equals(canonicalUnit))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Missing canonical unit"));
    if (canonical.canonicalPerUnit().compareTo(BigDecimal.ONE) != 0)
      throw new IllegalArgumentException("The canonical unit factor must equal one");
    referenceTicksPerSecond.ifPresent(
        rate -> {
          limits.check(rate);
          if (rate.signum() <= 0)
            throw new IllegalArgumentException("Reference tick rate must be positive");
        });
  }

  public Unit unit(String id) {
    return units.stream()
        .filter(unit -> unit.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown display unit: " + id));
  }

  public Conversion toStored(String raw, String displayUnit) {
    return convert(raw, displayUnit, canonicalUnit);
  }

  public Conversion fromStored(String raw, String displayUnit) {
    return convert(raw, canonicalUnit, displayUnit);
  }

  /** Decimal grammar only. A nonterminating result is explicit, with no rounding or expressions. */
  public Conversion convert(String raw, String from, String to) {
    Objects.requireNonNull(raw);
    var source = unit(from);
    var target = unit(to);
    if (raw.length() > limits.maximumCharacters())
      return failed(raw, State.LIMIT, "Number exceeds the declared input budget");
    var stripped = raw.strip();
    if (stripped.isEmpty()) return failed(raw, State.EMPTY, "Enter a number");
    if (DecimalText.incomplete(stripped))
      return failed(raw, State.INCOMPLETE, "Complete the number");
    final BigDecimal value;
    try {
      value = limits.parse(raw);
    } catch (NumberFormatException invalid) {
      return failed(raw, State.INVALID, "Enter a decimal number");
    } catch (IllegalArgumentException limit) {
      return failed(raw, State.LIMIT, "Number exceeds the declared precision/scale budget");
    }
    if (source.canonicalPerUnit().compareTo(target.canonicalPerUnit()) == 0)
      return new Conversion(raw, raw, State.EXACT, "");
    final BigDecimal converted;
    try {
      converted = value.multiply(source.canonicalPerUnit()).divide(target.canonicalPerUnit());
    } catch (ArithmeticException repeating) {
      return failed(
          raw, State.UNREPRESENTABLE, "This value has no exact finite decimal in that unit");
    }
    try {
      limits.check(converted);
    } catch (IllegalArgumentException limit) {
      return failed(
          raw, State.LIMIT, "Converted number exceeds the declared precision/scale budget");
    }
    String text = converted.stripTrailingZeros().toString();
    if (converted.signum() == 0 && stripped.startsWith("-") && !text.startsWith("-"))
      text = "-" + text;
    if (text.length() > limits.maximumCharacters())
      return failed(raw, State.LIMIT, "Converted number exceeds the declared text budget");
    return new Conversion(raw, text, State.EXACT, "");
  }

  private static Conversion failed(String raw, State state, String reason) {
    return new Conversion(raw, raw, state, reason);
  }

  /** A nominal tick reference, never a wall-clock promise or live server-rate query. */
  public static NumericDisplay ticks(BigDecimal nominalTicksPerSecond) {
    Objects.requireNonNull(nominalTicksPerSecond);
    DecimalText.Limits.CONFIGURATION.check(nominalTicksPerSecond);
    return new NumericDisplay(
        "ticks",
        List.of(
            new Unit("ticks", "ticks", BigDecimal.ONE),
            new Unit("seconds", "seconds", nominalTicksPerSecond),
            new Unit("milliseconds", "milliseconds", nominalTicksPerSecond.movePointLeft(3))),
        BigDecimal.ONE,
        Optional.of(nominalTicksPerSecond),
        DecimalText.Limits.CONFIGURATION);
  }

  public static NumericDisplay blocks() {
    return new NumericDisplay(
        "blocks",
        List.of(
            new Unit("blocks", "blocks", BigDecimal.ONE),
            new Unit("chunks", "chunks", BigDecimal.valueOf(16))),
        BigDecimal.ONE,
        Optional.empty(),
        DecimalText.Limits.CONFIGURATION);
  }

  /** Stored 0..1 probability is distinct from a file that already stores 0..100 percent. */
  public static NumericDisplay probability() {
    return new NumericDisplay(
        "probability",
        List.of(
            new Unit("probability", "probability", BigDecimal.ONE),
            new Unit("percent", "percent", new BigDecimal("0.01"))),
        new BigDecimal("0.01"),
        Optional.empty(),
        DecimalText.Limits.CONFIGURATION);
  }

  public static NumericDisplay percentStored() {
    return new NumericDisplay(
        "percent",
        List.of(
            new Unit("percent", "percent", BigDecimal.ONE),
            new Unit("probability", "probability", BigDecimal.valueOf(100))),
        BigDecimal.ONE,
        Optional.empty(),
        DecimalText.Limits.CONFIGURATION);
  }
}
