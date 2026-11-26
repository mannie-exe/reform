package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Finite intrinsic width allocation. Layout has no input, source, effect, or native ownership. */
public final class UiLayout {
  public static final int LIMIT = 1_000_000;

  public enum Alignment {
    START,
    CENTER,
    END
  }

  public record Offer(int minimum, int preferred, int maximum, int grow, int shrink) {
    public Offer {
      if (minimum < 1
          || minimum > preferred
          || preferred > maximum
          || maximum > LIMIT
          || grow < 0
          || grow > 1000
          || shrink < 0
          || shrink > 1000)
        throw new IllegalArgumentException(
            "Use finite ordered layout offers and nonnegative weights");
    }

    public static Offer flexible(int minimum, int preferred) {
      return new Offer(minimum, preferred, LIMIT, 1, 1);
    }

    public static Offer fixed(int width) {
      return new Offer(width, width, width, 0, 0);
    }
  }

  public record Row(int minimumGap, int maximumGap) {
    public Row {
      if (minimumGap < 0 || minimumGap > maximumGap || maximumGap > 1024)
        throw new IllegalArgumentException("Use bounded ordered layout gaps");
    }
  }

  public record Frame(Offer width, int padding, Alignment alignment) {
    public Frame {
      Objects.requireNonNull(width);
      Objects.requireNonNull(alignment);
      if (padding < 0 || padding > 1024)
        throw new IllegalArgumentException("Use bounded nonnegative frame padding");
    }
  }

  /** Widths remain in authored order. Stacked children receive individual vertical placements. */
  public record Allocation(List<Integer> widths, int gap, boolean stacked, boolean minimumUnmet) {
    public Allocation {
      widths = List.copyOf(widths);
    }
  }

  public record Framed(
      int x, int width, int contentX, int contentWidth, int padding, boolean minimumUnmet) {}

  public static Framed frame(Frame spec, int available) {
    finite(available);
    int width = Math.min(available, spec.width().maximum());
    if (spec.width().grow() == 0) width = Math.min(width, spec.width().preferred());
    int x =
        switch (spec.alignment()) {
          case START -> 0;
          case CENTER -> (available - width) / 2;
          case END -> available - width;
        };
    int padding = Math.min(spec.padding(), (width - 1) / 2);
    return new Framed(
        x,
        width,
        x + padding,
        width - padding * 2,
        padding,
        width < spec.width().minimum() || padding != spec.padding());
  }

  public static Allocation row(Row spec, List<Offer> offers, int available) {
    finite(available);
    Objects.requireNonNull(spec);
    Objects.requireNonNull(offers);
    if (offers.isEmpty() || offers.size() > 64)
      throw new IllegalArgumentException("A row needs one to 64 finite children");
    offers = List.copyOf(offers);
    long gaps = (long) spec.minimumGap() * (offers.size() - 1);
    long minimum = gaps;
    for (var offer : offers) minimum += offer.minimum();
    if (minimum > available) {
      var widths = new ArrayList<Integer>();
      boolean unmet = false;
      for (var offer : offers) {
        widths.add(Math.min(available, offer.maximum()));
        unmet |= offer.minimum() > available;
      }
      return new Allocation(widths, spec.minimumGap(), true, unmet);
    }
    int usable = available - (int) gaps;
    int[] widths = new int[offers.size()];
    long total = 0;
    for (int i = 0; i < widths.length; i++) total += widths[i] = offers.get(i).preferred();
    boolean grow = total < usable;
    long remaining = Math.abs(usable - total);
    // Each incomplete pass saturates a bound; a complete nonsaturating pass consumes the delta.
    for (int pass = 0; remaining > 0 && pass <= widths.length; pass++) {
      long weights = 0;
      for (int i = 0; i < widths.length; i++) {
        var offer = offers.get(i);
        if (grow ? widths[i] < offer.maximum() : widths[i] > offer.minimum())
          weights += grow ? offer.grow() : offer.shrink();
      }
      if (weights == 0) break;
      long budget = remaining, moved = 0;
      for (int i = 0; i < widths.length; i++) {
        var offer = offers.get(i);
        int capacity = grow ? offer.maximum() - widths[i] : widths[i] - offer.minimum();
        int weight = grow ? offer.grow() : offer.shrink();
        long share = Math.min(capacity, budget * weight / weights);
        widths[i] += grow ? (int) share : -(int) share;
        moved += share;
      }
      remaining -= moved;
      // Integral rounding residue is bounded by child count, never by viewport pixels.
      for (int i = 0; i < widths.length && remaining > 0; i++) {
        var offer = offers.get(i);
        if ((grow ? offer.grow() : offer.shrink()) > 0
            && (grow ? widths[i] < offer.maximum() : widths[i] > offer.minimum())) {
          widths[i] += grow ? 1 : -1;
          remaining--;
          moved++;
        }
      }
      if (moved == 0) break;
    }
    if (!grow && remaining > 0)
      return new Allocation(
          offers.stream().map(o -> Math.min(available, o.maximum())).toList(),
          spec.minimumGap(),
          true,
          false);
    var result = new ArrayList<Integer>();
    int used = 0;
    for (int width : widths) {
      result.add(width);
      used += width;
    }
    int gap =
        widths.length == 1
            ? 0
            : Math.min(
                spec.maximumGap(),
                spec.minimumGap()
                    + Math.max(0, available - used - (int) gaps) / (widths.length - 1));
    return new Allocation(result, gap, false, false);
  }

  private static void finite(int value) {
    if (value < 1 || value > LIMIT)
      throw new IllegalArgumentException("Use a finite positive layout proposal");
  }

  private UiLayout() {}
}
