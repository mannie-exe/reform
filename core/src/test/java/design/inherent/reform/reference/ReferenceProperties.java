package design.inherent.reform.reference;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class ReferenceProperties {
  @Property(tries = 100, seed = "2026111804")
  void boundedPageTraversalMatchesAnIndependentCompleteSearch(
      @ForAll @IntRange(min = 0, max = 24) int count,
      @ForAll @IntRange(min = 1, max = 5) int scan,
      @ForAll @IntRange(min = 1, max = 8) int pageSize,
      @ForAll boolean target,
      @ForAll boolean reverse) {
    var entries = new ArrayList<ReferenceIndex.Entry<String, String>>();
    for (int i = 0; i < count; i++) {
      String key = "fixture:item_" + i;
      entries.add(
          new ReferenceIndex.Entry<>(key, key, key, i % 3 == 0 ? "TARGET" : "other", Map.of()));
    }
    var expected =
        entries.stream()
            .filter(
                e -> e.searchText().toLowerCase(Locale.ROOT).contains(target ? "target" : "other"))
            .map(ReferenceIndex.Entry::key)
            .sorted()
            .toList();
    if (reverse)
      entries.sort(Comparator.comparing(ReferenceIndex.Entry<String, String>::key).reversed());
    var index =
        new ReferenceIndex<>(
            ReferenceIndexTest.stamp(),
            ValueTypes.text(128),
            design.inherent.reform.value.SnapshotPolicy.<String>immutable(),
            new ReferenceLimits(32, 32768, 8, 32, scan),
            entries);
    var actual = new ArrayList<String>();
    var query = new ReferenceQuery(target ? "target" : "other", pageSize);
    int examined = 0;
    for (int step = 0; ; step++) {
      assertTrue(step <= count, "Each continuation must make bounded progress");
      var page = index.search(query);
      assertTrue(page.examined() <= scan);
      assertTrue(page.choices().size() <= pageSize);
      examined += page.examined();
      actual.addAll(page.choices().stream().map(ReferencePage.Choice::key).toList());
      if (page.exhausted()) break;
      query = new ReferenceQuery(query.filter(), pageSize, page.next());
    }
    assertEquals(count, examined);
    assertEquals(expected, actual);
    assertEquals(actual.size(), new java.util.HashSet<>(actual).size());
  }
}
