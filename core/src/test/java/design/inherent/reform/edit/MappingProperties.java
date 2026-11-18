package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

/** Independent raw-row/history model, including duplicate keys that cannot yet be a Map. */
class MappingProperties {
  private static final JsonLimits JSON = new JsonLimits(16384, 32, 2048, 1024, 128, 1024);
  private static final DocumentDefinition<Map<String, Integer>> DEFINITION =
      new DocumentDefinition<>(
          DocumentId.parse("fixture:generated_map"),
          1,
          ValueTypes.objectMap(ValueTypes.text(16), ValueTypes.integer(0, 100), 8),
          Map::of);

  private record Row(UUID id, String key, String value) {}

  private record Change(List<Row> before, List<Row> after) {}

  private static final class Model {
    List<Row> rows = List.of();
    final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    void edit(List<Row> next) {
      if (rows.equals(next)) return;
      undo.addLast(new Change(rows, List.copyOf(next)));
      rows = List.copyOf(next);
      redo.clear();
    }

    void undo() {
      if (!undo.isEmpty()) {
        var change = undo.removeLast();
        rows = change.before();
        redo.addLast(change);
      }
    }

    void redo() {
      if (!redo.isEmpty()) {
        var change = redo.removeLast();
        rows = change.after();
        undo.addLast(change);
      }
    }
  }

  @Property(tries = 100, seed = "2026111701")
  void rowEditsAndHistoryMatchAnIndependentRawModel(
      @ForAll @Size(max = 50) List<@IntRange(min = 0, max = 999) Integer> operations) {
    try (var session =
        new EditSession<>(
            DEFINITION, UUID.randomUUID(), "initial", Map.of(), JSON, DraftLimits.CONFIGURATION)) {
      var model = new Model();
      for (int operation : operations) {
        var next = new ArrayList<>(model.rows);
        int choice = operation % 7;
        if (choice == 0 && next.size() < 8) {
          var row =
              new Row(UUID.randomUUID(), "key" + operation % 4, String.valueOf(operation % 10));
          next.add(row);
          model.edit(next);
          var raw =
              new DraftNode.ObjectValue(
                  Map.of(
                      "key",
                      new DraftNode.Scalar(row.key()),
                      "value",
                      new DraftNode.Scalar(row.value())));
          session.edit(
              FieldPath.ROOT,
              n ->
                  ((DraftNode.Mapping) n)
                      .insert(
                          ((DraftNode.Mapping) n).rows().size(), new DraftNode.Row(row.id(), raw)),
              "Insert");
        } else if (choice >= 1 && choice <= 4 && !next.isEmpty()) {
          int index = (operation / 7) % next.size();
          var row = next.get(index);
          var path = FieldPath.ROOT.row(row.id());
          switch (choice) {
            case 1 -> {
              next.remove(index);
              session.edit(FieldPath.ROOT, n -> ((DraftNode.Mapping) n).remove(row.id()), "Remove");
            }
            case 2 -> {
              int target = (operation / 11) % next.size();
              next.remove(index);
              next.add(target, row);
              session.edit(
                  FieldPath.ROOT, n -> ((DraftNode.Mapping) n).move(row.id(), target), "Move");
            }
            case 3 -> {
              var key = "key" + operation % 4;
              next.set(index, new Row(row.id(), key, row.value()));
              session.setRaw(path.append(FieldPath.Part.KEY), key);
            }
            case 4 -> {
              var value = operation % 3 == 0 ? "-" : String.valueOf(operation % 10);
              next.set(index, new Row(row.id(), row.key(), value));
              session.setRaw(path.append(FieldPath.Part.VALUE), value);
            }
            default -> throw new AssertionError();
          }
          model.edit(next);
        } else if (choice == 5) {
          model.undo();
          session.undo();
        } else if (choice == 6) {
          model.redo();
          session.redo();
        }
        var actual = ((DraftNode.Mapping) session.snapshot().draft().root()).rows();
        assertEquals(model.rows.size(), actual.size(), operations.toString());
        for (int i = 0; i < actual.size(); i++) {
          var row = model.rows.get(i);
          assertEquals(row.id(), actual.get(i).id());
          assertEquals(
              row.key(),
              ((DraftNode.Scalar)
                      session
                          .snapshot()
                          .draft()
                          .at(FieldPath.ROOT.row(row.id()).append(FieldPath.Part.KEY)))
                  .raw());
          assertEquals(
              row.value(),
              ((DraftNode.Scalar)
                      session
                          .snapshot()
                          .draft()
                          .at(FieldPath.ROOT.row(row.id()).append(FieldPath.Part.VALUE)))
                  .raw());
        }
        var expected = new LinkedHashMap<String, Integer>();
        boolean valid = true;
        for (var row : model.rows) {
          if (!row.value().matches("[0-9]+")) {
            valid = false;
            continue;
          }
          if (expected.putIfAbsent(row.key(), Integer.parseInt(row.value())) != null) valid = false;
        }
        var inspected = session.snapshot().inspect();
        assertEquals(valid, inspected.candidate().isPresent(), operations.toString());
        if (valid) assertEquals(expected, inspected.candidate().orElseThrow().value());
        assertEquals(!model.rows.isEmpty(), session.dirty());
        assertEquals(model.undo.size() + model.redo.size(), session.historySize());
      }
    }
  }

  @Property(tries = 80, seed = "2026111702")
  void reorderedAcknowledgementsKeepKeyIdentityAndLateInvalidDrafts(
      @ForAll @Size(min = 2, max = 8)
          Map<@StringLength(min = 1, max = 8) String, @IntRange(min = 0, max = 100) Integer>
              input) {
    var defaults = new LinkedHashMap<>(input);
    try (var session =
        new EditSession<>(
            DEFINITION, UUID.randomUUID(), "first", defaults, JSON, DraftLimits.CONFIGURATION)) {
      var rows = ((DraftNode.Mapping) session.snapshot().draft().root()).rows();
      var submitted = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submitted);
      var late = rows.getFirst();
      var latePath = FieldPath.ROOT.row(late.id()).append(FieldPath.Part.VALUE);
      session.setRaw(latePath, "-");
      var reversed = new LinkedHashMap<String, Integer>();
      for (var row : rows.reversed()) {
        var key =
            ((DraftNode.Scalar)
                    session
                        .snapshot()
                        .draft()
                        .at(FieldPath.ROOT.row(row.id()).append(FieldPath.Part.KEY)))
                .raw();
        reversed.put(key, input.get(key));
      }
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submitted, reversed, "second"));
      assertEquals(
          rows.reversed().stream().map(DraftNode.Row::id).toList(),
          ((DraftNode.Mapping) session.baseline().root())
              .rows().stream().map(DraftNode.Row::id).toList());
      assertEquals("-", ((DraftNode.Scalar) session.snapshot().draft().at(latePath)).raw());
      assertTrue(session.snapshot().inspect().candidate().isEmpty());
      assertTrue(session.undo());
      assertFalse(session.dirty());
      assertEquals(input, session.snapshot().inspect().candidate().orElseThrow().value());
    }
  }
}
