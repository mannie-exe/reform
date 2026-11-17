package design.inherent.reform.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Structural locations retain field/row/branch identity independently of indices and labels. */
public record FieldPath(List<Segment> segments) {
  public static final FieldPath ROOT = new FieldPath(List.of());

  public sealed interface Segment permits Field, Row, Variant, Part {}

  public record Field(String id) implements Segment {
    public Field {
      requireName(id);
    }
  }

  public record Row(UUID id) implements Segment {
    public Row {
      Objects.requireNonNull(id);
    }
  }

  public record Variant(String id) implements Segment {
    public Variant {
      requireName(id);
    }
  }

  public enum Part implements Segment {
    KEY,
    VALUE
  }

  public FieldPath {
    segments = List.copyOf(segments);
  }

  public FieldPath append(Segment segment) {
    var copy = new ArrayList<>(segments);
    copy.add(Objects.requireNonNull(segment));
    return new FieldPath(copy);
  }

  public FieldPath field(String id) {
    return append(new Field(id));
  }

  public FieldPath row(UUID id) {
    return append(new Row(id));
  }

  public FieldPath variant(String id) {
    return append(new Variant(id));
  }

  public FieldPath resolve(FieldPath relative) {
    var copy = new ArrayList<>(segments);
    copy.addAll(relative.segments);
    return new FieldPath(copy);
  }

  public boolean startsWith(FieldPath parent) {
    return segments.size() >= parent.segments.size()
        && segments.subList(0, parent.segments.size()).equals(parent.segments);
  }

  static void requireName(String id) {
    if (Objects.requireNonNull(id).isBlank())
      throw new IllegalArgumentException("Missing stable field/variant ID");
  }
}
