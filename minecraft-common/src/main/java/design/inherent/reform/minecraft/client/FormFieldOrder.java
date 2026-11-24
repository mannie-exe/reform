package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.operation.DocumentActions;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Presentation-only ranks. Captured values and order-sensitive codecs remain untouched. */
final class FormFieldOrder {
  static Map<FieldPath, Integer> admit(DocumentActions<?> actions, List<FieldPath> paths) {
    var order = new LinkedHashMap<FieldPath, Integer>();
    for (var path : List.copyOf(paths)) {
      if (actions.fields().field(path).isEmpty() || order.putIfAbsent(path, order.size()) != null)
        throw new IllegalArgumentException(
            "Review order requires distinct declared field identities");
    }
    return Map.copyOf(order);
  }

  static <E> List<E> sort(
      List<E> entries, Map<FieldPath, Integer> order, Function<E, FieldPath> path) {
    return entries.stream()
        .sorted(
            Comparator.comparingInt(
                entry -> order.getOrDefault(path.apply(entry), Integer.MAX_VALUE)))
        .toList();
  }

  private FormFieldOrder() {}
}
