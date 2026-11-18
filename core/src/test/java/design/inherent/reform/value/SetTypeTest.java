package design.inherent.reform.value;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import design.inherent.reform.serialization.ExactCodecs;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SetTypeTest {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);

  @Test
  void unorderedMembershipHasExplicitListEncodingAndOwnedIterationOrder() throws Exception {
    var type = ValueTypes.set(ValueTypes.integer(0, 100), 8);
    var decoded = JsonInput.read("[3,1,2]", JSON).decode(type).value().orElseThrow();
    assertEquals(Set.of(1, 2, 3), decoded);
    assertEquals(List.of(3, 1, 2), List.copyOf(decoded));
    assertEquals(
        "[3,1,2]", type.codec().encodeStart(JsonOps.INSTANCE, decoded).getOrThrow().toString());
    assertTrue(type.equivalent(decoded, new LinkedHashSet<>(List.of(2, 1, 3))));
    assertTrue(((ValueShape.Sequence) type.shape()).unique());
    assertEquals(
        ValueShape.CollectionOrder.UNORDERED, ((ValueShape.Sequence) type.shape()).order());
    assertEquals(Set.of(), JsonInput.read("[]", JSON).decode(type).value().orElseThrow());
    assertThrows(UnsupportedOperationException.class, decoded::clear);
  }

  @Test
  void duplicateDecodedMembersAndWrongKindsNeverBecomeSuccessfulSets() throws Exception {
    var type = ValueTypes.set(ValueTypes.integer(0, 100), 8);
    for (String json : List.of("[1,1.00]", "[1,\"2\"]", "[1,2.5]", "[1,null]", "{}")) {
      assertTrue(JsonInput.read(json, JSON).decode(type).value().isEmpty(), json);
      assertTrue(JsonInput.read(json, JSON).decode(type.codec()).result().isEmpty(), json);
    }
    var lower =
        ValueType.of(
            ExactCodecs.STRING.xmap(s -> s.toLowerCase(Locale.ROOT), s -> s),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    assertTrue(
        JsonInput.read("[\"A\",\"a\"]", JSON).decode(ValueTypes.set(lower, 8)).value().isEmpty());
  }

  @Test
  void sizeBoundsApplyToDecodeEncodeAndSnapshot() throws Exception {
    var type = ValueTypes.set(ValueTypes.integer(0, 100), 1);
    assertTrue(JsonInput.read("[1,2]", JSON).decode(type).value().isEmpty());
    assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, Set.of(1, 2)).error().isPresent());
    assertThrows(IllegalArgumentException.class, () -> type.freeze(Set.of(1, 2)));
    assertThrows(IllegalArgumentException.class, () -> ValueTypes.set(ValueTypes.bool(), -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ValueShape.Sequence(
                ValueTypes.bool(), 8, false, ValueShape.CollectionOrder.UNORDERED));
    assertEquals(Set.of(), ValueTypes.set(ValueTypes.bool(), 0).freeze(Set.of()));
  }

  @Test
  void compositeMemberSnapshotsAreDeepOwned() {
    var type = ValueTypes.set(ValueTypes.list(ValueTypes.text(16), 4), 4);
    var member = new ArrayList<>(List.of("a"));
    var input = new LinkedHashSet<List<String>>();
    input.add(member);
    var frozen = type.freeze(input);
    member.clear();
    input.clear();
    assertEquals(Set.of(List.of("a")), frozen);
    assertThrows(UnsupportedOperationException.class, () -> frozen.iterator().next().clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            type.freeze(
                Set.of(List.of("a")), new SnapshotContext(new SnapshotContext.Limits(8, 2))));
  }

  @Test
  void unstableSnapshotAndCodecIdentityAreRefused() {
    var copied =
        ValueType.of(
            ExactCodecs.STRING,
            ValueTypes.text(16).shape(),
            (String s, SnapshotContext context) -> s.toLowerCase(Locale.ROOT));
    assertThrows(
        IllegalArgumentException.class, () -> ValueTypes.set(copied, 8).freeze(Set.of("A")));
    var encoded =
        ValueType.of(
            ExactCodecs.STRING.xmap(s -> s, s -> s.toLowerCase(Locale.ROOT)),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    assertTrue(
        ValueTypes.set(encoded, 8)
            .codec()
            .encodeStart(JsonOps.INSTANCE, Set.of("A"))
            .error()
            .isPresent());
  }

  @Test
  void partialMemberRecoveryIsNotPromotedToCompleteSuccess() throws Exception {
    var partial =
        ValueType.of(
            ExactCodecs.STRING.flatXmap(
                s -> DataResult.error(() -> "unavailable", s), DataResult::success),
            ValueTypes.text(16).shape(),
            SnapshotPolicy.<String>immutable());
    var type = ValueTypes.set(partial, 8);
    assertTrue(JsonInput.read("[\"a\"]", JSON).decode(type).value().isEmpty());
    assertTrue(type.codec().encodeStart(JsonOps.INSTANCE, Set.of("a")).error().isPresent());
  }
}
