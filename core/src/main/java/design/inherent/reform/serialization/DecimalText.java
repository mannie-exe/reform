package design.inherent.reform.serialization;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Interactive decimal text, separate from JSON kind admission and the owner's numeric codec. */
public final class DecimalText {
  private static final Pattern INCOMPLETE =
      Pattern.compile("[+-]?\\.?|[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)[eE][+-]?");

  public record Limits(int maximumCharacters, int maximumAbsoluteScale) {
    public static final Limits CONFIGURATION = new Limits(128, 4096);

    public Limits {
      if (maximumCharacters < 1
          || maximumCharacters > 32768
          || maximumAbsoluteScale < 0
          || maximumAbsoluteScale > 4096)
        throw new IllegalArgumentException("Decimal text limits exceed the bounded editor range");
    }

    public void check(BigDecimal value) {
      if (value.precision() > maximumCharacters
          || Math.abs((long) value.scale()) > maximumAbsoluteScale
          || value.toString().length() > maximumCharacters)
        throw new IllegalArgumentException("Decimal exceeds its precision/scale/text budget");
    }

    public BigDecimal parse(String raw) {
      if (raw.length() > maximumCharacters)
        throw new IllegalArgumentException("Decimal input exceeds its text budget");
      var value = new BigDecimal(raw.strip());
      check(value);
      return value;
    }
  }

  /**
   * Receives stripped text, matching the existing raw-draft grammar. Empty is checked separately.
   */
  public static boolean incomplete(String stripped) {
    return INCOMPLETE.matcher(stripped).matches();
  }

  private DecimalText() {}
}
