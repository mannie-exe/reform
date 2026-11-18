package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.edit.DraftEdit;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.UUID;

/** Exact public display mapping leaves the stored integer codec and raw draft authoritative. */
final class UnitsEditing {
  static void verify() throws Exception {
    var display = NumericDisplay.ticks(BigDecimal.valueOf(20));
    var type = ValueTypes.numericDisplay(ValueTypes.integer(0, 12000), display);
    var definition = new DocumentDefinition<>(DocumentId.parse("example:units"), 1, type, () -> 20);
    var json = new JsonLimits(8192, 32, 1024, 512, 128, 4096);
    try (var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "one",
            JsonInput.read("20", json),
            json,
            DraftLimits.CONFIGURATION)) {
      long version = session.snapshot().version();
      var seconds = display.fromStored("20", "seconds");
      if (seconds.state() != NumericDisplay.State.EXACT
          || !seconds.text().equals("1")
          || session.snapshot().version() != version
          || session.dirty())
        throw new AssertionError("Display conversion changed the canonical draft");
      edit(session, "0.125");
      if (session.snapshot().inspect().candidate().isPresent())
        throw new AssertionError("A fractional stored tick silently became a whole number");
      edit(session, "0.15");
      if (!session.snapshot().inspect().candidate().orElseThrow().value().equals(3))
        throw new AssertionError("An exact unit conversion did not retain the native integer");
      edit(session, "-");
      for (var unit : display.units())
        if (!display.fromStored("-", unit.id()).text().equals("-"))
          throw new AssertionError("Changing a display unit discarded incomplete input");
      if (!((DraftNode.Scalar) session.snapshot().draft().root()).raw().equals("-")
          || !((DraftNode.Scalar) session.snapshot().draft().root())
              .unitInput()
              .orElseThrow()
              .unit()
              .equals("seconds")
          || session.snapshot().inspect().candidate().isPresent())
        throw new AssertionError("Incomplete unit input became an owner value");
      session.undo();
      if (!session.snapshot().inspect().candidate().orElseThrow().value().equals(3))
        throw new AssertionError("Raw unit editing lost its undo boundary");
    }
  }

  private static void edit(EditSession<?> session, String raw) {
    session.execute(
        new DraftEdit(
            session.snapshot().target(FieldPath.ROOT), new DraftEdit.SetUnitRaw("seconds", raw)));
  }

  private UnitsEditing() {}
}
