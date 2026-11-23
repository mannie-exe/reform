package design.inherent.reform.minecraft.client;

import java.util.*;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;

/** Authored rows of semantic header slots. Slot order is also their reading and focus order. */
public record HeaderComposition(List<Row> rows, Optional<Canvas> canvas) {
  public enum Slot {
    IDENTITY,
    SUBTITLE,
    LOCATION,
    CANVAS
  }

  public record Row(List<Slot> slots) {
    public Row {
      slots = List.copyOf(slots);
      if (slots.isEmpty() || slots.size() > 4)
        throw new IllegalArgumentException("Header rows need one to four slots");
    }
  }

  public record Canvas(
      Component description,
      int minimumWidth,
      int preferredWidth,
      int height,
      ClientHeaderCanvas.Factory create) {
    /** Convenience for cached canvases with no partially constructed owned resources. */
    public Canvas(
        Component description,
        int minimumWidth,
        int preferredWidth,
        int height,
        Supplier<ClientHeaderCanvas> create) {
      this(description, minimumWidth, preferredWidth, height, factory(create));
    }

    private static ClientHeaderCanvas.Factory factory(Supplier<ClientHeaderCanvas> create) {
      Objects.requireNonNull(create);
      return ignored -> create.get();
    }

    public Canvas {
      description = Objects.requireNonNull(description).copy();
      Objects.requireNonNull(create);
      if (description.getString().isBlank()
          || description.getString().length() > 1024
          || minimumWidth < 1
          || preferredWidth < minimumWidth
          || height < 1) throw new IllegalArgumentException("Invalid header canvas offer");
    }

    @Override
    public Component description() {
      return description.copy();
    }
  }

  public HeaderComposition {
    rows = List.copyOf(rows);
    Objects.requireNonNull(canvas);
    if (rows.isEmpty() || rows.size() > 4)
      throw new IllegalArgumentException("Header composition is bounded to four rows");
    var slots = new HashSet<Slot>();
    for (var row : rows)
      for (var slot : row.slots())
        if (!slots.add(slot)) throw new IllegalArgumentException("Duplicate header slot");
    if (!slots.contains(Slot.IDENTITY)
        || !slots.contains(Slot.LOCATION)
        || slots.contains(Slot.CANVAS) != canvas.isPresent())
      throw new IllegalArgumentException(
          "Identity and one location are required; canvas needs its actual offer");
  }

  public static HeaderComposition standard() {
    return new HeaderComposition(
        List.of(new Row(List.of(Slot.IDENTITY, Slot.LOCATION)), new Row(List.of(Slot.SUBTITLE))),
        Optional.empty());
  }
}
