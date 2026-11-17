package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.FieldPath;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/** A small independent scalar/history model exercises delayed-save and reload interleavings. */
class EditSessionProperties {
  private static final FieldPath COUNT = FieldPath.ROOT.field("count");

  private record Change(String before, String after) {}

  private static final class Model {
    String baseline = "2", current = "2", submitted;
    final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    void edit(String value) {
      if (value.equals(current)) return;
      undo.addLast(new Change(current, value));
      current = value;
      redo.clear();
    }

    void undo() {
      if (!undo.isEmpty()) {
        var change = undo.removeLast();
        current = change.before();
        redo.addLast(change);
      }
    }

    void redo() {
      if (!redo.isEmpty()) {
        var change = redo.removeLast();
        current = change.after();
        undo.addLast(change);
      }
    }

    boolean valid() {
      return current.matches("[0-9]+");
    }
  }

  @Property(tries = 120)
  void editsHistoryAndSaveAcknowledgementsMatchReference(
      @ForAll @Size(max = 60) List<@IntRange(min = 0, max = 999) Integer> operations) {
    compareWithReference(operations);
  }

  @Example
  void noOpResetPreservesRedo() {
    compareWithReference(List.of(0, 2, 14, 3));
  }

  private void compareWithReference(List<Integer> operations) {
    var session = EditSessionTest.session();
    var model = new Model();
    Submission<DraftTreeTest.Profile> pending = null;
    int revision = 1;
    for (int operation : operations) {
      switch (operation % 10) {
        case 0, 1 -> {
          String value = operation % 17 == 0 ? "-" : String.valueOf(operation / 10);
          model.edit(value);
          session.setRaw(COUNT, value);
        }
        case 2 -> {
          model.undo();
          session.undo();
        }
        case 3 -> {
          model.redo();
          session.redo();
        }
        case 4 -> {
          model.edit("2");
          session.reset();
        }
        case 5 -> {
          model.edit(model.baseline);
          session.revert();
        }
        case 6 -> {
          var prepared = session.prepareSubmission();
          if (pending == null && model.valid()) {
            assertEquals(EditSession.PreparationState.READY, prepared.state());
            pending = prepared.submission().orElseThrow();
            session.markInFlight(pending);
            model.submitted = model.current;
          } else assertTrue(prepared.submission().isEmpty());
        }
        case 7 -> {
          if (pending != null) {
            model.baseline = model.submitted;
            session.acknowledge(
                pending,
                new DraftTreeTest.Profile(
                    "Initial", Integer.parseInt(model.submitted), List.of(10, 20)),
                "saved-" + ++revision);
            pending = null;
            model.submitted = null;
          }
        }
        case 8 -> {
          if (pending != null) {
            session.failed(pending, EditSession.Failure.REFUSED);
            pending = null;
            model.submitted = null;
          }
        }
        case 9 -> {
          model.baseline = model.current = String.valueOf(operation / 10);
          model.undo.clear();
          model.redo.clear();
          model.submitted = null;
          session.reload(
              new DraftTreeTest.Profile(
                  "Initial", Integer.parseInt(model.current), List.of(10, 20)),
              "saved-" + ++revision);
          pending = null;
        }
        default -> throw new AssertionError();
      }
      assertEquals(
          model.current,
          ((DraftNode.Scalar) session.snapshot().draft().at(COUNT)).raw(),
          operations.toString());
      assertEquals(!model.current.equals(model.baseline), session.dirty(), operations.toString());
      assertEquals(
          model.valid(),
          session.snapshot().inspect().candidate().isPresent(),
          operations.toString());
    }
  }
}
