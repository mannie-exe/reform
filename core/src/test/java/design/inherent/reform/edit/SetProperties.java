package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class SetProperties {
  @Property(tries = 100, seed = "2026111703")
  void memberOrderAndLateEditsFollowAnIndependentMembershipSet(
      @ForAll @Size(min = 2, max = 8) Set<@IntRange(min = 0, max = 100) Integer> input) {
    var type = ValueTypes.set(ValueTypes.integer(0, 100), 8);
    var definition =
        new DocumentDefinition<>(DocumentId.parse("fixture:generated_set"), 1, type, Set::of);
    var json = new JsonLimits(8192, 16, 1024, 128, 64, 1024);
    try (var session =
        new EditSession<>(
            definition,
            UUID.randomUUID(),
            "one",
            new LinkedHashSet<>(input),
            json,
            DraftLimits.CONFIGURATION)) {
      var rows = ((DraftNode.Sequence) session.snapshot().draft().root()).rows();
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submitted);
      var original = rows.getFirst();
      var target = FieldPath.ROOT.row(original.id());
      session.edit(
          FieldPath.ROOT,
          n -> ((DraftNode.Sequence) n).move(original.id(), rows.size() - 1),
          "Reorder");
      assertFalse(session.dirty());
      session.setRaw(target, "-");
      var reversed = new LinkedHashSet<Integer>();
      for (var row : rows.reversed())
        reversed.add(Integer.parseInt(((DraftNode.Scalar) row.value()).raw()));
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submitted, reversed, "two"));
      assertEquals(
          rows.reversed().stream().map(DraftNode.Row::id).toList(),
          ((DraftNode.Sequence) session.baseline().root())
              .rows().stream().map(DraftNode.Row::id).toList());
      assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(target)).raw());
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      session.undo();
      assertFalse(session.dirty());
      assertEquals(input, session.snapshot().inspect().candidate().orElseThrow().value());
      session.undo();
      assertFalse(session.dirty());
      assertEquals(input, session.snapshot().inspect().candidate().orElseThrow().value());
      session.redo();
      session.redo();
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
    }
  }
}
