package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.Objects;
import java.util.UUID;

/** Complete captured draft and a separately frozen typed subject; neither is an authority grant. */
public record GraphicRequest<T, S>(
    Stamp stamp, DraftPreviewInput<T> input, S subject, GraphicLimits limits) {
  public record Stamp(
      DocumentId provider,
      UUID view,
      UUID request,
      UUID context,
      PreviewInputStamp input,
      ReferenceStamp resources,
      GraphicInputBasis basis)
      implements GraphicSourceStamp {
    public Stamp(
        DocumentId provider,
        UUID view,
        UUID request,
        UUID context,
        PreviewInputStamp input,
        ReferenceStamp resources) {
      this(provider, view, request, context, input, resources, new GraphicInputBasis.Draft());
    }

    @Override
    public Kind kind() {
      return basis.kind();
    }

    public Stamp {
      Objects.requireNonNull(provider);
      Objects.requireNonNull(view);
      Objects.requireNonNull(request);
      Objects.requireNonNull(context);
      Objects.requireNonNull(input);
      Objects.requireNonNull(resources);
      Objects.requireNonNull(basis);
      if (!context.equals(input.draft().context()))
        throw new IllegalArgumentException("The graphic must retain its actual editor context");
    }
  }

  public GraphicRequest {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(input);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(limits);
    if (!stamp.input().equals(input.stamp()) || !stamp.basis().equals(input.basis()))
      throw new IllegalArgumentException("Graphic capture and input stamps disagree");
  }
}
