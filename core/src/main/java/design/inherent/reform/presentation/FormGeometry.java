package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

  /** Native font/control measurements, not screen-size guesses or consumer storage structure. */
  public record WorkspaceMetrics(
      int navigationMinimum,
      int editorMinimum,
      int informationMinimum,
      int preferredNavigation,
      int preferredInformation,
      int gap) {
    public WorkspaceMetrics {
      if (navigationMinimum < 1
          || editorMinimum < 1
          || informationMinimum < 1
          || preferredNavigation < 1
          || preferredInformation < 1
          || gap < 0) throw new IllegalArgumentException("Invalid workspace measurements");
    }
  }

  /** A compact navigation drawer overlaps the editor explicitly; ordinary columns never do. */
  public record Workspace(
      Optional<Placement> navigation,
      Placement editor,
      Optional<Placement> information,
      boolean compactNavigation,
      boolean navigationOverlay) {
    public Workspace {
      Objects.requireNonNull(navigation);
      Objects.requireNonNull(editor);
      Objects.requireNonNull(information);
      if (navigationOverlay && (!compactNavigation || navigation.isEmpty()))
        throw new IllegalArgumentException("Only an open compact drawer overlays the editor");
    }
  }

  /** Keep the editor available; information moves inline before measured navigation collapses. */
  public static Workspace workspace(
      int width,
      int top,
      int bottom,
      WorkspaceMetrics metrics,
      boolean drawerOpen,
      boolean informationOpen) {
    Objects.requireNonNull(metrics);
    if (width < 1 || top < 0 || bottom <= top)
      throw new IllegalArgumentException("Invalid workspace bounds");
    int height = bottom - top;
    boolean wide =
        (long) metrics.navigationMinimum() + metrics.gap() + metrics.editorMinimum() <= width;
    if (!wide) {
      var navigation =
          drawerOpen
              ? Optional.of(
                  new Placement(
                      0,
                      top,
                      Math.min(
                          width,
                          Math.max(metrics.navigationMinimum(), metrics.preferredNavigation())),
                      height))
              : Optional.<Placement>empty();
      return new Workspace(
          navigation, new Placement(0, top, width, height), Optional.empty(), true, drawerOpen);
    }
    boolean information =
        informationOpen
            && (long) metrics.navigationMinimum()
                    + metrics.editorMinimum()
                    + metrics.informationMinimum()
                    + 2L * metrics.gap()
                <= width;
    int navigation =
        Math.clamp(
            metrics.preferredNavigation(),
            metrics.navigationMinimum(),
            width
                - metrics.gap()
                - metrics.editorMinimum()
                - (information ? metrics.informationMinimum() + metrics.gap() : 0));
    int informationWidth =
        information
            ? Math.clamp(
                metrics.preferredInformation(),
                metrics.informationMinimum(),
                width - navigation - 2 * metrics.gap() - metrics.editorMinimum())
            : 0;
    int editor =
        width - navigation - metrics.gap() - (information ? informationWidth + metrics.gap() : 0);
    return new Workspace(
        Optional.of(new Placement(0, top, navigation, height)),
        new Placement(navigation + metrics.gap(), top, editor, height),
        information
            ? Optional.of(new Placement(width - informationWidth, top, informationWidth, height))
            : Optional.empty(),
        false,
        false);
  }

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
