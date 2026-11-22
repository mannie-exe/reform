package design.inherent.reform.neoforge.configuration;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Fixed declared native fields, never a scan of arbitrary mod classes or a default correction. */
final class NativeSchema {
  record Field(
      List<String> path,
      FieldPath address,
      ModConfigSpec.ConfigValue<Object> nativeValue,
      ValueType<Object> type) {
    Object value(Map<String, Object> root) {
      Object value = root;
      for (String segment : path) {
        if (!(value instanceof Map<?, ?> map) || !map.containsKey(segment))
          throw new IllegalArgumentException("Missing declared native field");
        value = map.get(segment);
      }
      return type.freeze(value);
    }
  }

  final List<Field> fields = new ArrayList<>();
  final DocumentDefinition<Map<String, Object>> definition;
  private final int maximumFields;
  private final Map<FieldPath, ValueType<?>> overrides;
  private final Set<FieldPath> selected;
  private final Set<List<String>> selectedPaths;
  private final Map<String, Object> defaults = new LinkedHashMap<>();
  private final List<List<String>> groups = new ArrayList<>();
  private int declaredEntries;

  NativeSchema(
      DocumentId id,
      ModConfigSpec spec,
      Map<FieldPath, ValueType<?>> overrides,
      int maximumFields,
      Set<FieldPath> selected,
      Function<Map<String, Object>, ValidationReport> validate) {
    this.maximumFields = maximumFields;
    this.overrides = new LinkedHashMap<>(overrides);
    this.selected = Set.copyOf(selected);
    if (selected.size() > maximumFields)
      throw new IllegalArgumentException("Native selection exceeds its field bound");
    var type = object(spec.getValues(), List.of(), defaults);
    if (!this.overrides.isEmpty())
      throw new IllegalArgumentException("Explicit native type addresses an undeclared field");
    if (projected()
        && !fields.stream().map(Field::address).collect(Collectors.toSet()).equals(this.selected))
      throw new IllegalArgumentException("Native selection must name exact declared leaf fields");
    selectedPaths =
        projected()
            ? fields.stream().map(Field::path).collect(Collectors.toUnmodifiableSet())
            : Set.of();
    definition = new DocumentDefinition<>(id, 1, type, () -> defaults, validate);
  }

  private ValueType<Map<String, Object>> object(
      UnmodifiableConfig source, List<String> parent, Map<String, Object> initial) {
    if (parent.size() >= 32) throw new IllegalArgumentException("Native schema is too deep");
    var keys = new LinkedHashMap<String, FieldKey<Object>>();
    var builder =
        RecordType.<Map<String, Object>>builder(
            values -> {
              var result = new LinkedHashMap<String, Object>();
              keys.forEach((name, key) -> result.put(name, values.get(key)));
              return Map.copyOf(result);
            });
    for (var entry : source.entrySet()) {
      if (++declaredEntries > maximumFields)
        throw new IllegalArgumentException("Native schema exceeds its declared entry bound");
      var path = new ArrayList<>(parent);
      path.add(entry.getKey());
      var address = address(path);
      if (projected() && selected.stream().noneMatch(field -> field.startsWith(address))) continue;
      final ValueType<?> type;
      final Object defaultValue;
      if (entry.getValue() instanceof UnmodifiableConfig group) {
        groups.add(List.copyOf(path));
        var nested = new LinkedHashMap<String, Object>();
        type = object(group, path, nested);
        defaultValue = Map.copyOf(nested);
      } else if (entry.getValue() instanceof ModConfigSpec.ConfigValue<?> value) {
        if (fields.size() >= maximumFields)
          throw new IllegalArgumentException("Native schema exceeds its declared field bound");
        if (!path.equals(value.getPath()))
          throw new IllegalArgumentException("Native field path does not match its registration");
        defaultValue = value.getDefault(); // Native default supplier, on the actual owner thread.
        var explicit = overrides.remove(address);
        type = explicit == null ? infer(defaultValue) : explicit;
        fields.add(field(List.copyOf(path), address, value, type));
      } else throw new IllegalArgumentException("Unknown native specification entry");
      var key = key(entry.getKey(), type);
      keys.put(entry.getKey(), key);
      builder.field(key, values -> values.get(entry.getKey()));
      initial.put(entry.getKey(), defaultValue);
    }
    return builder.build();
  }

  Map<String, Object> raw() {
    var result = new LinkedHashMap<String, Object>();
    for (var group : groups) put(result, group, new LinkedHashMap<String, Object>());
    for (var field : fields)
      put(result, field.path(), field.type().freeze(field.nativeValue().getRaw()));
    return definition.type().freeze(result);
  }

  boolean projected() {
    return !selected.isEmpty();
  }

  JsonElement sourceJson(UnmodifiableConfig source) {
    var remaining = new int[] {16384};
    if (!projected()) return json(source, 0, remaining);
    var result = new JsonObject();
    for (var field : fields) {
      var path = field.path();
      if (!source.contains(path)) continue;
      var parent = result;
      for (String segment : path.subList(0, path.size() - 1)) {
        if (!parent.has(segment)) parent.add(segment, new JsonObject());
        parent = parent.getAsJsonObject(segment);
      }
      parent.add(path.getLast(), json(source.getRaw(path), path.size(), remaining));
    }
    return result;
  }

  /**
   * The real native serializer writes the whole file, so a projection must verify all untouched
   * values.
   */
  boolean sameNativeValues(
      UnmodifiableConfig expected, UnmodifiableConfig actual, boolean skipSelected) {
    return sameNativeValue(
        expected,
        actual,
        skipSelected ? selectedPaths : Set.of(),
        new ArrayList<>(),
        0,
        new int[] {16384});
  }

  private static boolean sameNativeValue(
      Object expected,
      Object actual,
      Set<List<String>> skipped,
      List<String> path,
      int depth,
      int[] remaining) {
    if (depth > 64 || --remaining[0] < 0) return false;
    if (expected instanceof UnmodifiableConfig left) {
      if (!(actual instanceof UnmodifiableConfig right) || left.size() != right.size())
        return false;
      for (var entry : left.entrySet()) {
        var key = entry.getKey();
        var relative = List.of(key);
        if (!right.contains(relative)) return false;
        path.add(key);
        boolean matches =
            skipped.contains(path)
                || sameNativeValue(
                    entry.getValue(), right.getRaw(relative), skipped, path, depth + 1, remaining);
        path.removeLast();
        if (!matches) return false;
      }
      return true;
    }
    if (expected instanceof List<?> left) {
      if (!(actual instanceof List<?> right) || left.size() != right.size()) return false;
      // Native field selectors never address members inside an authored TOML list.
      var other = right.iterator();
      for (Object value : left)
        if (!sameNativeValue(value, other.next(), Set.of(), path, depth + 1, remaining))
          return false;
      return true;
    }
    return Objects.equals(expected, actual);
  }

  private static void put(Map<String, Object> target, List<String> path, Object value) {
    if (path.size() == 1) {
      target.put(path.getFirst(), value);
      return;
    }
    var child = target.get(path.getFirst());
    if (child == null) {
      var created = new LinkedHashMap<String, Object>();
      target.put(path.getFirst(), created);
      put(created, path.subList(1, path.size()), value);
    } else put(asMap(child), path.subList(1, path.size()), value);
  }

  @SuppressWarnings("unchecked") // Only the map branches constructed in put enter this helper.
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static FieldPath address(List<String> path) {
    var result = FieldPath.ROOT;
    for (String segment : path) result = result.field(segment);
    return result;
  }

  @SuppressWarnings("unchecked") // Each key retains its paired runtime type/codec.
  private static FieldKey<Object> key(String name, ValueType<?> type) {
    return FieldKey.of(name, (ValueType<Object>) type);
  }

  @SuppressWarnings(
      "unchecked") // The native handle and declared/explicit ValueType are paired here.
  private static Field field(
      List<String> path, FieldPath address, ModConfigSpec.ConfigValue<?> value, ValueType<?> type) {
    return new Field(
        path, address, (ModConfigSpec.ConfigValue<Object>) value, (ValueType<Object>) type);
  }

  private static ValueType<?> infer(Object value) {
    if (value instanceof Boolean) return ValueTypes.bool();
    if (value instanceof Byte) return ValueTypes.byteInteger(Byte.MIN_VALUE, Byte.MAX_VALUE);
    if (value instanceof Short) return ValueTypes.shortInteger(Short.MIN_VALUE, Short.MAX_VALUE);
    if (value instanceof Integer) return ValueTypes.integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
    if (value instanceof Long) return ValueTypes.longInteger(Long.MIN_VALUE, Long.MAX_VALUE);
    if (value instanceof Float) return ValueTypes.floating(-Float.MAX_VALUE, Float.MAX_VALUE);
    if (value instanceof Double) return ValueTypes.decimal(-Double.MAX_VALUE, Double.MAX_VALUE);
    if (value instanceof String) return ValueTypes.text(32768);
    if (value instanceof Enum<?> choice) return enumeration(choice);
    // Empty/custom/nested collections are ambiguous; the owner must declare a type explicitly.
    if (value instanceof List<?> list
        && !list.isEmpty()
        && list.stream().allMatch(String.class::isInstance))
      return ValueTypes.list(ValueTypes.text(32768), 4096);
    throw new IllegalArgumentException("Native value needs an explicit re:form type");
  }

  @SuppressWarnings({"unchecked", "rawtypes"}) // Enum declares its own enclosing concrete type.
  private static ValueType<?> enumeration(Enum<?> value) {
    return ValueTypes.enumeration((Class) value.getDeclaringClass());
  }

  static JsonElement json(Object value, int depth, int[] remaining) {
    if (depth > 64 || --remaining[0] < 0)
      throw new IllegalArgumentException("Native source exceeds its structural admission");
    if (value == null) return JsonNull.INSTANCE;
    if (value instanceof Boolean bool) return new JsonPrimitive(bool);
    if (value instanceof Number number) return new JsonPrimitive(number);
    if (value instanceof String text) {
      if (text.length() > 32768)
        throw new IllegalArgumentException("Native source text is too long");
      return new JsonPrimitive(text);
    }
    if (value instanceof UnmodifiableConfig object) {
      var result = new JsonObject();
      for (var entry : object.entrySet()) {
        if (entry.getKey().length() > 32768)
          throw new IllegalArgumentException("Native source key is too long");
        result.add(entry.getKey(), json(entry.getValue(), depth + 1, remaining));
      }
      return result;
    }
    if (value instanceof List<?> list) {
      var result = new JsonArray();
      for (var item : list) result.add(json(item, depth + 1, remaining));
      return result;
    }
    throw new IllegalArgumentException("Native source has an unsupported TOML value");
  }
}
