package design.inherent.reform.minecraft.client.ui;

import java.util.*;

/** One bounded admitted window. A missing total does not mean the window is the whole source. */
public record ChoiceListData(String generation, List<Item> items, OptionalLong total) {
  public record Item(String key, String title, String detail, boolean enabled) {
    public Item {
      Objects.requireNonNull(key);
      Objects.requireNonNull(title);
      Objects.requireNonNull(detail);
      if (key.isBlank() || key.length() > 32768 || title.length() > 4096 || detail.length() > 4096)
        throw new IllegalArgumentException("Choice metadata exceeds its allowance");
    }
  }

  public ChoiceListData {
    Objects.requireNonNull(generation);
    Objects.requireNonNull(total);
    items = List.copyOf(items);
    if (generation.length() > 256
        || items.size() > 4096
        || (total.isPresent() && total.getAsLong() < items.size()))
      throw new IllegalArgumentException("Invalid choice window allowance or total");
    var keys = new HashSet<String>();
    for (var item : items)
      if (!keys.add(item.key())) throw new IllegalArgumentException("Duplicate choice key");
  }
}
