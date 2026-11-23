package design.inherent.reform.minecraft.client;

import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;

/** Native host outcome; only Ready carries a prepared graphic lease. */
public sealed interface GraphicPreparation {
  record Ready(PreparedGraphic graphic) implements GraphicPreparation {
    public Ready {
      Objects.requireNonNull(graphic);
    }
  }

  record Unavailable(ValidationReport reason) implements GraphicPreparation {
    public Unavailable {
      Objects.requireNonNull(reason);
      if (reason.issues().isEmpty())
        throw new IllegalArgumentException("An unavailable capability needs a reason");
    }
  }

  record Refused(ValidationReport report) implements GraphicPreparation {
    public Refused {
      Objects.requireNonNull(report);
      if (report.valid()) throw new IllegalArgumentException("A refusal needs issues");
    }
  }

  record Failed(ValidationReport report, Throwable cause) implements GraphicPreparation {
    public Failed {
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
      if (report.valid()) throw new IllegalArgumentException("A failure needs issues");
    }
  }

  record Cancelled() implements GraphicPreparation {}
}
