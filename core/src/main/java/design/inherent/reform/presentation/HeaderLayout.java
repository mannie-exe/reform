package design.inherent.reform.presentation;

import java.util.*;
import java.util.function.IntUnaryOperator;

/** One measured authored slot flow; a row that does not fit stacks as a complete relationship. */
public final class HeaderLayout {
  public record Offer(int minimumWidth, int preferredWidth, IntUnaryOperator height) {
    public Offer {
      Objects.requireNonNull(height);
      if (minimumWidth < 1 || preferredWidth < minimumWidth)
        throw new IllegalArgumentException("Invalid header offer");
    }
  }

  public record Layout<K>(Map<K, FormGeometry.Placement> slots, int height) {
    public Layout {
      slots = Collections.unmodifiableMap(new LinkedHashMap<>(slots));
      if (height < 1) throw new IllegalArgumentException("Empty header");
    }
  }

  public static <K> Layout<K> measure(
      List<List<K>> rows, Map<K, Offer> offers, int width, int gap) {
    if (width < 1 || gap < 0 || rows.isEmpty() || rows.size() > 4)
      throw new IllegalArgumentException("Invalid header bounds");
    var placed = new LinkedHashMap<K, FormGeometry.Placement>();
    int y = 0;
    for (var declared : rows) {
      var row = declared.stream().filter(offers::containsKey).toList();
      if (row.isEmpty()) continue;
      long minimum = (long) (row.size() - 1) * gap;
      for (var key : row) minimum += offers.get(key).minimumWidth();
      if (minimum > width) {
        for (var key : row) {
          if (!placed.isEmpty()) y = Math.addExact(y, gap);
          int height = offers.get(key).height().applyAsInt(width);
          if (height < 1) throw new IllegalArgumentException("Invalid measured header height");
          if (placed.putIfAbsent(key, new FormGeometry.Placement(0, y, width, height)) != null)
            throw new IllegalArgumentException("Duplicate header slot");
          y = Math.addExact(y, height);
        }
      } else {
        if (!placed.isEmpty()) y = Math.addExact(y, gap);
        int x = 0, remaining = (int) (width - minimum), height = 0;
        for (int i = 0; i < row.size(); i++) {
          var key = row.get(i);
          var offer = offers.get(key);
          int extra =
              i == row.size() - 1
                  ? remaining
                  : Math.min(remaining, offer.preferredWidth() - offer.minimumWidth());
          int w = offer.minimumWidth() + extra;
          remaining -= extra;
          int h = offer.height().applyAsInt(w);
          if (h < 1) throw new IllegalArgumentException("Invalid measured header height");
          if (placed.putIfAbsent(key, new FormGeometry.Placement(x, y, w, h)) != null)
            throw new IllegalArgumentException("Duplicate header slot");
          height = Math.max(height, h);
          if (i + 1 < row.size()) x = Math.addExact(x, Math.addExact(w, gap));
        }
        y = Math.addExact(y, height);
      }
    }
    return new Layout<>(placed, y);
  }

  /** Stable history slots followed by a path offer, independent of the current path's text. */
  public static FormGeometry.Controls location(
      int width, int height, int historyWidth, int pathMinimum, int gap) {
    if (height < 1 || historyWidth < 1 || pathMinimum < 1)
      throw new IllegalArgumentException("Invalid location offer");
    long history = 2L * historyWidth + 2L * gap;
    int pathWidth = history + pathMinimum <= width ? (int) (width - history) : width;
    return FormGeometry.controls(
        List.of(
            new FormGeometry.Size(historyWidth, height),
            new FormGeometry.Size(historyWidth, height),
            new FormGeometry.Size(pathWidth, height)),
        width,
        gap,
        Set.of(0));
  }

  private HeaderLayout() {}
}
