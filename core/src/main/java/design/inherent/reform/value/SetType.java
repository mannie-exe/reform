package design.inherent.reform.value;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Explicit unordered membership with list encoding; duplicate authored rows remain a draft. */
final class SetType<E> implements ValueType<Set<E>> {
  private final ValueShape.Sequence shape;
  private final Codec<Set<E>> codec;
  private final SnapshotPolicy<Set<E>> snapshots;

  SetType(ValueType<E> element, int maximumSize) {
    java.util.Objects.requireNonNull(element);
    shape =
        new ValueShape.Sequence(element, maximumSize, true, ValueShape.CollectionOrder.UNORDERED);
    var encoded =
        element.codec().listOf(0, maximumSize).flatXmap(this::fromElements, this::toElements);
    codec =
        new Codec<>() {
          @Override
          public <O> DataResult<Pair<Set<E>, O>> decode(DynamicOps<O> ops, O input) {
            return encoded.decode(ops, input);
          }

          @Override
          public <O> DataResult<O> encode(Set<E> input, DynamicOps<O> ops, O prefix) {
            if (input.size() > maximumSize)
              return DataResult.error(() -> "Set exceeds its declared size");
            for (var item : input) {
              var checked = CollectionIdentity.encode(element, item, ops);
              if (checked.error().isPresent())
                return DataResult.error(() -> checked.error().orElseThrow().message());
            }
            return encoded.encode(input, ops, prefix);
          }
        };
    snapshots =
        new SnapshotPolicy<>() {
          @Override
          public Set<E> freeze(Set<E> input, SnapshotContext context) {
            if (input.size() > maximumSize)
              throw new IllegalArgumentException("Set exceeds its declared size");
            var copied = new LinkedHashSet<E>();
            for (var item : input) {
              var frozen = element.freeze(item, context);
              if (!CollectionIdentity.same(item, frozen))
                throw new IllegalArgumentException(
                    "Set member equality/hashCode must survive an owned copy");
              if (!copied.add(frozen))
                throw new IllegalArgumentException("Duplicate copied set member");
            }
            return Collections.unmodifiableSet(copied);
          }

          @Override
          public boolean equivalent(Set<E> first, Set<E> second) {
            return first.equals(second);
          }
        };
  }

  private DataResult<Set<E>> fromElements(List<E> elements) {
    if (elements.size() > shape.maximumSize())
      return DataResult.error(() -> "Set exceeds its declared size");
    var result = new LinkedHashSet<E>();
    for (var element : elements)
      if (!result.add(element)) return DataResult.error(() -> "Duplicate decoded set member");
    return DataResult.success(Collections.unmodifiableSet(result));
  }

  private DataResult<List<E>> toElements(Set<E> input) {
    if (input.size() > shape.maximumSize())
      return DataResult.error(() -> "Set exceeds its declared size");
    return DataResult.success(List.copyOf(input));
  }

  @Override
  public Codec<Set<E>> codec() {
    return codec;
  }

  @Override
  public ValueShape.Sequence shape() {
    return shape;
  }

  @Override
  public SnapshotPolicy<Set<E>> snapshotPolicy() {
    return snapshots;
  }
}
