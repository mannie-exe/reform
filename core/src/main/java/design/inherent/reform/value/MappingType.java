package design.inherent.reform.value;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.RecordType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit lossless map encoding. Keys use Java value equality/hashCode, stable under owned copying
 * and codec roundtrip. Object keys must actually encode as strings; other keys use entry lists.
 */
public final class MappingType<K, V> implements ValueType<Map<K, V>> {
  private record Entry<K, V>(K key, V value) {}

  private final ValueType<K> key;
  private final ValueType<V> value;
  private final ValueShape.Mapping shape;
  private final Codec<Map<K, V>> codec;
  private final SnapshotPolicy<Map<K, V>> snapshots;

  public static <K, V> MappingType<K, V> object(
      ValueType<K> key, ValueType<V> value, int maximumSize) {
    return new MappingType<>(key, value, maximumSize, ValueShape.MapEncoding.OBJECT);
  }

  public static <K, V> MappingType<K, V> entries(
      ValueType<K> key, ValueType<V> value, int maximumSize) {
    return new MappingType<>(key, value, maximumSize, ValueShape.MapEncoding.ENTRY_LIST);
  }

  private MappingType(
      ValueType<K> key, ValueType<V> value, int maximumSize, ValueShape.MapEncoding encoding) {
    this.key = Objects.requireNonNull(key);
    this.value = Objects.requireNonNull(value);
    shape = new ValueShape.Mapping(key, value, maximumSize, encoding);
    var keyField = FieldKey.of("key", key);
    var valueField = FieldKey.of("value", value);
    var row =
        RecordType.<Entry<K, V>>builder(v -> new Entry<>(v.get(keyField), v.get(valueField)))
            .field(keyField, Entry::key)
            .field(valueField, Entry::value)
            .build();
    Codec<Map<K, V>> encoded =
        encoding == ValueShape.MapEncoding.ENTRY_LIST
            ? row.codec().listOf(0, maximumSize).flatXmap(this::fromEntries, this::toEntries)
            : new Codec<Map<K, V>>() {
              @Override
              public <O> DataResult<Pair<Map<K, V>, O>> decode(DynamicOps<O> ops, O input) {
                return ops.getMap(input)
                    .flatMap(
                        sourceMap -> {
                          var encoded = sourceMap.entries().limit((long) maximumSize + 1).toList();
                          if (encoded.size() > maximumSize)
                            return DataResult.error(() -> "Map exceeds its declared size");
                          var entries = new ArrayList<Entry<K, V>>(encoded.size());
                          for (var pair : encoded) {
                            var json = ops.convertTo(JsonOps.INSTANCE, pair.getFirst());
                            if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString())
                              return DataResult.error(
                                  () -> "Object map keys must encode as strings");
                            var k = key.codec().parse(ops, pair.getFirst());
                            var v = value.codec().parse(ops, pair.getSecond());
                            if (k.error().isPresent() || k.result().isEmpty())
                              return DataResult.error(
                                  () ->
                                      k.error()
                                          .map(DataResult.Error::message)
                                          .orElse("Invalid map key"));
                            if (v.error().isPresent() || v.result().isEmpty())
                              return DataResult.error(
                                  () ->
                                      v.error()
                                          .map(DataResult.Error::message)
                                          .orElse("Invalid map value"));
                            entries.add(
                                new Entry<>(k.result().orElseThrow(), v.result().orElseThrow()));
                          }
                          return fromEntries(entries);
                        })
                    .map(mapped -> Pair.of(mapped, ops.empty()));
              }

              @Override
              public <O> DataResult<O> encode(Map<K, V> input, DynamicOps<O> ops, O prefix) {
                var rows = toEntries(input);
                if (rows.error().isPresent())
                  return DataResult.error(() -> rows.error().orElseThrow().message());
                var encoded = new HashSet<String>();
                var result = ops.mapBuilder();
                for (var entry : rows.result().orElseThrow()) {
                  var name = objectKey(entry.key(), ops);
                  if (name.error().isPresent())
                    return DataResult.error(() -> name.error().orElseThrow().message());
                  String text = name.result().orElseThrow();
                  if (!encoded.add(text))
                    return DataResult.error(
                        () -> "Different map keys have the same string encoding");
                  result =
                      result.add(
                          ops.createString(text), value.codec().encodeStart(ops, entry.value()));
                }
                return result.build(prefix);
              }
            };
    codec =
        encoding == ValueShape.MapEncoding.OBJECT
            ? encoded
            : new Codec<>() {
              @Override
              public <O> DataResult<Pair<Map<K, V>, O>> decode(DynamicOps<O> ops, O input) {
                return encoded.decode(ops, input);
              }

              @Override
              public <O> DataResult<O> encode(Map<K, V> input, DynamicOps<O> ops, O prefix) {
                if (input.size() > maximumSize)
                  return DataResult.error(() -> "Map exceeds its declared size");
                for (var mapKey : input.keySet()) {
                  var checked = encodedKey(mapKey, ops);
                  if (checked.error().isPresent())
                    return DataResult.error(() -> checked.error().orElseThrow().message());
                }
                return encoded.encode(input, ops, prefix);
              }
            };
    snapshots =
        new SnapshotPolicy<>() {
          @Override
          public Map<K, V> freeze(Map<K, V> input, SnapshotContext context) {
            if (input.size() > maximumSize)
              throw new IllegalArgumentException("Map exceeds its declared size");
            var copied = new LinkedHashMap<K, V>();
            for (var entry : input.entrySet()) {
              K frozen = key.freeze(entry.getKey(), context);
              if (!CollectionIdentity.same(entry.getKey(), frozen))
                throw new IllegalArgumentException(
                    "Map key equality/hashCode must survive an owned copy");
              if (copied.containsKey(frozen))
                throw new IllegalArgumentException("Duplicate copied map key");
              copied.put(frozen, value.freeze(entry.getValue(), context));
            }
            return Collections.unmodifiableMap(copied);
          }

          @Override
          public boolean equivalent(Map<K, V> first, Map<K, V> second) {
            if (first.size() != second.size()) return false;
            for (var entry : first.entrySet())
              if (!second.containsKey(entry.getKey())
                  || !value.equivalent(entry.getValue(), second.get(entry.getKey()))) return false;
            return true;
          }
        };
  }

  private <O> DataResult<String> objectKey(K input, DynamicOps<O> ops) {
    var encoded = encodedKey(input, ops);
    if (encoded.error().isPresent() || encoded.result().isEmpty())
      return DataResult.error(() -> "Map key could not encode completely");
    var json = ops.convertTo(JsonOps.INSTANCE, encoded.result().orElseThrow());
    if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isString())
      return DataResult.error(() -> "Object map keys must encode as strings");
    return DataResult.success(json.getAsString());
  }

  private <O> DataResult<O> encodedKey(K input, DynamicOps<O> ops) {
    return CollectionIdentity.encode(key, input, ops);
  }

  private DataResult<Map<K, V>> fromEntries(List<Entry<K, V>> entries) {
    if (entries.size() > shape.maximumSize())
      return DataResult.error(() -> "Map exceeds its declared size");
    var mapped = new LinkedHashMap<K, V>();
    for (var entry : entries) {
      if (mapped.containsKey(entry.key()))
        return DataResult.error(() -> "Duplicate decoded map key");
      mapped.put(entry.key(), entry.value());
    }
    return DataResult.success(Collections.unmodifiableMap(mapped));
  }

  private DataResult<List<Entry<K, V>>> toEntries(Map<K, V> input) {
    if (input.size() > shape.maximumSize())
      return DataResult.error(() -> "Map exceeds its declared size");
    return DataResult.success(
        input.entrySet().stream().map(e -> new Entry<>(e.getKey(), e.getValue())).toList());
  }

  @Override
  public Codec<Map<K, V>> codec() {
    return codec;
  }

  @Override
  public ValueShape.Mapping shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<Map<K, V>> snapshotPolicy() {
    return snapshots;
  }
}
