package design.inherent.reform.minecraft.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;

/**
 * Owner-published presentation metadata, never a reference resolver or a selection capability.
 * Prepare/replace titles at the owner's resource boundary; rendering only observes a cached map.
 */
public final class ReferenceTitles {
  static final int MAXIMUM_ENTRIES = 16_384;
  static final int MAXIMUM_CHARACTERS = 1_048_576;

  static final class Snapshot {
    private final Map<String, Component> titles;

    Snapshot(Map<String, Component> titles) {
      this.titles = titles;
    }

    Optional<Component> title(String key) {
      return Optional.ofNullable(titles.get(key)).map(Component::copy);
    }
  }

  private final AtomicReference<Snapshot> current = new AtomicReference<>();

  public ReferenceTitles(Map<String, Component> titles) {
    replace(titles);
  }

  /**
   * Publish one complete copied map. Invalid/oversized replacements leave the old map unchanged.
   * This performs no source lookup, resource reload, draft edit or persistence.
   */
  public void replace(Map<String, Component> titles) {
    Objects.requireNonNull(titles);
    if (titles.size() > MAXIMUM_ENTRIES)
      throw new IllegalArgumentException("Reference titles exceed the entry limit");
    var copy = new LinkedHashMap<String, Component>();
    long characters = 0;
    for (var entry : titles.entrySet()) {
      var key = Objects.requireNonNull(entry.getKey());
      var title = Objects.requireNonNull(entry.getValue()).copy();
      String text = title.getString();
      if (key.length() > 32768 || text.isBlank() || text.length() > 1024)
        throw new IllegalArgumentException("Reference keys and titles must be bounded");
      characters += key.length() + text.length();
      if (characters > MAXIMUM_CHARACTERS)
        throw new IllegalArgumentException("Reference titles exceed the text limit");
      copy.put(key, title);
    }
    current.set(new Snapshot(Map.copyOf(copy)));
  }

  Snapshot snapshot() {
    return current.get();
  }
}
