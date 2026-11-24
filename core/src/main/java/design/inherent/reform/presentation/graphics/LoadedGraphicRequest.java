package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.reference.ReferenceStamp;
import java.util.Objects;
import java.util.UUID;

/** A read-authorized loaded subject; contains no document, draft, or write capability. */
public record LoadedGraphicRequest<S>(Stamp stamp, S subject, GraphicLimits limits) {
  public record Stamp(
      DocumentId provider, UUID view, UUID request, UUID context, ReferenceStamp resources)
      implements GraphicSourceStamp {
    public Stamp {
      Objects.requireNonNull(provider);
      Objects.requireNonNull(view);
      Objects.requireNonNull(request);
      Objects.requireNonNull(context);
      Objects.requireNonNull(resources);
    }

    @Override
    public Kind kind() {
      return Kind.LOADED;
    }
  }

  public LoadedGraphicRequest {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(limits);
  }
}
