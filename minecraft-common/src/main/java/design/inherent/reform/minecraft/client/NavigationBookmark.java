package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.SectionNavigation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Presentation return data for one live workflow; no editor, native widget, or source capability.
 */
public record NavigationBookmark(
    UUID workflow,
    ClientDestination destination,
    Optional<SectionNavigation.Bookmark> viewport,
    Optional<String> focus) {
  public NavigationBookmark {
    Objects.requireNonNull(workflow);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(viewport);
    Objects.requireNonNull(focus);
    if (focus.filter(value -> value.length() > 4096).isPresent())
      throw new IllegalArgumentException("Bookmark focus identity exceeds its allowance");
    viewport.ifPresent(
        value -> {
          if (value.section() == null
              || value.section().isBlank()
              || value.section().length() > 4096
              || !Float.isFinite(value.offset()))
            throw new IllegalArgumentException(
                "Bookmark viewport requires finite bounded position data");
        });
  }
}
