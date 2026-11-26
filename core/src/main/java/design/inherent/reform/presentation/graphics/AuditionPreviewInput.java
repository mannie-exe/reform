package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.Candidate;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.owner.SourceInfo;
import design.inherent.reform.reference.ReferenceAudition;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.value.ValueType;
import java.util.*;

/** A complete transformed preview and attributable base; neither stages the selected field. */
public final class AuditionPreviewInput<T> implements CapturedGraphicInput<T> {
  private final DraftPreviewInput<T> base;
  private final ReferenceAudition.Overlay<T> overlay;
  private final Map<DocumentId, ReferenceStamp> references;

  public AuditionPreviewInput(
      PreviewInputStamp stamp,
      ReferenceAudition.Overlay<T> overlay,
      Map<DocumentId, ReferenceStamp> observedReferences) {
    this.overlay = Objects.requireNonNull(overlay);
    if (!stamp.draft().equals(overlay.base().target()))
      throw new IllegalArgumentException(
          "Audition and native input need the exact same base revision");
    base =
        new DraftPreviewInput<>(
            stamp,
            overlay.base().candidate().orElseThrow(),
            overlay.base().observation().provenance(),
            observedReferences);
    var all = new HashMap<>(base.observedReferences());
    var source = overlay.basis().reference();
    var prior = all.putIfAbsent(source.source(), source);
    if (prior != null && !prior.equals(source))
      throw new PolicyRefusal(
          "preview.reference_stale",
          "The audition and field observed different reference snapshots");
    references = Map.copyOf(all);
  }

  public PreviewInputStamp stamp() {
    return base.stamp();
  }

  public Candidate<T> candidate() {
    return overlay.candidate();
  }

  public SourceInfo provenance() {
    return base.provenance();
  }

  public Map<DocumentId, ReferenceStamp> observedReferences() {
    return references;
  }

  public GraphicInputBasis.Audition basis() {
    return overlay.basis();
  }

  public DraftPreviewInput<T> base() {
    return base;
  }

  public ValueType<?> targetType() {
    return overlay.targetType();
  }

  public long copiedInputBytes() {
    return overlay.copiedBytes();
  }
}
