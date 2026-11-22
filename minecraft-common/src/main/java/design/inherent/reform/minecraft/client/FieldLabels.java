package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import net.minecraft.network.chat.Component;

/** Shared native captions; translated field identity stays independent of row UUIDs and labels. */
final class FieldLabels {
  static String key(DocumentId document, FieldPath path) {
    var parts =
        path.segments().stream()
            .map(
                segment ->
                    switch (segment) {
                      case FieldPath.Field field -> escape(field.id());
                      case FieldPath.Row ignored -> "%row";
                      case FieldPath.Variant variant -> "%variant:" + escape(variant.id());
                      case FieldPath.Part part -> part == FieldPath.Part.KEY ? "%key" : "%value";
                    })
            .toList();
    return document.namespace()
        + ":settings/"
        + escape(document.path())
        + "/fields/"
        + String.join("/", parts);
  }

  static Component input(DocumentId document, FieldPath path, Component root) {
    for (int i = path.segments().size() - 1; i >= 0; i--)
      if (path.segments().get(i) instanceof FieldPath.Field field)
        return Component.translatableWithFallback(
            key(document, new FieldPath(path.segments().subList(0, i + 1))), readable(field.id()));
    return root.copy();
  }

  private static String escape(String value) {
    return value.replace("%", "%25").replace("/", "%2F");
  }

  static String readable(String id) {
    var words = id.replace('_', ' ').replace('-', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
    return words.isEmpty() ? id : Character.toUpperCase(words.charAt(0)) + words.substring(1);
  }

  private FieldLabels() {}
}
