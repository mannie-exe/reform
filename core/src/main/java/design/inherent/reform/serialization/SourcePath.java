package design.inherent.reform.serialization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Encoded source positions, separate from session row identities and filesystem paths. */
public final class SourcePath {
  public sealed interface Segment permits Property, Key, Index {}

  /** Object property name, distinct from the value stored at that property. */
  public record Key(String name) implements Segment {
    public Key {
      Objects.requireNonNull(name);
    }
  }

  public record Property(String name) implements Segment {
    public Property {
      Objects.requireNonNull(name);
    }
  }

  public record Index(int index) implements Segment {
    public Index {
      if (index < 0) throw new IllegalArgumentException("Negative source index");
    }
  }

  public static final SourcePath ROOT = new SourcePath();

  private final SourcePath parent;
  private final Segment last;
  private final int depth;
  private final int hash;

  private SourcePath() {
    parent = null;
    last = null;
    depth = 0;
    hash = 1;
  }

  private SourcePath(SourcePath parent, Segment last) {
    this.parent = parent;
    this.last = last;
    depth = parent.depth + 1;
    hash = 31 * parent.hash + last.hashCode();
  }

  public SourcePath property(String name) {
    return new SourcePath(this, new Property(name));
  }

  public SourcePath key(String name) {
    return new SourcePath(this, new Key(name));
  }

  public SourcePath index(int index) {
    return new SourcePath(this, new Index(index));
  }

  public List<Segment> segments() {
    var result = new ArrayList<Segment>(depth);
    for (var current = this; current.depth > 0; current = current.parent) result.add(current.last);
    Collections.reverse(result);
    return List.copyOf(result);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof SourcePath path) || path.depth != depth || path.hash != hash)
      return false;
    for (SourcePath a = this, b = path; a.depth > 0; a = a.parent, b = b.parent) {
      if (!a.last.equals(b.last)) return false;
    }
    return true;
  }

  @Override
  public String toString() {
    var result = new StringBuilder();
    for (var segment : segments()) {
      result.append('/');
      if (segment instanceof Property property)
        result.append(property.name().replace("~", "~0").replace("/", "~1"));
      else if (segment instanceof Key key)
        result.append("~k").append(key.name().replace("~", "~0").replace("/", "~1"));
      else result.append(((Index) segment).index());
    }
    return result.toString();
  }
}
