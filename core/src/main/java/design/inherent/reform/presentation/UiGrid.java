package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded explicit tracks and spans. Placement never changes authored reading order. */
public final class UiGrid {
  public enum Kind {
    FIXED,
    CONTENT,
    FRACTION
  }

  public record Track(Kind kind, UiLayout.Offer width) {
    public Track {
      Objects.requireNonNull(kind);
      Objects.requireNonNull(width);
      if (kind == Kind.FIXED
              && (width.minimum() != width.maximum() || width.grow() != 0 || width.shrink() != 0)
          || kind == Kind.CONTENT && width.grow() != 0
          || kind == Kind.FRACTION && width.grow() < 1)
        throw new IllegalArgumentException("Track weights must match their declared kind");
    }

    public static Track fixed(int width) {
      return new Track(Kind.FIXED, UiLayout.Offer.fixed(width));
    }

    public static Track content(int minimum, int maximum) {
      return new Track(Kind.CONTENT, new UiLayout.Offer(minimum, minimum, maximum, 0, 1));
    }

    public static Track fraction(int minimum, int preferred, int maximum, int weight) {
      return new Track(Kind.FRACTION, new UiLayout.Offer(minimum, preferred, maximum, weight, 1));
    }
  }

  public record Grid(List<Track> columns, int rows, int columnGap, int rowGap) {
    public Grid {
      Objects.requireNonNull(columns);
      if (columns.isEmpty()
          || columns.size() > 16
          || rows < 1
          || rows > 16
          || columnGap < 0
          || columnGap > 1024
          || rowGap < 0
          || rowGap > 1024)
        throw new IllegalArgumentException(
            "A grid needs one to 16 tracks per axis and bounded gaps");
      columns = List.copyOf(columns);
    }
  }

  public record Cell(int row, int column, int rowSpan, int columnSpan) {
    public Cell {
      if (row < 0
          || row >= 16
          || column < 0
          || column >= 16
          || rowSpan < 1
          || rowSpan > 16
          || columnSpan < 1
          || columnSpan > 16)
        throw new IllegalArgumentException(
            "Use bounded nonnegative grid coordinates and positive spans");
    }

    public Cell(int row, int column) {
      this(row, column, 1, 1);
    }
  }

  public record Placement(int x, int width, int row, int rowSpan) {}

  public record Plan(Grid grid, List<Placement> placements, boolean stacked, boolean minimumUnmet) {
    public Plan {
      placements = List.copyOf(placements);
    }
  }

  public record Vertical(List<Integer> tops, int height) {
    public Vertical {
      tops = List.copyOf(tops);
    }
  }

  public static void validate(Grid grid, List<Cell> cells) {
    Objects.requireNonNull(grid);
    Objects.requireNonNull(cells);
    if (cells.isEmpty() || cells.size() > 64)
      throw new IllegalArgumentException("A grid needs one to 64 cells");
    boolean[][] occupied = new boolean[grid.rows()][grid.columns().size()];
    for (var cell : cells) {
      Objects.requireNonNull(cell);
      if (cell.row() + cell.rowSpan() > grid.rows()
          || cell.column() + cell.columnSpan() > grid.columns().size())
        throw new IllegalArgumentException("A cell span exceeds the declared grid");
      for (int row = cell.row(); row < cell.row() + cell.rowSpan(); row++)
        for (int col = cell.column(); col < cell.column() + cell.columnSpan(); col++) {
          if (occupied[row][col]) throw new IllegalArgumentException("Grid cells must not overlap");
          occupied[row][col] = true;
        }
    }
  }

  public static Plan plan(Grid grid, List<Cell> cells, List<UiLayout.Offer> content, int width) {
    validate(grid, cells);
    Objects.requireNonNull(content);
    if (content.size() != cells.size())
      throw new IllegalArgumentException("Each grid cell needs one measured offer");
    if (width < 1 || width > UiLayout.LIMIT)
      throw new IllegalArgumentException("Use a finite positive grid width");
    int count = grid.columns().size();
    int[] minima = new int[count], preferred = new int[count];
    for (int i = 0; i < count; i++) {
      minima[i] = grid.columns().get(i).width().minimum();
      preferred[i] = grid.columns().get(i).width().preferred();
    }
    boolean unmet = false;
    for (int i = 0; i < cells.size(); i++) {
      var cell = cells.get(i);
      unmet |= !satisfy(grid, cell, minima, content.get(i).minimum());
    }
    for (int i = 0; i < count; i++) preferred[i] = Math.max(minima[i], preferred[i]);
    for (int i = 0; i < cells.size(); i++)
      satisfy(grid, cells.get(i), preferred, content.get(i).preferred());
    var offers = new ArrayList<UiLayout.Offer>();
    for (int i = 0; i < count; i++) {
      var offer = grid.columns().get(i).width();
      offers.add(
          new UiLayout.Offer(
              minima[i], preferred[i], offer.maximum(), offer.grow(), offer.shrink()));
    }
    var tracks = UiLayout.row(new UiLayout.Row(grid.columnGap(), grid.columnGap()), offers, width);
    var placements = new ArrayList<Placement>();
    if (unmet || tracks.stacked()) {
      for (int i = 0; i < cells.size(); i++)
        placements.add(new Placement(0, Math.min(width, content.get(i).maximum()), i, 1));
      return new Plan(grid, placements, true, unmet || tracks.minimumUnmet());
    }
    for (var cell : cells) {
      int x = 0, span = grid.columnGap() * (cell.columnSpan() - 1);
      for (int i = 0; i < cell.column(); i++) x += tracks.widths().get(i) + grid.columnGap();
      for (int i = cell.column(); i < cell.column() + cell.columnSpan(); i++)
        span += tracks.widths().get(i);
      placements.add(new Placement(x, span, cell.row(), cell.rowSpan()));
    }
    return new Plan(grid, placements, false, false);
  }

  // Every pass saturates a flexible track or finishes. Fixed tracks cannot consume intrinsic
  // deficits.
  private static boolean satisfy(Grid grid, Cell cell, int[] sizes, int required) {
    int total = grid.columnGap() * (cell.columnSpan() - 1);
    for (int i = cell.column(); i < cell.column() + cell.columnSpan(); i++) total += sizes[i];
    int remaining = Math.max(0, required - total);
    for (int pass = 0; remaining > 0 && pass <= cell.columnSpan(); pass++) {
      int eligible = 0;
      for (int i = cell.column(); i < cell.column() + cell.columnSpan(); i++)
        if (grid.columns().get(i).kind() != Kind.FIXED
            && sizes[i] < grid.columns().get(i).width().maximum()) eligible++;
      if (eligible == 0) return false;
      int share = (remaining + eligible - 1) / eligible;
      for (int i = cell.column(); i < cell.column() + cell.columnSpan() && remaining > 0; i++) {
        var track = grid.columns().get(i);
        if (track.kind() == Kind.FIXED) continue;
        int moved = Math.min(remaining, Math.min(share, track.width().maximum() - sizes[i]));
        sizes[i] += moved;
        remaining -= moved;
      }
    }
    return remaining == 0;
  }

  /**
   * Content determines row heights; spans contribute their deficit without clipping child content.
   */
  public static Vertical arrange(Plan plan, List<Integer> heights) {
    Objects.requireNonNull(heights);
    if (heights.size() != plan.placements().size())
      throw new IllegalArgumentException("Each cell needs one measured height");
    for (int h : heights)
      if (h < 0 || h > UiLayout.LIMIT)
        throw new IllegalArgumentException("Use finite nonnegative cell heights");
    var tops = new ArrayList<Integer>();
    if (plan.stacked()) {
      int y = 0;
      for (int height : heights) {
        tops.add(y);
        y = Math.addExact(y, height + plan.grid().rowGap());
      }
      return new Vertical(tops, y - plan.grid().rowGap());
    }
    int[] rows = new int[plan.grid().rows()];
    for (int i = 0; i < heights.size(); i++) {
      var at = plan.placements().get(i);
      int available = plan.grid().rowGap() * (at.rowSpan() - 1);
      for (int row = at.row(); row < at.row() + at.rowSpan(); row++) available += rows[row];
      int deficit = Math.max(0, heights.get(i) - available);
      for (int offset = 0; offset < at.rowSpan(); offset++)
        rows[at.row() + offset] +=
            deficit / at.rowSpan() + (offset < deficit % at.rowSpan() ? 1 : 0);
    }
    int[] y = new int[rows.length + 1];
    for (int i = 0; i < rows.length; i++)
      y[i + 1] = Math.addExact(y[i], rows[i] + plan.grid().rowGap());
    for (var at : plan.placements()) tops.add(y[at.row()]);
    return new Vertical(tops, y[rows.length] - plan.grid().rowGap());
  }

  private UiGrid() {}
}
