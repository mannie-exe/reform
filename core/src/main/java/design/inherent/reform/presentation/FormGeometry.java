package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Measured form geometry without widget, renderer, or file ownership. */
public final class FormGeometry {
  public record Size(int width, int height) {
    public Size {
      if (width < 1 || height < 1) throw new IllegalArgumentException("Invalid control extent");
    }
  }

  public record Placement(int x, int y, int width, int height) {
    public boolean contains(Placement child) {
      return width > 0
          && height > 0
          && child.width > 0
          && child.height > 0
          && child.x >= x
          && child.y >= y
          && (long) child.x + child.width <= (long) x + width
          && (long) child.y + child.height <= (long) y + height;
    }
  }

  /** Outer regions never overlap. Overflowing chrome becomes explicit scrolling content. */
  public record ScreenFrame(
      Placement bounds,
      Placement content,
      Optional<Placement> title,
      Optional<Placement> context,
      Optional<Placement> status,
      Optional<Placement> actions,
      Controls actionFlow,
      boolean headerInContent,
      boolean statusInContent,
      boolean actionsInContent) {
    public ScreenFrame {
      Objects.requireNonNull(bounds);
      Objects.requireNonNull(content);
      Objects.requireNonNull(title);
      Objects.requireNonNull(context);
      Objects.requireNonNull(status);
      Objects.requireNonNull(actions);
      Objects.requireNonNull(actionFlow);
      var regions = new ArrayList<Placement>();
      regions.add(content);
      title.ifPresent(regions::add);
      context.ifPresent(regions::add);
      status.ifPresent(regions::add);
      actions.ifPresent(regions::add);
      for (int i = 0; i < regions.size(); i++) {
        var a = regions.get(i);
        if (!bounds.contains(a))
          throw new IllegalArgumentException("Screen region exceeds its host");
        for (int j = i + 1; j < regions.size(); j++) {
          var b = regions.get(j);
          if (a.x < (long) b.x + b.width
              && b.x < (long) a.x + a.width
              && a.y < (long) b.y + b.height
              && b.y < (long) a.y + a.height)
            throw new IllegalArgumentException("Screen regions overlap");
        }
      }
    }
  }

  /**
   * Measure header text first; do not infer its height from string length or a screen breakpoint.
   */
  public static ScreenFrame screenFrame(
      int width,
      int height,
      int titleHeight,
      int contextHeight,
      int statusHeight,
      List<Size> actions) {
    return screenFrame(
        width, height, titleHeight, contextHeight, statusHeight, actions, ThemeValues.standard());
  }

  public static ScreenFrame screenFrame(
      int width,
      int height,
      int titleHeight,
      int contextHeight,
      int statusHeight,
      List<Size> actions,
      ThemeValues theme) {
    Objects.requireNonNull(theme);
    if (width < 1 || height < 1 || titleHeight < 0 || contextHeight < 0 || statusHeight < 0)
      throw new IllegalArgumentException("Invalid screen measurements");
    int gutter =
        Math.min(theme.value(ThemeValues.Role.SPACE_SCREEN), (Math.min(width, height) - 1) / 2);
    int available = width - 2 * gutter, bottom = height - gutter;
    int minimum =
        Math.min(theme.value(ThemeValues.Role.MEASURE_CONTENT_MINIMUM_HEIGHT), height - 2 * gutter);
    int contentGap = theme.value(ThemeValues.Role.SPACE_CONTENT);
    var flow = controls(actions, available, theme.value(ThemeValues.Role.SPACE_INLINE));
    boolean flowingActions =
        !actions.isEmpty() && (long) flow.height() + contentGap + minimum > bottom - gutter;
    var actionBounds =
        actions.isEmpty() || flowingActions
            ? Optional.<Placement>empty()
            : Optional.of(new Placement(gutter, bottom - flow.height(), available, flow.height()));
    if (actionBounds.isPresent()) bottom = actionBounds.get().y() - contentGap;
    boolean flowingStatus =
        statusHeight > 0 && (long) statusHeight + contentGap + minimum > bottom - gutter;
    var statusBounds =
        statusHeight == 0 || flowingStatus
            ? Optional.<Placement>empty()
            : Optional.of(new Placement(gutter, bottom - statusHeight, available, statusHeight));
    if (statusBounds.isPresent()) bottom = statusBounds.get().y() - contentGap;
    long header = (long) titleHeight + contextHeight;
    boolean flowingHeader = header > 0 && header + contentGap + minimum > bottom - gutter;
    int top = gutter;
    Optional<Placement> title = Optional.empty(), context = Optional.empty();
    if (!flowingHeader) {
      if (titleHeight > 0) {
        title = Optional.of(new Placement(gutter, top, available, titleHeight));
        top += titleHeight;
      }
      if (contextHeight > 0) {
        context = Optional.of(new Placement(gutter, top, available, contextHeight));
        top += contextHeight;
      }
      if (header > 0) top += contentGap;
    }
    return new ScreenFrame(
        new Placement(0, 0, width, height),
        new Placement(gutter, top, available, bottom - top),
        title,
        context,
        statusBounds,
        actionBounds,
        flow,
        flowingHeader,
        flowingStatus,
        flowingActions);
  }

  public record Columns(int firstWidth, int secondX, int secondWidth) {}

  /** Center an intrinsic content offer within its parent; narrow parents keep their full width. */
  public static Placement centeredWidth(Placement parent, int preferredWidth) {
    Objects.requireNonNull(parent);
    if (parent.width() < 1 || parent.height() < 1 || preferredWidth < 1)
      throw new IllegalArgumentException("Invalid content-width offer");
    int width = Math.min(parent.width(), preferredWidth);
    return new Placement(
        Math.addExact(parent.x(), (parent.width() - width) / 2),
        parent.y(),
        width,
        parent.height());
  }

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

  /** Content constraints decide inline versus stacked; an absent label consumes no track. */
  public record FieldTracks(int textWidth, int controlsX, boolean stacked, Controls controls) {}

  public static FieldTracks fieldTracks(int width, int textMinimum, List<Size> controls, int gap) {
    return fieldTracks(width, textMinimum, controls, gap, Set.of());
  }

  public static FieldTracks fieldTracks(
      int width, int textMinimum, List<Size> controls, int gap, Set<Integer> joinedAfter) {
    return fieldTracks(width, textMinimum, controls, gap, joinedAfter, FormTokens.Space.INLINE);
  }

  public static FieldTracks fieldTracks(
      int width,
      int textMinimum,
      List<Size> controls,
      int gap,
      Set<Integer> joinedAfter,
      int inlineGap) {
    if (width < 1 || textMinimum < 0 || gap < 0 || inlineGap < 0)
      throw new IllegalArgumentException("Invalid field measurements");
    long preferred =
        controls.stream().mapToLong(Size::width).sum()
            + Math.max(0L, controls.size() - 1L) * inlineGap;
    int controlMinimum = minimumControlWidth(controls, inlineGap, joinedAfter);
    boolean stacked =
        textMinimum > 0 && !controls.isEmpty() && textMinimum + (long) gap + controlMinimum > width;
    int start =
        textMinimum == 0 || controls.isEmpty() || stacked
            ? 0
            : width - (int) Math.min(preferred, width - (long) textMinimum - gap);
    int text =
        textMinimum == 0 ? 0 : stacked || controls.isEmpty() ? width : Math.max(1, start - gap);
    return new FieldTracks(
        text,
        start,
        stacked,
        controls(controls, Math.max(1, width - start), inlineGap, joinedAfter));
  }

  /** Equal responsive tracks for one authored relationship; no inferred adjacency grouping. */
  public static List<Placement> relatedTracks(List<Integer> minima, int width, int gap) {
    if (width < 1
        || gap < 0
        || minima.isEmpty()
        || minima.size() > 3
        || minima.stream().anyMatch(value -> value == null || value < 1))
      throw new IllegalArgumentException("Invalid related field measurements");
    int count = minima.size();
    int largest = minima.stream().mapToInt(Integer::intValue).max().orElseThrow();
    if ((long) largest * count + (count - 1L) * gap > width) return List.of();
    int content = width - (count - 1) * gap;
    var tracks = new ArrayList<Placement>();
    for (int index = 0; index < count; index++) {
      int start = (int) ((long) content * index / count);
      int end = (int) ((long) content * (index + 1) / count);
      tracks.add(new Placement(start + index * gap, 0, end - start, 1));
    }
    return List.copyOf(tracks);
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
    return controls(sizes, width, gap, Set.of());
  }

  /** Join explicit adjacent controls when their complete cluster fits the available line. */
  public static Controls controls(List<Size> sizes, int width, int gap, Set<Integer> joinedAfter) {
    if (width < 1 || gap < 0) throw new IllegalArgumentException("Invalid form bounds");
    joinedAfter = joins(sizes, joinedAfter);
    var positions = new ArrayList<Placement>();
    long x = 0, y = 0;
    int lineHeight = 0;
    for (int first = 0; first < sizes.size(); ) {
      int end = clusterEnd(first, sizes.size(), joinedAfter);
      long cluster = (end - first - 1L) * gap;
      for (int i = first; i < end; i++) cluster += Math.min(width, sizes.get(i).width());
      if (cluster <= width && x > 0 && x + cluster > width) {
        y += (long) lineHeight + gap;
        x = 0;
        lineHeight = 0;
      }
      for (int i = first; i < end; i++) {
        var size = sizes.get(i);
        int controlWidth = Math.min(width, size.width());
        if (x > 0 && x + controlWidth > width) {
          y += (long) lineHeight + gap;
          x = 0;
          lineHeight = 0;
        }
        positions.add(
            new Placement(Math.toIntExact(x), Math.toIntExact(y), controlWidth, size.height()));
        x += (long) controlWidth + gap;
        lineHeight = Math.max(lineHeight, size.height());
      }
      first = end;
    }
    return new Controls(positions, Math.toIntExact(y + lineHeight));
  }

  public static int minimumControlWidth(List<Size> sizes, int gap, Set<Integer> joinedAfter) {
    if (gap < 0) throw new IllegalArgumentException("Invalid control gap");
    joinedAfter = joins(sizes, joinedAfter);
    long maximum = 0;
    for (int first = 0; first < sizes.size(); ) {
      int end = clusterEnd(first, sizes.size(), joinedAfter);
      long width = (end - first - 1L) * gap;
      for (int i = first; i < end; i++) width += sizes.get(i).width();
      maximum = Math.max(maximum, width);
      first = end;
    }
    return (int) Math.min(Integer.MAX_VALUE, maximum);
  }

  private static Set<Integer> joins(List<Size> sizes, Set<Integer> joinedAfter) {
    var copy = Set.copyOf(joinedAfter);
    if (copy.stream().anyMatch(index -> index < 0 || index >= sizes.size() - 1))
      throw new IllegalArgumentException("A control join requires two adjacent controls");
    return copy;
  }

  private static int clusterEnd(int first, int size, Set<Integer> joinedAfter) {
    int end = first + 1;
    while (end < size && joinedAfter.contains(end - 1)) end++;
    return end;
  }

  private FormGeometry() {}
}
