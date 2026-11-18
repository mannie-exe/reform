package design.inherent.reform.neoforge.reference;

import com.mojang.serialization.DataResult;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.reference.ReferenceIndex;
import design.inherent.reform.reference.ReferenceLimits;
import design.inherent.reform.reference.ReferencePage;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

/** Owner-admitted identifier snapshots. No level, chunk, client renderer or retained registry. */
public final class NativeReferences {
  public record Limits(
      ReferenceLimits index,
      int maximumKeyLength,
      int maximumTagMembers,
      int maximumTotalMembers,
      SnapshotContext.Limits snapshots) {
    public static final Limits STANDARD =
        new Limits(
            ReferenceLimits.STANDARD, 512, 4096, 65536, new SnapshotContext.Limits(128, 262144));

    public Limits {
      Objects.requireNonNull(index);
      Objects.requireNonNull(snapshots);
      if (maximumKeyLength < 1 || maximumTagMembers < 1 || maximumTotalMembers < 1)
        throw new IllegalArgumentException("Native reference limits must be positive");
    }
  }

  /** Captured identifiers only; building the bounded index can run on an admitted worker. */
  public static final class Capture {
    private final ReferenceStamp stamp;
    private final boolean tags;
    private final Limits limits;
    private final ReferencePage.Availability availability;
    private final String reason;
    private final List<ReferenceIndex.Entry<String, List<String>>> entries;

    private Capture(
        ReferenceStamp stamp,
        boolean tags,
        Limits limits,
        ReferencePage.Availability availability,
        String reason,
        List<ReferenceIndex.Entry<String, List<String>>> entries) {
      this.stamp = stamp;
      this.tags = tags;
      this.limits = limits;
      this.availability = availability;
      this.reason = reason;
      this.entries =
          entries.stream()
              .map(
                  entry ->
                      new ReferenceIndex.Entry<>(
                          entry.key(),
                          List.copyOf(entry.value()),
                          entry.label(),
                          entry.searchText(),
                          entry.preview()))
              .toList();
    }

    public ReferenceIndex<String, List<String>> build() {
      return new ReferenceIndex<>(
          stamp,
          keys(tags, limits.maximumKeyLength()),
          valueType(limits).snapshotPolicy(),
          limits.index(),
          limits.snapshots(),
          availability,
          reason,
          entries);
    }
  }

  /** Storage retains authored spelling. Lookup separately uses native namespace normalization. */
  public static ValueType<String> type(DocumentId source, boolean tags, int maximumLength) {
    if (maximumLength < 1)
      throw new IllegalArgumentException("Native references need a positive text bound");
    var text = ValueTypes.text(maximumLength);
    var codec = text.codec().validate(value -> canonical(value, tags).map(ignored -> value));
    return ValueTypes.reference(ValueType.of(codec, text.shape(), text.snapshotPolicy()), source);
  }

  /** The caller supplies its actual frozen/current registry on the native owner thread. */
  public static <T> ReferenceIndex<String, List<String>> registry(
      OwnerContext context,
      ReferenceStamp stamp,
      Registry<T> registry,
      boolean tags,
      Limits limits) {
    return captureRegistry(context, stamp, registry, tags, limits).build();
  }

  public static <T> Capture captureRegistry(
      OwnerContext context,
      ReferenceStamp stamp,
      Registry<T> registry,
      boolean tags,
      Limits limits) {
    admitted(context, stamp);
    Objects.requireNonNull(registry);
    Objects.requireNonNull(limits);
    if (registry.keySet().size() > limits.index().maximumEntries())
      throw new IllegalArgumentException("Native registry exceeds the complete index budget");
    var entries = new ArrayList<ReferenceIndex.Entry<String, List<String>>>();
    var names = new HashMap<ResourceLocation, String>();
    String registryId = registry.key().location().toString();
    for (var id : registry.keySet()) {
      String key = bounded(id, false, limits);
      names.put(id, key);
      entries.add(
          new ReferenceIndex.Entry<>(
              key,
              List.of(key),
              key,
              key,
              Map.of("kind", "registry_entry", "registry", registryId)));
    }
    int total = 0;
    if (tags) {
      var selected =
          registry
              .getTagNames()
              .limit((long) limits.index().maximumEntries() - entries.size() + 1)
              .toList();
      if ((long) selected.size() + entries.size() > limits.index().maximumEntries())
        throw new IllegalArgumentException("Native registry/tag index exceeds its complete budget");
      for (var tag : selected) {
        var members =
            registry
                .getTag(tag)
                .orElseThrow(() -> new IllegalStateException("Native tag changed during capture"));
        if (members.size() > limits.maximumTagMembers()
            || (long) total + members.size() > limits.maximumTotalMembers())
          throw new IllegalArgumentException("Native tag membership budget exceeded");
        var values = new ArrayList<String>();
        for (var holder : members) {
          if (values.size() >= limits.maximumTagMembers() || total >= limits.maximumTotalMembers())
            throw new IllegalArgumentException("Native tag membership budget exceeded");
          var id =
              holder
                  .unwrapKey()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Tag member requires an actual registered key"))
                  .location();
          var key = names.get(id);
          if (key == null)
            throw new IllegalArgumentException(
                "Tag member is outside the admitted registry snapshot");
          values.add(key);
          total++;
        }
        if (values.size() != members.size())
          throw new IllegalStateException("Native tag changed during capture");
        values.sort(String::compareTo);
        String key = bounded(tag.location(), true, limits);
        entries.add(
            new ReferenceIndex.Entry<>(
                key,
                List.copyOf(values),
                key,
                key,
                Map.of(
                    "kind",
                    "tag",
                    "registry",
                    registryId,
                    "members",
                    Integer.toString(values.size()))));
      }
    }
    admitted(context, stamp);
    return new Capture(stamp, tags, limits, ReferencePage.Availability.READY, "", entries);
  }

  /** No supplier invocation or optional class lookup is needed for an absent dependency. */
  public static <T> ReferenceIndex<String, List<String>> optionalRegistry(
      OwnerContext context,
      ReferenceStamp stamp,
      boolean dependencyPresent,
      Supplier<Registry<T>> registry,
      boolean tags,
      Limits limits) {
    return captureOptionalRegistry(context, stamp, dependencyPresent, registry, tags, limits)
        .build();
  }

  public static <T> Capture captureOptionalRegistry(
      OwnerContext context,
      ReferenceStamp stamp,
      boolean dependencyPresent,
      Supplier<Registry<T>> registry,
      boolean tags,
      Limits limits) {
    admitted(context, stamp);
    Objects.requireNonNull(registry);
    if (dependencyPresent) return captureRegistry(context, stamp, registry.get(), tags, limits);
    return new Capture(
        stamp,
        tags,
        limits,
        ReferencePage.Availability.MISSING_DEPENDENCY,
        "Optional dependency is absent",
        List.of());
  }

  /** Resource enumeration is an explicit owner operation; this factory receives admitted names. */
  public static ReferenceIndex<String, List<String>> resources(
      OwnerContext context,
      ReferenceStamp stamp,
      Collection<ResourceLocation> names,
      Limits limits) {
    return captureResources(context, stamp, names, limits).build();
  }

  public static Capture captureResources(
      OwnerContext context,
      ReferenceStamp stamp,
      Collection<ResourceLocation> names,
      Limits limits) {
    admitted(context, stamp);
    if (names.size() > limits.index().maximumEntries())
      throw new IllegalArgumentException("Resource names exceed the complete index budget");
    var entries = new ArrayList<ReferenceIndex.Entry<String, List<String>>>();
    for (var id : names) {
      if (entries.size() >= limits.index().maximumEntries())
        throw new IllegalArgumentException("Resource names exceed the complete index budget");
      String key = bounded(id, false, limits);
      entries.add(
          new ReferenceIndex.Entry<>(key, List.of(key), key, key, Map.of("kind", "resource")));
    }
    admitted(context, stamp);
    return new Capture(stamp, false, limits, ReferencePage.Availability.READY, "", entries);
  }

  private static String bounded(ResourceLocation id, boolean tag, Limits limits) {
    if ((long) id.getNamespace().length() + id.getPath().length() + (tag ? 2 : 1)
        > limits.maximumKeyLength())
      throw new IllegalArgumentException("Native identifier exceeds its declared length");
    return (tag ? "#" : "") + id;
  }

  public static ValueType<List<String>> valueType(Limits limits) {
    return ValueTypes.list(ValueTypes.text(limits.maximumKeyLength()), limits.maximumTagMembers());
  }

  private static ValueType<String> keys(boolean tags, int maximumLength) {
    var text = ValueTypes.text(maximumLength);
    return ValueType.of(
        text.codec().flatXmap(value -> canonical(value, tags), value -> canonical(value, tags)),
        text.shape(),
        text.snapshotPolicy());
  }

  private static DataResult<String> canonical(String value, boolean tags) {
    boolean tag = value.startsWith("#");
    if (tag && !tags) return DataResult.error(() -> "This reference source does not accept tags");
    var id = ResourceLocation.tryParse(tag ? value.substring(1) : value);
    return id == null || id.getPath().isEmpty()
        ? DataResult.error(() -> "Expected a native resource identifier")
        : DataResult.success((tag ? "#" : "") + id);
  }

  private static void admitted(OwnerContext context, ReferenceStamp stamp) {
    context.requireOwner();
    if (!context.id().equals(stamp.context()))
      throw new IllegalArgumentException(
          "Registry/resource capture requires its actual owner context");
  }

  private NativeReferences() {}
}
