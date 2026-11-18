package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.serialization.JsonInput;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueShape;
import design.inherent.reform.value.ValueTypes;
import java.util.Optional;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class OptionalProperties {
  private record State(boolean present, String raw) {}

  @Property(tries = 100, seed = "2026111802")
  void lateMembershipAndInactiveInputMatchAnIndependentSaveModel(
      @ForAll boolean submitPresent,
      @ForAll boolean latePresent,
      @ForAll @IntRange(min = 0, max = 100) int submittedValue,
      @ForAll @IntRange(min = 0, max = 101) int lateValue,
      @ForAll ValueShape.OptionalEncoding encoding)
      throws Exception {
    var type = ValueTypes.optional(ValueTypes.integer(0, 100), encoding);
    var definition =
        new DocumentDefinition<>(
            DocumentId.parse("fixture:generated_optional"), 1, type, Optional::empty);
    var limits = new JsonLimits(8192, 16, 1024, 128, 64, 1024);
    var path = FieldPath.ROOT.append(FieldPath.Part.VALUE);
    try (var session =
        new EditSession<>(
            definition,
            UUID.randomUUID(),
            "one",
            Optional.empty(),
            limits,
            DraftLimits.CONFIGURATION)) {
      String submittedRaw = submittedValue + ".00";
      session.edit(
          FieldPath.ROOT,
          n -> ((DraftNode.OptionalValue) n).withPresent(submitPresent),
          "Membership");
      session.setRaw(path, submittedRaw);
      var submission = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submission);
      String lateRaw = lateValue == 101 ? "-" : String.valueOf(lateValue);
      var submitted = new State(submitPresent, submittedRaw);
      var late = new State(latePresent, lateRaw);
      session.edit(
          FieldPath.ROOT,
          n -> ((DraftNode.OptionalValue) n).withPresent(latePresent),
          "Later membership");
      session.setRaw(path, lateRaw);
      var actual =
          new State(submitPresent, submitPresent ? String.valueOf(submittedValue) : submittedRaw);
      var expected = submitted.equals(late) ? actual : late;
      String source =
          encoding == ValueShape.OptionalEncoding.SINGLETON_LIST
              ? (submitPresent ? "[" + submittedValue + "]" : "[]")
              : (submitPresent ? String.valueOf(submittedValue) : "null");
      var observed =
          DraftTree.fromParsed(type, JsonInput.read(source, limits), DraftLimits.CONFIGURATION);
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED, session.acknowledge(submission, observed, "two"));
      var raw = (DraftNode.OptionalValue) session.snapshot().draft().root();
      assertEquals(expected.present(), raw.present());
      assertEquals(expected.raw(), ((DraftNode.Scalar) raw.value()).raw());
      assertEquals(
          expected.present() != actual.present()
              || (expected.present() && !expected.raw().equals(actual.raw())),
          session.dirty());
      var candidate = session.snapshot().inspect().candidate();
      assertEquals(!expected.present() || !expected.raw().equals("-"), candidate.isPresent());
      if (candidate.isPresent())
        assertEquals(
            expected.present()
                ? Optional.of(Integer.parseInt(expected.raw().split("\\.")[0]))
                : Optional.empty(),
            candidate.orElseThrow().value());
    }
  }
}
