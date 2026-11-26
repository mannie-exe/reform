package design.inherent.reform.presentation.graphics;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.Objects;

/** Complete attributable draft or audition input, separate from the legacy draft-only request. */
public record CapturedGraphicRequest<T, S>(
    GraphicRequest.Stamp stamp, CapturedGraphicInput<T> input, S subject, GraphicLimits limits) {
  public CapturedGraphicRequest {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(input);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(limits);
    if (!stamp.input().equals(input.stamp()) || !stamp.basis().equals(input.basis()))
      throw new IllegalArgumentException("Graphic capture and input stamps disagree");
  }

  public static <T, S> CapturedGraphicRequest<T, S> from(GraphicRequest<T, S> draft) {
    return new CapturedGraphicRequest<>(
        draft.stamp(), draft.input(), draft.subject(), draft.limits());
  }

  /** Preserve the actual complete DRAFT; never substitute an audition's base as its output. */
  public GraphicRequest<T, S> asDraft() {
    if (!(input instanceof DraftPreviewInput<T> draft))
      throw new PolicyRefusal(
          "preview.audition_unsupported", "This provider supports draft previews only");
    return new GraphicRequest<>(stamp, draft, subject, limits);
  }
}
