package design.inherent.reform.presentation.graphics;

import design.inherent.reform.validation.ValidationReport;
import java.util.Objects;

/**
 * Provider outcomes contain no private GPU lease; cancellation is settled after entered work
 * drains.
 */
public sealed interface GraphicScenePreparation {
  /**
   * A platform contribution contains frozen preparation data, never a submitted native lease. Hosts
   * must explicitly support its concrete kind before preparing it.
   */
  non-sealed interface Contribution extends GraphicScenePreparation {
    GraphicSourceStamp stamp();

    GraphicCamera camera();

    java.util.List<GraphicSceneData.Fact> facts();

    long outputBytes();
  }

  record Ready(GraphicSceneData graphic) implements Contribution {
    public Ready {
      Objects.requireNonNull(graphic);
    }

    @Override
    public GraphicSourceStamp stamp() {
      return graphic.stamp();
    }

    @Override
    public GraphicCamera camera() {
      return graphic.camera();
    }

    @Override
    public java.util.List<GraphicSceneData.Fact> facts() {
      return graphic.facts();
    }

    @Override
    public long outputBytes() {
      return graphic.outputBytes();
    }
  }

  record Unavailable(ValidationReport reason) implements GraphicScenePreparation {
    public Unavailable {
      Objects.requireNonNull(reason);
      if (reason.issues().isEmpty())
        throw new IllegalArgumentException("An unavailable capability needs a reason");
    }
  }

  record Refused(ValidationReport report) implements GraphicScenePreparation {
    public Refused {
      Objects.requireNonNull(report);
      if (report.valid()) throw new IllegalArgumentException("A refusal needs an invalid report");
    }
  }

  record Failed(ValidationReport report, Throwable cause) implements GraphicScenePreparation {
    public Failed {
      Objects.requireNonNull(report);
      Objects.requireNonNull(cause);
      if (report.valid()) throw new IllegalArgumentException("A failure needs an invalid report");
    }
  }

  record Cancelled() implements GraphicScenePreparation {}
}
