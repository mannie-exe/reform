package design.inherent.reform.value;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.validation.DecodedValue;
import design.inherent.reform.validation.Issue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Explicit registered Java variants and a stable owned discriminator/payload envelope. */
public final class TaggedType<T> implements ValueType<T> {
  public static final class Builder<T> {
    private final String discriminator;
    private final String payload;
    private final Map<String, Binding<T, ?>> bindings = new LinkedHashMap<>();

    private Builder(String discriminator, String payload) {
      this.discriminator = discriminator;
      this.payload = payload;
    }

    public <V extends T> Builder<T> variant(String id, Class<V> javaType, ValueType<V> type) {
      Objects.requireNonNull(javaType);
      if (javaType.isPrimitive()
          || javaType.isInterface()
          || java.lang.reflect.Modifier.isAbstract(javaType.getModifiers()))
        throw new IllegalArgumentException("A variant needs a concrete exact Java class");
      var binding = new Binding<T, V>(new ValueShape.Variant(id, type), javaType, type);
      if (bindings.containsKey(id)
          || bindings.values().stream().anyMatch(b -> b.javaType().equals(javaType)))
        throw new IllegalArgumentException("Variant IDs and exact Java classes must be distinct");
      bindings.put(id, binding);
      return this;
    }

    public TaggedType<T> build() {
      return new TaggedType<>(discriminator, payload, List.copyOf(bindings.values()));
    }
  }

  private record Binding<T, V extends T>(
      ValueShape.Variant metadata, Class<V> javaType, ValueType<V> type) {
    private <O> DataResult<O> encode(T value, DynamicOps<O> ops) {
      return type.codec().encodeStart(ops, javaType.cast(value));
    }

    private <O> DecodedValue<T> decode(DynamicOps<O> ops, O value, FieldPath path) {
      var decoded = type.decode(ops, value, path);
      if (decoded.value().isPresent() && decoded.value().orElseThrow().getClass() != javaType)
        return DecodedValue.failure(
            Issue.error(
                "value.variant_class", path, "Decoded value is not the declared exact variant"));
      return decoded.value().isPresent()
          ? DecodedValue.success(decoded.value().orElseThrow())
          : DecodedValue.failure(decoded.report());
    }

    private T freeze(T value, SnapshotContext context) {
      var copied = type.snapshotPolicy().freeze(javaType.cast(value), context);
      if (copied.getClass() != javaType)
        throw new IllegalArgumentException("Copied value is not the declared exact variant");
      return copied;
    }

    private boolean equivalent(T first, T second) {
      return type.equivalent(javaType.cast(first), javaType.cast(second));
    }
  }

  public static <T> Builder<T> builder(String discriminator, String payload) {
    return new Builder<>(discriminator, payload);
  }

  private final ValueShape.Alternative shape;
  private final Map<String, Binding<T, ?>> byId;
  private final Map<Class<?>, Binding<T, ?>> byClass;
  private final Codec<T> codec;
  private final SnapshotPolicy<T> snapshots;

  private TaggedType(String discriminator, String payload, List<Binding<T, ?>> bindings) {
    shape =
        new ValueShape.Alternative(
            discriminator, payload, bindings.stream().map(Binding::metadata).toList());
    var ids = new LinkedHashMap<String, Binding<T, ?>>();
    var classes = new LinkedHashMap<Class<?>, Binding<T, ?>>();
    for (var binding : bindings) {
      ids.put(binding.metadata().id(), binding);
      classes.put(binding.javaType(), binding);
    }
    byId = Map.copyOf(ids);
    byClass = Map.copyOf(classes);
    codec =
        new Codec<>() {
          @Override
          public <O> DataResult<Pair<T, O>> decode(DynamicOps<O> ops, O input) {
            var decoded = read(ops, input, FieldPath.ROOT);
            if (decoded.value().isEmpty())
              return DataResult.error(
                  () ->
                      decoded.report().issues().stream()
                          .map(Issue::fallback)
                          .reduce((a, b) -> a + "; " + b)
                          .orElse("Alternative validation unavailable"));
            return DataResult.success(Pair.of(decoded.value().orElseThrow(), ops.empty()));
          }

          @Override
          public <O> DataResult<O> encode(T value, DynamicOps<O> ops, O prefix) {
            var binding = byClass.get(Objects.requireNonNull(value).getClass());
            if (binding == null)
              return DataResult.error(() -> "Java value is not a declared exact variant");
            var payloadValue = binding.encode(value, ops);
            if (payloadValue.error().isPresent() || payloadValue.result().isEmpty())
              return DataResult.error(() -> "Variant payload cannot encode completely");
            if (!binding
                .decode(
                    ops,
                    payloadValue.result().orElseThrow(),
                    FieldPath.ROOT.variant(binding.metadata().id()))
                .report()
                .valid()) return DataResult.error(() -> "Variant payload cannot decode completely");
            var fields = new LinkedHashMap<O, O>();
            fields.put(
                ops.createString(shape.discriminator()), ops.createString(binding.metadata().id()));
            fields.put(ops.createString(shape.payload()), payloadValue.result().orElseThrow());
            return ops.mergeToMap(prefix, fields);
          }
        };
    snapshots =
        new SnapshotPolicy<>() {
          @Override
          public T freeze(T value, SnapshotContext context) {
            var binding = byClass.get(value.getClass());
            if (binding == null)
              throw new IllegalArgumentException("Java value is not a declared exact variant");
            return binding.freeze(value, context);
          }

          @Override
          public boolean equivalent(T first, T second) {
            var binding = byClass.get(first.getClass());
            return binding != null
                && first.getClass().equals(second.getClass())
                && binding.equivalent(first, second);
          }
        };
  }

  private <O> DecodedValue<T> read(DynamicOps<O> ops, O input, FieldPath path) {
    var map = ops.getMap(input);
    if (map.error().isPresent() || map.result().isEmpty())
      return DecodedValue.failure(
          Issue.error("value.alternative", path, "Expected a tagged object"));
    var object = map.result().orElseThrow();
    var names = new java.util.HashSet<String>();
    for (var entry : object.entries().limit(3).toList()) {
      var name = ExactCodecs.STRING.parse(ops, entry.getFirst());
      if (name.error().isPresent()
          || name.result().isEmpty()
          || !names.add(name.result().orElseThrow())
          || (!name.result().orElseThrow().equals(shape.discriminator())
              && !name.result().orElseThrow().equals(shape.payload())))
        return DecodedValue.failure(
            Issue.error(
                "value.variant_fields",
                path,
                "Expected only the declared discriminator and payload"));
    }
    if (!names.contains(shape.discriminator()) || !names.contains(shape.payload()))
      return DecodedValue.failure(
          Issue.error("value.variant_fields", path, "Discriminator and payload are required"));
    var id = ExactCodecs.STRING.parse(ops, object.get(shape.discriminator()));
    var binding =
        id.error().isEmpty() && id.result().isPresent()
            ? byId.get(id.result().orElseThrow())
            : null;
    if (binding == null)
      return DecodedValue.failure(
          Issue.error(
              "value.variant", path.append(FieldPath.Part.KEY), "Select a declared variant"));
    var payload = object.get(shape.payload());
    return binding.decode(
        ops, payload == null ? ops.empty() : payload, path.variant(binding.metadata().id()));
  }

  @Override
  public Codec<T> codec() {
    return codec;
  }

  @Override
  public ValueShape.Alternative shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<T> snapshotPolicy() {
    return snapshots;
  }

  @Override
  public <O> DecodedValue<T> decode(DynamicOps<O> ops, O input, FieldPath path) {
    return read(ops, input, path);
  }
}
