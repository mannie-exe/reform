package design.inherent.reform.presentation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded requested identities; the owner resolves removed destinations against its current data.
 */
public final class NavigationHistory<K> {
  private final int maximumEntries;
  private final List<K> entries = new ArrayList<>();
  private int cursor;

  public NavigationHistory(K initial, int maximumEntries) {
    if (maximumEntries < 1) throw new IllegalArgumentException("History needs a positive bound");
    this.maximumEntries = maximumEntries;
    entries.add(Objects.requireNonNull(initial));
  }

  public K selected() {
    return entries.get(cursor);
  }

  public void select(K identity) {
    Objects.requireNonNull(identity);
    if (selected().equals(identity)) return;
    entries.subList(cursor + 1, entries.size()).clear();
    entries.add(identity);
    if (entries.size() > maximumEntries) entries.removeFirst();
    cursor = entries.size() - 1;
  }

  public boolean canBack() {
    return cursor > 0;
  }

  public boolean canForward() {
    return cursor + 1 < entries.size();
  }

  public K back() {
    if (canBack()) cursor--;
    return selected();
  }

  public K forward() {
    if (canForward()) cursor++;
    return selected();
  }
}
