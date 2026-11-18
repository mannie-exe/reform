package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

class DraftEditProperties {
  private record Entry(UUID id, int value) {}

  @Property(tries = 100, seed = "2026111805")
  void anchoredOperationsMatchIndependentPositionModelAndRejectObsoleteActions(@ForAll long seed) {
    var random = new Random(seed);
    var type = ValueTypes.list(ValueTypes.integer(0, 100), 8);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:sequence"), 1, type, () -> List.of(1, 2, 3));
    var json = new JsonLimits(32768, 64, 4096, 8192, 128, 4096);
    try (var session =
        EditSession.fromDraft(
            definition,
            UUID.randomUUID(),
            "saved",
            DraftTree.from(type, definition.defaults(), DraftLimits.CONFIGURATION),
            json,
            DraftLimits.CONFIGURATION)) {
      var model = new ArrayList<Entry>();
      var initial = (DraftNode.Sequence) session.snapshot().draft().root();
      for (int i = 0; i < 3; i++) model.add(new Entry(initial.rows().get(i).id(), i + 1));
      for (int step = 0; step < 32; step++) {
        var before = session.snapshot();
        var stale = new DraftEdit(before.target(FieldPath.ROOT), new DraftEdit.Repair());
        int operation = random.nextInt(5);
        if (model.isEmpty()) operation = 0;
        if (model.size() == 8 && (operation == 0 || operation == 4)) operation = 1;
        if (operation == 0) {
          int position = random.nextInt(model.size() + 1);
          var anchor =
              position == model.size()
                  ? Optional.<UUID>empty()
                  : Optional.of(model.get(position).id());
          session.execute(
              new DraftEdit(before.target(FieldPath.ROOT), new DraftEdit.InsertRow(anchor)));
          int value = random.nextInt(101);
          var added = ((DraftNode.Sequence) session.snapshot().draft().root()).rows().get(position);
          session.execute(
              new DraftEdit(
                  session.snapshot().target(FieldPath.ROOT.row(added.id())),
                  new DraftEdit.SetRaw(Integer.toString(value))));
          model.add(position, new Entry(added.id(), value));
        } else if (operation == 1) {
          int index = random.nextInt(model.size());
          var removed = model.remove(index);
          session.execute(
              new DraftEdit(before.target(FieldPath.ROOT), new DraftEdit.RemoveRow(removed.id())));
        } else if (operation == 2) {
          int from = random.nextInt(model.size());
          int to = random.nextInt(model.size() + 1);
          var selected = model.get(from);
          var anchor =
              to == model.size() ? Optional.<UUID>empty() : Optional.of(model.get(to).id());
          session.execute(
              new DraftEdit(
                  before.target(FieldPath.ROOT), new DraftEdit.MoveRow(selected.id(), anchor)));
          if (to != from) {
            model.remove(from);
            model.add(to > from ? to - 1 : to, selected);
          }
        } else if (operation == 3) {
          int index = random.nextInt(model.size());
          var old = model.get(index);
          int value = random.nextInt(101);
          session.execute(
              new DraftEdit(
                  before.target(FieldPath.ROOT.row(old.id())),
                  new DraftEdit.SetRaw(Integer.toString(value))));
          model.set(index, new Entry(old.id(), value));
        } else {
          int index = random.nextInt(model.size());
          var original = model.get(index);
          session.execute(
              new DraftEdit(
                  before.target(FieldPath.ROOT),
                  new DraftEdit.DuplicateRow(original.id(), Optional.empty())));
          var duplicate = ((DraftNode.Sequence) session.snapshot().draft().root()).rows().getLast();
          assertTrue(model.stream().noneMatch(entry -> entry.id().equals(duplicate.id())));
          model.add(new Entry(duplicate.id(), original.value()));
        }
        var after = session.snapshot();
        assertEquals(
            model.stream().map(Entry::value).toList(),
            after.inspect().candidate().orElseThrow().value());
        assertEquals(
            model.stream().map(Entry::id).toList(),
            ((DraftNode.Sequence) after.draft().root())
                .rows().stream().map(DraftNode.Row::id).toList());
        if (after.version() != before.version()) {
          var refusal = assertThrows(DraftEditRefusal.class, () -> session.execute(stale));
          assertEquals("edit.stale_target", refusal.report().issues().getFirst().code());
          assertEquals(after.version(), session.snapshot().version());
        }
      }
    }
  }
}
