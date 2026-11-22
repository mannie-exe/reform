package design.inherent.reform.minecraft.client;

import java.util.Map;
import net.minecraft.network.chat.Component;

/** Bounded display of already admitted owner facts; no lookup or interpretation of their values. */
final class ReferencePreview {
  static Component format(Map<String, String> values) {
    var text = new StringBuilder();
    for (var entry : values.entrySet()) {
      if (!text.isEmpty()) text.append("; ");
      text.append(clip(entry.getKey(), 64)).append(": ").append(clip(entry.getValue(), 256));
      if (text.length() > 512)
        return Component.literal(clip(text.toString(), 512))
            .append(" ")
            .append(
                Component.translatableWithFallback(
                    "reform.reference.abbreviated", "(preview abbreviated)"));
    }
    return Component.literal(text.toString());
  }

  private static String clip(String value, int maximum) {
    int end = 0;
    for (int count = 0; count < maximum && end < value.length(); count++)
      end += Character.charCount(value.codePointAt(end));
    return end == value.length() ? value : value.substring(0, end) + "…";
  }

  private ReferencePreview() {}
}
