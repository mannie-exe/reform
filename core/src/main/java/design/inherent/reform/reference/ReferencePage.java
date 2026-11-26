package design.inherent.reform.reference;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Suggestions and logical candidates examined, without inventing a total number of matches. */
public record ReferencePage<K>(
    ReferenceStamp stamp,
    Availability availability,
    List<Choice<K>> choices,
    Optional<ReferenceQuery.Cursor> next,
    int examined,
    boolean exhausted,
    String reason) {
  public enum Availability {
    READY,
    UNSUPPORTED,
    MISSING_DEPENDENCY,
    UNAVAILABLE_CONTEXT
  }

  public record Choice<K>(K key, String label, Map<String, String> preview) {
    public Choice {
      Objects.requireNonNull(key);
      Objects.requireNonNull(label);
      preview = Map.copyOf(preview);
    }
  }

  public ReferencePage {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(availability);
    choices = List.copyOf(choices);
    Objects.requireNonNull(next);
    Objects.requireNonNull(reason);
    if (examined < 0 || examined < choices.size())
      throw new IllegalArgumentException("Invalid reference accounting");
    if (availability != Availability.READY
        && (!choices.isEmpty() || next.isPresent() || examined != 0 || exhausted))
      throw new IllegalArgumentException("Unavailable reference pages carry no partial choices");
    if (availability == Availability.READY && exhausted == next.isPresent())
      throw new IllegalArgumentException("Ready pages distinguish exhaustion from continuation");
  }

  public static <K> ReferencePage<K> unavailable(
      ReferenceStamp stamp, Availability availability, String reason) {
    if (availability == Availability.READY)
      throw new IllegalArgumentException("Use a ready page for available data");
    return new ReferencePage<>(stamp, availability, List.of(), Optional.empty(), 0, false, reason);
  }
}
