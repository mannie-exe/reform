package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldKey;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.definition.RecordType;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class DefaultedProperties {
  private record Profile(int height) {}

  private record State(boolean overridden, String raw) {}

  @Property(tries = 100, seed = "2026111801")
  void canonicalOmissionAndLatePresenceChangesMatchAnIndependentStateModel(
      @ForAll @IntRange(min = 0, max = 100) int submittedValue,
      @ForAll boolean fractionalSpelling,
      @ForAll boolean lateOverride,
      @ForAll @IntRange(min = 0, max = 101) int lateValue)
      throws Exception {
    var field = FieldKey.of("height", ValueTypes.defaultedField(ValueTypes.integer(0, 100), 4));
    var type =
        RecordType.<Profile>builder(v -> new Profile(v.get(field)))
            .field(field, Profile::height)
            .build();
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:generated_default"), 1, type, () -> new Profile(4));
    var limits = new JsonLimits(8192, 16, 1024, 128, 64, 1024);
    var path = FieldPath.ROOT.field("height");
    var contents = path.append(FieldPath.Part.VALUE);
    try (var session =
        EditSession.fromSource(
            definition,
            UUID.randomUUID(),
            "one",
            JsonInput.read("{}", limits),
            limits,
            DraftLimits.CONFIGURATION)) {
      String submittedRaw = submittedValue + (fractionalSpelling ? ".00" : "");
      var submittedState = new State(true, submittedRaw);
      session.edit(path, n -> ((DraftNode.Defaulted) n).withOverride(true), "Override");
      session.setRaw(contents, submittedRaw);
      var submission = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submission);
      String lateRaw = lateValue == 101 ? "-" : String.valueOf(lateValue);
      var late = new State(lateOverride, lateRaw);
      session.edit(
          path, n -> ((DraftNode.Defaulted) n).withOverride(lateOverride), "Late presence");
      session.setRaw(contents, lateRaw);
      var actual =
          new State(
              submittedValue != 4,
              submittedValue == 4 ? submittedRaw : String.valueOf(submittedValue));
      var expected = late.equals(submittedState) ? actual : late;
      var nativeSource = submittedValue == 4 ? "{}" : "{\"height\":" + submittedValue + "}";
      var observed =
          DraftTree.fromParsed(
              type, JsonInput.read(nativeSource, limits), DraftLimits.CONFIGURATION);
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submission, observed, "two"));
      var node = (DraftNode.Defaulted) session.snapshot().draft().at(path);
      assertEquals(expected.overridden(), node.overridden());
      assertEquals(expected.raw(), ((DraftNode.Scalar) node.value()).raw());
      assertEquals(
          expected.overridden() != actual.overridden()
              || (expected.overridden() && !expected.raw().equals(actual.raw())),
          session.dirty());
      var candidate = session.snapshot().inspect().candidate();
      boolean valid = !expected.overridden() || !expected.raw().equals("-");
      assertEquals(valid, candidate.isPresent());
      if (valid)
        assertEquals(
            expected.overridden() ? Integer.parseInt(expected.raw().split("\\.")[0]) : 4,
            candidate.orElseThrow().value().height());
    }
  }
}
