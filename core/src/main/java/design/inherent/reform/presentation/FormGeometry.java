package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Measured form geometry without widget, renderer, or file ownership. */
public final class FormGeometry {
  public record Size(int width, int height) {
    public Size {
      if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid control extent");
    }
  }

  public record Placement(int x, int y, int width, int height) {}

  public record Columns(int firstWidth, int secondX, int secondWidth) {}

  /** Split only when both measured minima fit; compact views choose their own list/detail mode. */
  public static Optional<Columns> columns(
      int width, int firstMinimum, int secondMinimum, int preferredFirst, int gap) {
    if (width < 1 || firstMinimum < 1 || secondMinimum < 1 || preferredFirst < 1 || gap < 0)
      throw new IllegalArgumentException("Invalid column bounds");
    if ((long) firstMinimum + secondMinimum + gap > width) return Optional.empty();
    int first = Math.clamp(preferredFirst, firstMinimum, width - gap - secondMinimum);
    return Optional.of(new Columns(first, first + gap, width - first - gap));
  }

  public record Controls(List<Placement> placements, int height) {
    public Controls {
      placements = List.copyOf(placements);
    }
  }

  public static boolean pair(
      String group, int firstMinimum, String nextGroup, int secondMinimum, int available, int gap) {
    return group != null
        && !group.isBlank()
        && group.equals(nextGroup)
        && firstMinimum > 0
        && secondMinimum > 0
        && 2L * Math.max(firstMinimum, secondMinimum) + gap <= available;
  }

  public static Controls controls(List<Size> sizes, int width, int gap) {
    if (width < 1 || gap < 0) throw new IllegalArgumentException("Invalid form bounds");
    var positions = new ArrayList<Placement>();
    int x = 0, y = 0, lineHeight = 0;
    for (var size : sizes) {
      int controlWidth = Math.min(width, size.width());
      if (x > 0 && (long) x + controlWidth > width) {
        y += lineHeight + gap;
        x = 0;
        lineHeight = 0;
      }
      positions.add(new Placement(x, y, controlWidth, size.height()));
      x += controlWidth + gap;
      lineHeight = Math.max(lineHeight, size.height());
    }
    return new Controls(positions, y + lineHeight);
  }

  private FormGeometry() {}
}
