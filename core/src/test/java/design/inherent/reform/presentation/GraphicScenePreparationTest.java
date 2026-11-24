package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.*;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.validation.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GraphicScenePreparationTest {
  @Test
  void readyContributionKeepsTheSameMeshMetadataWithoutAllocatingALease() {
    var data =
        new GraphicSceneData(
            stamp(),
            empty(),
            GraphicCamera.STANDARD,
            List.of(
                new GraphicSceneData.Fact(DocumentId.parse("test:subject"), "Subject", "Empty")));
    GraphicScenePreparation.Contribution contribution = new GraphicScenePreparation.Ready(data);
    assertSame(data.stamp(), contribution.stamp());
    assertSame(data.camera(), contribution.camera());
    assertSame(data.facts(), contribution.facts());
    assertEquals(data.outputBytes(), contribution.outputBytes());
  }

  private static GraphicRequest.Stamp stamp() {
    var context = UUID.randomUUID();
    return new GraphicRequest.Stamp(
        DocumentId.parse("test:provider"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        context,
        new PreviewInputStamp(
            new DraftRevision(
                DocumentId.parse("test:document"), context, UUID.randomUUID(), 0, 0, "0"),
            0),
        new ReferenceStamp(context, DocumentId.parse("test:resources"), UUID.randomUUID(), "0"));
  }

  private static GraphicScene empty() {
    return new GraphicScene(
        new GraphicMesh(new GraphicLimits(1024, 1024, 256, 8, 1024), List.of(), List.of()),
        Map.of(),
        Map.of());
  }

  @Test
  void providerResultsRetainActualContextAndWholeResourceIdentity() {
    var stamp = stamp();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphicRequest.Stamp(
                stamp.provider(),
                stamp.view(),
                stamp.request(),
                UUID.randomUUID(),
                stamp.input(),
                stamp.resources()));
    var other =
        new ReferenceStamp(
            stamp.resources().context(),
            stamp.resources().source(),
            UUID.randomUUID(),
            stamp.resources().revision());
    assertNotEquals(stamp.resources(), other);
  }

  @Test
  void boundedFactsRemainUniqueAndTheirUtf8PayloadCountsAgainstOutput() {
    var fact = new GraphicSceneData.Fact(DocumentId.parse("test:pose"), "Pose", "静止");
    var data = new ArrayList<>(List.of(fact));
    var result = new GraphicSceneData(stamp(), empty(), GraphicCamera.STANDARD, data);
    data.clear();
    assertEquals(List.of(fact), result.facts());
    assertEquals("test:pose".length() + "Pose".length() + 6, result.outputBytes());
    assertThrows(UnsupportedOperationException.class, () -> result.facts().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicSceneData(stamp(), empty(), GraphicCamera.STANDARD, List.of(fact, fact)));
    assertThrows(
        IllegalArgumentException.class, () -> new GraphicSceneData.Fact(fact.id(), "Pose", " "));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicSceneData.Fact(fact.id(), "Pose", "x".repeat(4097)));
  }

  @Test
  void loadedSourcesRetainRealResourceIdentityWithoutADraftStamp() {
    var draft = stamp();
    var loaded =
        new LoadedGraphicRequest.Stamp(
            draft.provider(), draft.view(), draft.request(), UUID.randomUUID(), draft.resources());
    assertEquals(GraphicSourceStamp.Kind.DRAFT, draft.kind());
    assertEquals(GraphicSourceStamp.Kind.LOADED, loaded.kind());
    assertNotEquals(draft, loaded);
    var input =
        new LoadedGraphicRequest<>(
            loaded, "test:textures/image.png", new GraphicLimits(1024, 1024, 256, 8, 1024));
    assertSame(loaded, input.stamp());
    assertSame(
        loaded, new GraphicSceneData(loaded, empty(), GraphicCamera.STANDARD, List.of()).stamp());
    assertThrows(
        NullPointerException.class,
        () ->
            new LoadedGraphicRequest.Stamp(
                loaded.provider(), loaded.view(), loaded.request(), loaded.context(), null));
  }

  @Test
  void unavailableAndInvalidAreDistinctAndCannotCarryAReadyLease() {
    var reason =
        new ValidationReport(
            List.of(Issue.error("test.unavailable", FieldPath.ROOT, "No world is available")),
            true);
    assertInstanceOf(
        GraphicScenePreparation.Unavailable.class, new GraphicScenePreparation.Unavailable(reason));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicScenePreparation.Unavailable(ValidationReport.VALID));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicScenePreparation.Refused(ValidationReport.VALID));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphicScenePreparation.Failed(
                ValidationReport.VALID, new IllegalStateException()));
  }
}
