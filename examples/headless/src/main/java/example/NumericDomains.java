package example;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftNode;
import design.inherent.reform.edit.EditSession;
import design.inherent.reform.presentation.NumericDisplay;
import design.inherent.reform.presentation.NumericInput;
import design.inherent.reform.presentation.NumericPresentation;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.NumericDomain;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Both owned record and object-map styles use the same packaged endpoint and caption contract. */
final class NumericDomains {
  private record Profile(double positive, double belowOne) {}

  static void verify() throws Exception {
    var positive =
        ValueTypes.numericDisplay(
            ValueTypes.decimal(NumericDomain.openClosed(BigDecimal.ZERO, BigDecimal.ONE)),
            NumericDisplay.probability().withSlider());
    var belowOne = ValueTypes.decimal(NumericDomain.closedOpen(BigDecimal.ZERO, BigDecimal.ONE));
    var a = FieldKey.of("positive", positive);
    var b = FieldKey.of("belowOne", belowOne);
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(a), v.get(b)))
            .field(a, Profile::positive)
            .field(b, Profile::belowOne)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("example:numeric_domains"), 1, type, () -> new Profile(0.5, 0.5));
    var json = new JsonLimits(8192, 32, 1024, 512, 128, 4096);
    try (var editor =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "source",
            JsonInput.read("{\"positive\":0.5,\"belowOne\":0.5}", json),
            json,
            DraftLimits.CONFIGURATION)) {
      var path = FieldPath.ROOT.field("positive");
      editor.setRaw(path, "0");
      if (editor.prepareSubmission().submission().isPresent()
          || !((DraftNode.Scalar) editor.snapshot().draft().at(path)).raw().equals("0"))
        throw new AssertionError("Exclusive zero was admitted or discarded");
      editor.setRaw(path, "1e-300");
      if (editor.prepareSubmission().submission().isEmpty())
        throw new AssertionError("A valid sub-step positive value was lost");
      editor.setRaw(FieldPath.ROOT.field("belowOne"), "1");
      if (editor.prepareSubmission().submission().isPresent())
        throw new AssertionError("The open upper endpoint was admitted");
    }
    var map = ValueTypes.objectMap(ValueTypes.text(32), positive, 8);
    if (JsonInput.read("{\"activation\":0}", json).decode(map).value().isPresent()
        || JsonInput.read("{\"activation\":1e-300}", json).decode(map).value().isEmpty())
      throw new AssertionError("Map-style domain differs from the typed record");
    var shape = (ValueShape.Scalar) positive.shape();
    var input = NumericInput.open(shape, new DraftNode.Scalar("1e-300"));
    if (!input.slide(0).input().expected().raw().equals("0.01")
        || !input.expected().raw().equals("1e-300")
        || input.action().isPresent())
      throw new AssertionError("An open grid narrowed or edited exact input");
    var off = new NumericDisplay.Caption(BigDecimal.ZERO, "example.off", "Off");
    var duration =
        ValueTypes.numericDisplay(
            ValueTypes.integer(0, 12000),
            NumericDisplay.ticks(BigDecimal.valueOf(20)).withCaptions(List.of(off)));
    var rawZero = new DraftNode.Scalar("0e2");
    if (!NumericPresentation.caption(duration, rawZero).equals(java.util.Optional.of(off))
        || !rawZero.raw().equals("0e2"))
      throw new AssertionError("Caption changed or failed to describe the numeric value");
  }

  private NumericDomains() {}
}
