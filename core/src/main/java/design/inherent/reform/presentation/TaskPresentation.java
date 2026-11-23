package design.inherent.reform.presentation;

import java.util.Objects;
import java.util.Optional;

/** Immutable task preferences. Actual measured containment always takes precedence. */
public record TaskPresentation(Preference chooser, Preference inspection, Preference operations) {
  public enum Kind {
    CHOOSER,
    INSPECTION,
    OPERATIONS
  }

  public enum Preference {
    ADJACENT_PREFERRED,
    FOCUSED
  }

  public enum Arrangement {
    ADJACENT,
    FOCUSED
  }

  /** Intrinsic content offers, measured before assigning a pane's actual bounds. */
  public record Offer(FormGeometry.Size minimum, int preferredWidth) {
    public Offer {
      Objects.requireNonNull(minimum);
      if (preferredWidth < minimum.width())
        throw new IllegalArgumentException("Preferred task width is below its minimum");
    }
  }

  /** In focused mode the primary region contains the complete ordered task. */
  public record Regions(
      Arrangement arrangement,
      FormGeometry.Placement primary,
      Optional<FormGeometry.Placement> detail) {
    public Regions {
      Objects.requireNonNull(arrangement);
      Objects.requireNonNull(primary);
      Objects.requireNonNull(detail);
      if ((arrangement == Arrangement.ADJACENT) != detail.isPresent())
        throw new IllegalArgumentException("Only an adjacent task has an independent detail pane");
    }
  }

  public TaskPresentation {
    Objects.requireNonNull(chooser);
    Objects.requireNonNull(inspection);
    Objects.requireNonNull(operations);
  }

  public static TaskPresentation standard() {
    return new TaskPresentation(
        Preference.ADJACENT_PREFERRED,
        Preference.ADJACENT_PREFERRED,
        Preference.ADJACENT_PREFERRED);
  }

  public TaskPresentation withPreference(Kind kind, Preference preference) {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(preference);
    return switch (kind) {
      case CHOOSER -> new TaskPresentation(preference, inspection, operations);
      case INSPECTION -> new TaskPresentation(chooser, preference, operations);
      case OPERATIONS -> new TaskPresentation(chooser, inspection, preference);
    };
  }

  public Regions arrange(
      Kind kind, FormGeometry.Placement body, Offer primary, Optional<Offer> detail, int gap) {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(body);
    Objects.requireNonNull(primary);
    Objects.requireNonNull(detail);
    if (body.x() < 0
        || body.y() < 0
        || body.width() < 1
        || body.height() < 1
        || gap < 0
        || (long) body.x() + body.width() > Integer.MAX_VALUE
        || (long) body.y() + body.height() > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid task body bounds");
    var preference =
        switch (kind) {
          case CHOOSER -> chooser;
          case INSPECTION -> inspection;
          case OPERATIONS -> operations;
        };
    if (preference == Preference.ADJACENT_PREFERRED && detail.isPresent()) {
      var second = detail.orElseThrow();
      if (body.height() >= Math.max(primary.minimum().height(), second.minimum().height())) {
        var columns =
            FormGeometry.columns(
                body.width(),
                primary.minimum().width(),
                second.minimum().width(),
                primary.preferredWidth(),
                gap);
        if (columns.isPresent()) {
          var at = columns.orElseThrow();
          return new Regions(
              Arrangement.ADJACENT,
              new FormGeometry.Placement(body.x(), body.y(), at.firstWidth(), body.height()),
              Optional.of(
                  new FormGeometry.Placement(
                      body.x() + at.secondX(), body.y(), at.secondWidth(), body.height())));
        }
      }
    }
    return new Regions(Arrangement.FOCUSED, body, Optional.empty());
  }
}
