package design.inherent.reform.reference;

import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable complete index. Producer exhaustion rejects the whole index, never a partial lookup.
 */
public final class ReferenceIndex<K, V> implements ReferenceResolver<K, V> {
  public record Entry<K, V>(
      K key, V value, String label, String searchText, Map<String, String> preview) {
    public Entry {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      Objects.requireNonNull(label);
      Objects.requireNonNull(searchText);
      preview = Map.copyOf(preview);
    }
  }

  private record Row<K, V>(
      K key, V value, String label, String search, String sortKey, Map<String, String> preview) {}

  private final ReferenceStamp stamp;
  private final ReferencePage.Availability availability;
  private final String reason;
  private final ValueType<K> keys;
  private final SnapshotPolicy<V> values;
  private final ReferenceLimits limits;
  private final SnapshotContext.Limits snapshots;
  private final List<Row<K, V>> rows;
  private final Map<K, Row<K, V>> byKey;
  private final boolean prefixKeys;
  private final List<Row<K, V>> prefixRows;

  public ReferenceIndex(
      ReferenceStamp stamp,
      ValueType<K> keys,
      SnapshotPolicy<V> values,
      ReferenceLimits limits,
      List<Entry<K, V>> entries) {
    this(
        stamp,
        keys,
        values,
        limits,
        SnapshotContext.Limits.CONFIGURATION,
        ReferencePage.Availability.READY,
        "",
        entries);
  }

  public ReferenceIndex(
      ReferenceStamp stamp,
      ValueType<K> keys,
      SnapshotPolicy<V> values,
      ReferenceLimits limits,
      SnapshotContext.Limits snapshots,
      ReferencePage.Availability availability,
      String reason,
      List<Entry<K, V>> entries) {
    this.stamp = Objects.requireNonNull(stamp);
    this.keys = Objects.requireNonNull(keys);
    this.values = Objects.requireNonNull(values);
    this.limits = Objects.requireNonNull(limits);
    this.snapshots = Objects.requireNonNull(snapshots);
    this.availability = Objects.requireNonNull(availability);
    this.reason = Objects.requireNonNull(reason);
    if (entries.size() > limits.maximumEntries()
        || availability != ReferencePage.Availability.READY && !entries.isEmpty())
      throw new IllegalArgumentException(
          "Reference index must be complete and within its declared size");
    limits.text(reason, Map.of());
    var context = new SnapshotContext(snapshots);
    var copied = new ArrayList<Row<K, V>>(entries.size());
    var table = new HashMap<K, Row<K, V>>();
    var encodedKeys = new HashSet<String>();
    long characters = reason.length();
    for (var entry : entries) {
      K key = keys.freeze(entry.key(), context);
      var encoded = keys.codec().encodeStart(JsonOps.INSTANCE, key);
      if (encoded.error().isPresent() || encoded.result().isEmpty())
        throw new IllegalArgumentException("Reference index key cannot encode completely");
      var decoded = keys.decode(JsonOps.INSTANCE, encoded.result().orElseThrow(), FieldPath.ROOT);
      if (!decoded.report().valid()
          || decoded.value().isEmpty()
          || !sameKey(entry.key(), key)
          || !sameKey(key, decoded.value().orElseThrow()))
        throw new IllegalArgumentException(
            "Reference key equality/hashCode must survive copy and codec roundtrip");
      String sortKey = encoded.result().orElseThrow().toString();
      if (!encodedKeys.add(sortKey))
        throw new IllegalArgumentException("Reference keys have duplicate encodings");
      limits.text(entry.label(), entry.preview());
      characters += (long) sortKey.length() + entry.label().length() + entry.searchText().length();
      for (var preview : entry.preview().entrySet())
        characters += (long) preview.getKey().length() + preview.getValue().length();
      if (characters > limits.maximumTextCharacters())
        throw new IllegalArgumentException("Reference index text budget exceeded");
      String search = SearchText.fold(entry.searchText());
      characters += Math.max(0, search.length() - entry.searchText().length());
      if (characters > limits.maximumTextCharacters())
        throw new IllegalArgumentException("Reference index text budget exceeded");
      V value = context.copy(entry.value(), () -> values.freeze(entry.value(), context));
      var row = new Row<>(key, value, entry.label(), search, sortKey, entry.preview());
      if (table.putIfAbsent(key, row) != null)
        throw new IllegalArgumentException("Duplicate reference key");
      copied.add(row);
    }
    copied.sort(Comparator.comparing(Row::sortKey));
    rows = List.copyOf(copied);
    byKey = Map.copyOf(table);
    prefixKeys = rows.stream().allMatch(row -> row.key() instanceof String);
    prefixRows =
        prefixKeys
            ? rows.stream().sorted(Comparator.comparing(row -> (String) row.key())).toList()
            : List.of();
  }

  public static <K, V> ReferenceIndex<K, V> unavailable(
      ReferenceStamp stamp,
      ValueType<K> keys,
      SnapshotPolicy<V> values,
      ReferenceLimits limits,
      ReferencePage.Availability availability,
      String reason) {
    if (availability == ReferencePage.Availability.READY)
      throw new IllegalArgumentException("Use a complete ready index");
    return new ReferenceIndex<>(
        stamp,
        keys,
        values,
        limits,
        SnapshotContext.Limits.CONFIGURATION,
        availability,
        reason,
        List.of());
  }

  @Override
  public ReferenceStamp stamp() {
    return stamp;
  }

  @Override
  public ReferenceResolution<V> resolve(K key) {
    if (availability != ReferencePage.Availability.READY)
      return ReferenceResolution.unavailable(
          stamp,
          availability == ReferencePage.Availability.MISSING_DEPENDENCY
              ? ReferenceResolution.Status.MISSING_DEPENDENCY
              : ReferenceResolution.Status.UNAVAILABLE_CONTEXT,
          reason);
    var encoded = keys.codec().encodeStart(JsonOps.INSTANCE, key);
    if (encoded.error().isPresent() || encoded.result().isEmpty())
      return ReferenceResolution.unavailable(
          stamp, ReferenceResolution.Status.MALFORMED, "Reference syntax is not admissible");
    var decoded = keys.decode(JsonOps.INSTANCE, encoded.result().orElseThrow(), FieldPath.ROOT);
    if (!decoded.report().valid() || decoded.value().isEmpty())
      return ReferenceResolution.unavailable(
          stamp, ReferenceResolution.Status.MALFORMED, "Reference syntax is not admissible");
    var row = byKey.get(decoded.value().orElseThrow());
    if (row == null)
      return ReferenceResolution.unavailable(
          stamp,
          ReferenceResolution.Status.UNKNOWN_REFERENCE,
          "Reference is not present in this complete index");
    var context = new SnapshotContext(snapshots);
    return ReferenceResolution.resolved(
        stamp, context.copy(row.value(), () -> values.freeze(row.value(), context)), row.preview());
  }

  @Override
  public boolean supports(ReferenceQuery.Match match) {
    return match == ReferenceQuery.Match.TEXT
        || match == ReferenceQuery.Match.KEY_PREFIX && prefixKeys;
  }

  @Override
  public ReferencePage<K> search(ReferenceQuery query) {
    limits.query(query);
    int offset = query.cursor().map(ReferenceQuery.Cursor::offset).orElse(0);
    if (query
            .cursor()
            .filter(
                cursor ->
                    !cursor.snapshot().equals(stamp.snapshot())
                        || !cursor.filter().equals(query.filter())
                        || cursor.match() != query.match())
            .isPresent()
        || offset > rows.size())
      throw new PolicyRefusal(
          "reference.cursor", "Reference cursor does not address this snapshot/filter");
    if (availability != ReferencePage.Availability.READY)
      return ReferencePage.unavailable(stamp, availability, reason);
    if (!supports(query.match()))
      return ReferencePage.unavailable(
          stamp,
          ReferencePage.Availability.UNSUPPORTED,
          "This source does not provide ID-prefix suggestions.");
    if (query.match() == ReferenceQuery.Match.KEY_PREFIX) return prefix(query);
    String needle = SearchText.fold(query.filter());
    var choices = new ArrayList<ReferencePage.Choice<K>>();
    int position = offset, examined = 0;
    var snapshots = new SnapshotContext(this.snapshots);
    while (position < rows.size()
        && examined < limits.maximumScan()
        && choices.size() < query.limit()) {
      var row = rows.get(position++);
      examined++;
      if (query.match() == ReferenceQuery.Match.TEXT
          ? row.search().contains(needle)
          : row.key() instanceof String key && key.startsWith(query.filter()))
        choices.add(
            new ReferencePage.Choice<>(
                keys.freeze(row.key(), snapshots), row.label(), row.preview()));
    }
    boolean exhausted = position == rows.size();
    return new ReferencePage<>(
        stamp,
        availability,
        choices,
        exhausted
            ? Optional.empty()
            : Optional.of(
                new ReferenceQuery.Cursor(
                    stamp.snapshot(), query.filter(), position, query.match())),
        examined,
        exhausted,
        "");
  }

  // Prefix cursors count candidates within this exact prefix range, rather than text-scan rows.
  // Navigation costs O(log(maximumEntries)); candidate reads still obey maximumScan and limit.
  private ReferencePage<K> prefix(ReferenceQuery query) {
    int lower = prefixBoundary(query.filter(), false);
    int upper = prefixBoundary(query.filter(), true);
    int offset = query.cursor().map(ReferenceQuery.Cursor::offset).orElse(0);
    if (offset > upper - lower)
      throw new PolicyRefusal("reference.cursor", "Reference cursor is outside this prefix range");
    int position = lower + offset, examined = 0;
    var choices = new ArrayList<ReferencePage.Choice<K>>();
    var context = new SnapshotContext(snapshots);
    while (position < upper && examined < limits.maximumScan() && choices.size() < query.limit()) {
      var row = prefixRows.get(position++);
      examined++;
      choices.add(
          new ReferencePage.Choice<>(keys.freeze(row.key(), context), row.label(), row.preview()));
    }
    boolean exhausted = position == upper;
    return new ReferencePage<>(
        stamp,
        availability,
        choices,
        exhausted
            ? Optional.empty()
            : Optional.of(
                new ReferenceQuery.Cursor(
                    stamp.snapshot(),
                    query.filter(),
                    offset + examined,
                    ReferenceQuery.Match.KEY_PREFIX)),
        examined,
        exhausted,
        "");
  }

  private int prefixBoundary(String prefix, boolean after) {
    int low = 0, high = prefixRows.size();
    while (low < high) {
      int middle = low + (high - low) / 2;
      String key = (String) prefixRows.get(middle).key();
      if (key.compareTo(prefix) < 0 || after && key.startsWith(prefix)) low = middle + 1;
      else high = middle;
    }
    return low;
  }

  private static boolean sameKey(Object first, Object second) {
    return first.equals(second) && second.equals(first) && first.hashCode() == second.hashCode();
  }
}
