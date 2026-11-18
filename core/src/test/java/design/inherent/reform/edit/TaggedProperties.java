package design.inherent.reform.edit;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.FieldPath;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

class TaggedProperties {
  @Property(tries = 100, seed = "2026111803")
  void savedBranchNormalizationAndLaterPrivateBranchesMatchAnIndependentModel(
      @ForAll @IntRange(min = 0, max = 100) int submitted,
      @ForAll @IntRange(min = 0, max = 101) int later,
      @ForAll boolean editActive,
      @ForAll boolean open,
      @ForAll boolean selectCavern)
      throws Exception {
    String source =
        "{\"kind\":\"land\",\"settings\":{\"height\":" + submitted + ".00,\"pillars\":[1,2]}}";
    try (var session = TaggedDraftTest.session(source)) {
      TaggedDraftTest.select(session, "cavern", TaggedDraftTest.cavern(false));
      TaggedDraftTest.select(session, "land", new DraftNode.Missing());
      var submission = session.prepareSubmission().submission().orElseThrow();
      session.markInFlight(submission);
      if (editActive)
        session.setRaw(TaggedDraftTest.HEIGHT_PATH, later == 101 ? "-" : String.valueOf(later));
      session.setRaw(TaggedDraftTest.CAVERN_PATH.field("open"), String.valueOf(open));
      if (selectCavern) TaggedDraftTest.select(session, "cavern", TaggedDraftTest.cavern(false));
      String expectedRaw =
          editActive ? later == 101 ? "-" : String.valueOf(later) : String.valueOf(submitted);
      assertEquals(
          EditSession.Acknowledgement.ACCEPTED,
          session.acknowledge(
              submission, new TaggedDraftTest.Land(submitted, List.of(1, 2)), "two"));
      assertEquals(
          expectedRaw,
          ((DraftNode.Scalar) session.snapshot().draft().at(TaggedDraftTest.HEIGHT_PATH)).raw());
      assertEquals(
          String.valueOf(open),
          ((DraftNode.Scalar)
                  session.snapshot().draft().at(TaggedDraftTest.CAVERN_PATH.field("open")))
              .raw());
      assertEquals(
          selectCavern ? "cavern" : "land",
          ((DraftNode.Alternative) session.snapshot().draft().root()).selected());
      boolean valid = selectCavern || !expectedRaw.equals("-");
      assertEquals(valid, session.snapshot().inspect().candidate().isPresent());
      assertEquals(selectCavern || !expectedRaw.equals(String.valueOf(submitted)), session.dirty());
      if (valid)
        assertEquals(
            selectCavern
                ? new TaggedDraftTest.Cavern(open)
                : new TaggedDraftTest.Land(Integer.parseInt(expectedRaw), List.of(1, 2)),
            session.snapshot().inspect().candidate().orElseThrow().value());
      assertTrue(session.snapshot().draft().contains(FieldPath.ROOT.append(FieldPath.Part.KEY)));
    }
  }
}
