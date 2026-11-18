package design.inherent.reform.reference;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.DataResult;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.value.SnapshotContext;
import design.inherent.reform.value.SnapshotPolicy;
import design.inherent.reform.value.ValueType;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReferenceIndexTest {
  static final ReferenceLimits LIMITS = new ReferenceLimits(16, 8192, 4, 32, 2);
  static final ValueType<String> KEYS =
      ValueType.of(
          ValueTypes.text(128)
              .codec()
              .validate(
                  key ->
                      key.matches("[a-z]+:[a-z_0-9]+")
                          ? DataResult.success(key)
                          : DataResult.error(() -> "Malformed key")),
          ValueTypes.text(128).shape(),
          SnapshotPolicy.immutable());

  static ReferenceStamp stamp() {
    return new ReferenceStamp(
        UUID.randomUUID(), DocumentId.parse("fixture:references"), UUID.randomUUID(), "same-label");
  }

  static ReferenceIndex.Entry<String, List<String>> entry(String key, String search) {
    return new ReferenceIndex.Entry<>(key, List.of(key), key, search, Map.of("kind", "literal"));
  }

  static ReferenceIndex<String, List<String>> index(
      ReferenceStamp stamp, List<ReferenceIndex.Entry<String, List<String>>> entries) {
    return new ReferenceIndex<>(
        stamp, KEYS, ValueTypes.list(ValueTypes.text(128), 16).snapshotPolicy(), LIMITS, entries);
  }

  @Test
  void accentCaseAndCanonicalSpellingFoldForSearchOnlyAndCursorsRemainExact() {
    var index =
        index(
            stamp(),
            List.of(
                new ReferenceIndex.Entry<>(
                    "fixture:a", List.of("fixture:a"), "CAFÉ", "CAFÉ", Map.of()),
                entry("fixture:b", "cafe\u0301"),
                entry("fixture:c", "\u0130STANBUL"),
                entry("fixture:d", "\uD83C\uDF0A reef")));
    var first = index.search(new ReferenceQuery("cafe", 1));
    assertEquals("fixture:a", first.choices().getFirst().key());
    var next = index.search(new ReferenceQuery("cafe", 1, first.next()));
    assertEquals("fixture:b", next.choices().getFirst().key());
    assertThrows(
        PolicyRefusal.class, () -> index.search(new ReferenceQuery("CAFÉ", 1, first.next())));
    assertEquals("istanbul", SearchText.fold("\u0130STANBUL"));
    assertEquals("\uD83C\uDF0A reef", SearchText.fold("\uD83C\uDF0A REEF"));
    assertEquals(ReferenceResolution.Status.MALFORMED, index.resolve("fixture:A").status());
    assertEquals("CAFÉ", first.choices().getFirst().label());
  }

  @Test
  void resolvedEmptyUnknownMalformedAndUnavailableStatesRemainDistinct() {
    var stamp = stamp();
    var index =
        index(
            stamp,
            List.of(
                new ReferenceIndex.Entry<>(
                    "fixture:empty", List.of(), "Empty tag", "empty", Map.of("kind", "tag"))));
    var empty = index.resolve("fixture:empty");
    assertEquals(ReferenceResolution.Status.RESOLVED, empty.status());
    assertEquals(List.of(), empty.value().orElseThrow());
    assertEquals(stamp, empty.stamp());
    assertEquals(
        ReferenceResolution.Status.UNKNOWN_REFERENCE, index.resolve("fixture:missing").status());
    assertEquals(ReferenceResolution.Status.MALFORMED, index.resolve("not valid").status());
    for (var state :
        List.of(
            ReferencePage.Availability.MISSING_DEPENDENCY,
            ReferencePage.Availability.UNAVAILABLE_CONTEXT)) {
      var unavailable =
          ReferenceIndex.unavailable(
              stamp,
              KEYS,
              SnapshotPolicy.<List<String>>immutable(),
              LIMITS,
              state,
              "Not available");
      assertEquals(
          state == ReferencePage.Availability.MISSING_DEPENDENCY
              ? ReferenceResolution.Status.MISSING_DEPENDENCY
              : ReferenceResolution.Status.UNAVAILABLE_CONTEXT,
          unavailable.resolve("fixture:empty").status());
      var page = unavailable.search(new ReferenceQuery("", 1));
      assertEquals(state, page.availability());
      assertTrue(page.choices().isEmpty());
      assertFalse(page.exhausted());
    }
    var completeEmpty = index(stamp, List.of()).search(new ReferenceQuery("", 1));
    assertEquals(ReferencePage.Availability.READY, completeEmpty.availability());
    assertTrue(completeEmpty.exhausted());
  }

  @Test
  void zeroMatchScanPagesContinueWithoutInventingAnEmptySearchOrATotal() {
    var index =
        index(
            stamp(),
            List.of(
                entry("fixture:e", "no"),
                entry("fixture:a", "no"),
                entry("fixture:d", "no"),
                entry("fixture:c", "no"),
                entry("fixture:b", "no")));
    var first = index.search(new ReferenceQuery("target", 4));
    assertTrue(first.choices().isEmpty());
    assertEquals(2, first.examined());
    assertFalse(first.exhausted());
    assertEquals(2, first.next().orElseThrow().offset());
    var second = index.search(new ReferenceQuery("target", 4, first.next()));
    assertEquals(2, second.examined());
    assertFalse(second.exhausted());
    var third = index.search(new ReferenceQuery("target", 4, second.next()));
    assertEquals(1, third.examined());
    assertTrue(third.exhausted());
    assertTrue(third.next().isEmpty());
  }

  @Test
  void orderingFilteringAndSnapshotFilterBoundCursorsAreDeterministic() {
    var stamp = stamp();
    var index =
        index(
            stamp,
            List.of(
                entry("fixture:z", "Stone"),
                entry("fixture:a", "STONE"),
                entry("fixture:m", "other")));
    var first = index.search(new ReferenceQuery("stone", 1));
    assertEquals(
        List.of("fixture:a"), first.choices().stream().map(ReferencePage.Choice::key).toList());
    var next = index.search(new ReferenceQuery("stone", 1, first.next()));
    assertEquals(
        List.of("fixture:z"), next.choices().stream().map(ReferencePage.Choice::key).toList());
    assertTrue(next.exhausted());
    assertThrows(
        PolicyRefusal.class, () -> index.search(new ReferenceQuery("STONE", 1, first.next())));
    var other = index(stamp(), List.of(entry("fixture:a", "STONE")));
    assertThrows(
        PolicyRefusal.class, () -> other.search(new ReferenceQuery("stone", 1, first.next())));
    assertThrows(
        PolicyRefusal.class,
        () ->
            index.search(
                new ReferenceQuery(
                    "stone",
                    1,
                    Optional.of(new ReferenceQuery.Cursor(stamp.snapshot(), "stone", 4)))));
  }

  @Test
  void keysAndValuesAreOwnedAndCallerCopiesCannotMutateTheIndex() {
    var keys = ValueTypes.list(ValueTypes.integer(0, 10), 4);
    var key = new ArrayList<>(List.of(1));
    var value = new ArrayList<>(List.of("a"));
    var mutableCopies =
        new SnapshotPolicy<List<String>>() {
          @Override
          public List<String> freeze(List<String> source, SnapshotContext context) {
            return new ArrayList<>(source);
          }

          @Override
          public boolean equivalent(List<String> first, List<String> second) {
            return first.equals(second);
          }
        };
    var index =
        new ReferenceIndex<>(
            stamp(),
            keys,
            mutableCopies,
            LIMITS,
            List.of(new ReferenceIndex.Entry<>(key, value, "one", "one", Map.of())));
    key.clear();
    value.clear();
    var result = index.resolve(List.of(1)).value().orElseThrow();
    assertEquals(List.of("a"), result);
    result.clear();
    assertEquals(List.of("a"), index.resolve(List.of(1)).value().orElseThrow());
    var choice = index.search(new ReferenceQuery("", 1)).choices().getFirst();
    assertEquals(List.of(1), choice.key());
    assertThrows(UnsupportedOperationException.class, () -> choice.key().clear());
  }

  @Test
  void producerBoundsAndDuplicateOrPartialKeysNeverProduceAPartialIndex() {
    var calls = new AtomicInteger();
    var policy =
        new SnapshotPolicy<String>() {
          @Override
          public String freeze(String value, SnapshotContext context) {
            calls.incrementAndGet();
            return value;
          }

          @Override
          public boolean equivalent(String first, String second) {
            return first.equals(second);
          }
        };
    var entry = new ReferenceIndex.Entry<>("fixture:a", "a", "a", "a", Map.<String, String>of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReferenceIndex<>(
                stamp(),
                KEYS,
                policy,
                new ReferenceLimits(1, 8192, 1, 16, 1),
                List.of(entry, entry)));
    assertEquals(0, calls.get());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReferenceIndex<>(
                stamp(), KEYS, policy, new ReferenceLimits(1, 1, 1, 16, 1), List.of(entry)));
    assertEquals(0, calls.get());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReferenceIndex<>(stamp(), KEYS, policy, LIMITS, List.of(entry, entry)));
    var partial =
        ValueType.of(
            KEYS.codec()
                .flatXmap(key -> DataResult.error(() -> "partial", key), DataResult::success),
            KEYS.shape(),
            KEYS.snapshotPolicy());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReferenceIndex<>(stamp(), partial, policy, LIMITS, List.of(entry)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReferenceIndex<>(
                stamp(),
                KEYS,
                policy,
                LIMITS,
                new SnapshotContext.Limits(8, 1),
                ReferencePage.Availability.READY,
                "",
                List.of(entry)));
  }
}
