package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.edit.DraftRevision;
import design.inherent.reform.edit.DraftTree;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.presentation.graphics.DraftPreviewInput;
import design.inherent.reform.presentation.graphics.PreviewInputStamp;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.value.ValueTypes;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DraftPreviewInputTest {
  private static final DocumentId ID = DocumentId.parse("fixture:preview");

  private static DraftRevision revision(DocumentId id) {
    return new DraftRevision(id, UUID.randomUUID(), UUID.randomUUID(), 0, 4, "source-2");
  }

  @Test
  void inputFreezesCandidateAndReferenceIdentitiesWithoutReinterpretingRevisions() {
    var definition =
        new DocumentDefinition<>(
            ID, 1, ValueTypes.list(ValueTypes.integer(0, 10), 4), () -> List.of(1));
    var candidate =
        DraftTree.from(definition.type(), List.of(2, 3), DraftLimits.CONFIGURATION)
            .inspect(definition, new JsonLimits(8192, 32, 1024, 512, 64, 1024))
            .candidate()
            .orElseThrow();
    var source = DocumentId.parse("fixture:choices");
    var references = new HashMap<DocumentId, ReferenceStamp>();
    var observed = new ReferenceStamp(UUID.randomUUID(), source, UUID.randomUUID(), "actual-1");
    references.put(source, observed);
    var stamp = new PreviewInputStamp(revision(ID), 7);
    var input = new DraftPreviewInput<>(stamp, candidate, SourceInfo.UNKNOWN, references);
    references.clear();
    assertEquals(observed, input.observedReferences().get(source));
    assertThrows(UnsupportedOperationException.class, () -> input.observedReferences().clear());
    assertEquals(List.of(2, 3), input.candidate().value());
    assertEquals(stamp, input.stamp());
    assertNotEquals(stamp, new PreviewInputStamp(stamp.draft(), 8));
    assertThrows(IllegalArgumentException.class, () -> new PreviewInputStamp(stamp.draft(), -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DraftPreviewInput<>(
                new PreviewInputStamp(revision(source), 0),
                candidate,
                SourceInfo.UNKNOWN,
                java.util.Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new DraftPreviewInput<>(
                stamp, candidate, SourceInfo.UNKNOWN, java.util.Map.of(ID, observed)));
  }
}
