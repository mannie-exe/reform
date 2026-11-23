package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import java.util.Objects;

/** Explicit mesh sampling/alpha policy. A native adapter must refuse unsupported policies. */
public record GraphicMaterial(
    DocumentId texture, Alpha alpha, float cutoff, Cull cull, Filter filter, Wrap wrap) {
  public enum Alpha {
    OPAQUE,
    CUTOUT,
    TRANSLUCENT
  }

  public enum Cull {
    NONE,
    BACK
  }

  public enum Filter {
    NEAREST,
    LINEAR
  }

  public enum Wrap {
    CLAMP,
    REPEAT
  }

  public GraphicMaterial {
    Objects.requireNonNull(texture);
    Objects.requireNonNull(alpha);
    Objects.requireNonNull(cull);
    Objects.requireNonNull(filter);
    Objects.requireNonNull(wrap);
    if (!Float.isFinite(cutoff) || cutoff < 0 || cutoff > 1)
      throw new IllegalArgumentException(
          "Material alpha cutoff must be finite and between 0 and 1");
  }
}
