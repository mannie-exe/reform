package design.inherent.reform.definition;

import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Explicit registered-record derivation; no classpath scanning or reflective access override. */
public final class RecordTypes {
  public record Limits(int maximumDepth, int maximumFields, int textLength, int collectionSize) {
    public static final Limits CONFIGURATION = new Limits(32, 1024, 4096, 256);

    public Limits {
      if (maximumDepth < 1
          || maximumDepth > 128
          || maximumFields < 1
          || textLength < 0
          || collectionSize < 0)
        throw new IllegalArgumentException("Invalid record derivation bounds");
    }
  }

  public static final class Builder<T extends Record> {
    private final Class<T> root;
    private final MethodHandles.Lookup lookup;
    private final Map<Class<?>, ValueType<?>> registered = new HashMap<>();
    private final Map<String, FieldKey<?>> fields = new LinkedHashMap<>();
    private Limits limits = Limits.CONFIGURATION;

    private Builder(Class<T> root, MethodHandles.Lookup lookup) {
      this.root = Objects.requireNonNull(root);
      this.lookup = Objects.requireNonNull(lookup);
      if (!root.isRecord())
        throw new IllegalArgumentException("Expected an explicitly declared record");
    }

    /** Override one root component's serialized identity/type, without guessing from its name. */
    public Builder<T> field(String component, FieldKey<?> field) {
      Objects.requireNonNull(component);
      Objects.requireNonNull(field);
      if (fields.putIfAbsent(component, field) != null)
        throw new IllegalArgumentException("Duplicate component binding: " + component);
      return this;
    }

    /** A custom or nested type supplies its normal explicit codec/shape/snapshot contract. */
    public <V> Builder<T> register(Class<V> javaType, ValueType<V> type) {
      if (registered.putIfAbsent(Objects.requireNonNull(javaType), Objects.requireNonNull(type))
          != null) throw new IllegalArgumentException("Java type already registered");
      return this;
    }

    public Builder<T> limits(Limits bounds) {
      limits = Objects.requireNonNull(bounds);
      return this;
    }

    public RecordType<T> build() {
      var resolver = new Resolver(lookup, limits, Map.copyOf(registered));
      return resolver.record(root, Map.copyOf(fields), 1);
    }
  }

  /** Public accessible records and public canonical constructors only. */
  public static <T extends Record> Builder<T> record(Class<T> type) {
    return new Builder<>(type, MethodHandles.publicLookup());
  }

  /** Caller-declared access for its own records; re:form never opens a foreign class/module. */
  public static <T extends Record> Builder<T> record(MethodHandles.Lookup lookup, Class<T> type) {
    return new Builder<>(type, lookup);
  }

  private static final class Resolver {
    private final MethodHandles.Lookup lookup;
    private final Limits limits;
    private final Map<Class<?>, ValueType<?>> registered;
    private final Map<Type, ValueType<?>> resolved = new HashMap<>();
    private final Set<Class<?>> visiting = new HashSet<>();
    private int fields;

    Resolver(MethodHandles.Lookup lookup, Limits limits, Map<Class<?>, ValueType<?>> registered) {
      this.lookup = lookup;
      this.limits = limits;
      this.registered = registered;
    }

    private ValueType<?> type(Type javaType, int depth) {
      if (depth > limits.maximumDepth())
        throw new IllegalArgumentException("Record type exceeds its derivation depth");
      if (javaType instanceof Class<?> raw && registered.containsKey(raw))
        return registered.get(raw);
      var cached = resolved.get(javaType);
      if (cached != null) return cached;
      ValueType<?> result;
      if (javaType == boolean.class || javaType == Boolean.class) result = ValueTypes.bool();
      else if (javaType == byte.class || javaType == Byte.class)
        result = ValueTypes.byteInteger(Byte.MIN_VALUE, Byte.MAX_VALUE);
      else if (javaType == short.class || javaType == Short.class)
        result = ValueTypes.shortInteger(Short.MIN_VALUE, Short.MAX_VALUE);
      else if (javaType == float.class || javaType == Float.class)
        result = ValueTypes.floating(-Float.MAX_VALUE, Float.MAX_VALUE);
      else if (javaType == int.class || javaType == Integer.class)
        result = ValueTypes.integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
      else if (javaType == long.class || javaType == Long.class)
        result = ValueTypes.longInteger(Long.MIN_VALUE, Long.MAX_VALUE);
      else if (javaType == double.class || javaType == Double.class)
        result = ValueTypes.decimal(-Double.MAX_VALUE, Double.MAX_VALUE);
      else if (javaType == String.class) result = ValueTypes.text(limits.textLength());
      else if (javaType instanceof Class<?> raw && raw.isEnum()) result = enumeration(raw);
      else if (javaType instanceof Class<?> raw && raw.isRecord())
        result = record(raw.asSubclass(Record.class), Map.of(), depth);
      else if (javaType instanceof ParameterizedType parameterized
          && (parameterized.getRawType() == List.class || parameterized.getRawType() == Set.class)
          && parameterized.getActualTypeArguments().length == 1) {
        var element = type(parameterized.getActualTypeArguments()[0], depth + 1);
        result =
            parameterized.getRawType() == Set.class
                ? ValueTypes.set(element, limits.collectionSize())
                : ValueTypes.list(element, limits.collectionSize());
      } else if (javaType instanceof ParameterizedType parameterized
          && parameterized.getRawType() == Map.class
          && parameterized.getActualTypeArguments().length == 2
          && parameterized.getActualTypeArguments()[0] == String.class)
        result =
            ValueTypes.objectMap(
                type(String.class, depth + 1),
                type(parameterized.getActualTypeArguments()[1], depth + 1),
                limits.collectionSize());
      else
        throw new IllegalArgumentException(
            "Register an explicit value type for " + javaType.getTypeName());
      resolved.put(javaType, result);
      return result;
    }

    private <T extends Record> RecordType<T> record(
        Class<T> type, Map<String, FieldKey<?>> overrides, int depth) {
      if (depth > limits.maximumDepth() || !visiting.add(type))
        throw new IllegalArgumentException(
            "Recursive or too-deep record requires an explicit bounded type");
      try {
        var components = type.getRecordComponents();
        var names = Arrays.stream(components).map(RecordComponent::getName).toList();
        if (!names.containsAll(overrides.keySet()))
          throw new IllegalArgumentException("Unknown record component binding");
        if ((long) fields + components.length > limits.maximumFields())
          throw new IllegalArgumentException("Record field derivation budget exceeded");
        fields += components.length;
        var keys = new java.util.ArrayList<FieldKey<?>>();
        var getters = new java.util.ArrayList<MethodHandle>();
        for (var component : components) {
          keys.add(
              overrides.containsKey(component.getName())
                  ? overrides.get(component.getName())
                  : FieldKey.of(component.getName(), type(component.getGenericType(), depth + 1)));
          getters.add(
              lookup
                  .findVirtual(
                      type, component.getName(), MethodType.methodType(component.getType()))
                  .asType(MethodType.methodType(Object.class, Object.class)));
        }
        var parameterTypes =
            Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        var constructor =
            lookup
                .findConstructor(type, MethodType.methodType(void.class, parameterTypes))
                .asSpreader(Object[].class, components.length)
                .asType(MethodType.methodType(Object.class, Object[].class));
        var builder =
            RecordType.<T>builder(
                values -> {
                  var arguments = new Object[keys.size()];
                  for (int i = 0; i < arguments.length; i++) arguments[i] = values.get(keys.get(i));
                  return construct(type, constructor, arguments);
                });
        for (int i = 0; i < keys.size(); i++) bind(builder, keys.get(i), getters.get(i));
        return builder.build();
      } catch (NoSuchMethodException | IllegalAccessException inaccessible) {
        throw new IllegalArgumentException(
            "Record needs accessible getters/canonical constructor or a caller-supplied Lookup",
            inaccessible);
      } finally {
        visiting.remove(type);
      }
    }
  }

  private static <T, V> void bind(
      RecordType.Builder<T> builder, FieldKey<V> key, MethodHandle getter) {
    Function<T, V> accessor =
        value -> {
          try {
            @SuppressWarnings(
                "unchecked") // This key's decoder and the declared canonical constructor retain the
            // binding.
            V result = (V) (Object) getter.invokeExact((Object) value);
            return result;
          } catch (RuntimeException | Error failure) {
            throw failure;
          } catch (Throwable failed) {
            throw new IllegalArgumentException("Record accessor could not complete", failed);
          }
        };
    builder.field(key, accessor);
  }

  private static <T extends Record> T construct(
      Class<T> type, MethodHandle constructor, Object[] arguments) {
    try {
      return type.cast((Object) constructor.invokeExact(arguments));
    } catch (RuntimeException | Error failure) {
      throw failure;
    } catch (Throwable failed) {
      throw new IllegalArgumentException("Record constructor could not complete", failed);
    }
  }

  @SuppressWarnings({
    "unchecked",
    "rawtypes"
  }) // isEnum was checked before binding the declared enum class.
  private static ValueType<?> enumeration(Class<?> type) {
    return ValueTypes.enumeration((Class) type);
  }

  private RecordTypes() {}
}
