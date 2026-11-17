package design.inherent.reform.operation;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Declared field selectors shared by frontends. Selection is exact lookup, never a JSON patch. */
public final class FieldIndex {
  public record Field(FieldPath path, String selector, ValueType<?> type) {
    public boolean scalar() {
      return type.shape() instanceof ValueShape.Scalar;
    }
  }

  private final Map<String, Field> selectors = new LinkedHashMap<>();
  private final Map<FieldPath, Field> paths = new LinkedHashMap<>();
  private final int maximumEntries, maximumDepth;
  private int visited;

  public FieldIndex(ValueType<?> root, int maximumEntries, int maximumDepth) {
    if (maximumEntries < 1 || maximumDepth < 1 || maximumDepth > 256)
      throw new IllegalArgumentException("Invalid field-index limits");
    this.maximumEntries = maximumEntries;
    this.maximumDepth = maximumDepth;
    visit(root, FieldPath.ROOT, List.of());
  }

  private void visit(ValueType<?> type, FieldPath path, List<String> names) {
    if (++visited > maximumEntries || names.size() > maximumDepth)
      throw new IllegalArgumentException("Field graph exceeds its declared limits");
    if (type.shape() instanceof ValueShape.ObjectValue object) {
      for (var field : object.fields()) {
        var next = new ArrayList<>(names);
        next.add(escape(field.id()));
        visit(field.type(), path.field(field.id()), next);
      }
    } else {
      String selector = names.isEmpty() ? "$" : String.join(".", names);
      var field = new Field(path, selector, type);
      if (selectors.putIfAbsent(selector, field) != null)
        throw new IllegalArgumentException("Declared field selectors collide");
      paths.put(path, field);
    }
  }

  private static String escape(String name) {
    var result = new StringBuilder();
    for (int index = 0; index < name.length(); index++) {
      char value = name.charAt(index);
      if (value == '.' || value == '\\' || value == '$') result.append('\\').append(value);
      else if (Character.isISOControl(value))
        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) value));
      else result.append(value);
    }
    return result.toString();
  }

  public Optional<Field> field(String selector) {
    return Optional.ofNullable(selectors.get(selector));
  }

  public Optional<Field> field(FieldPath path) {
    return Optional.ofNullable(paths.get(path));
  }

  public List<Field> fields() {
    return List.copyOf(selectors.values());
  }

  public Map<String, Field> selectors() {
    return Collections.unmodifiableMap(selectors);
  }
}
