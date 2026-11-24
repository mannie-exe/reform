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
    Optional<String> focus,
    Optional<NavigationState> navigation) {
  public NavigationBookmark(
      UUID workflow,
      ClientDestination destination,
      Optional<SectionNavigation.Bookmark> viewport,
      Optional<String> focus) {
    this(workflow, destination, viewport, focus, Optional.empty());
  }

  /** Captured admitted navigation data only; construction never accepts external native handles. */
  public static final class NavigationState {
    private final String filter;
    private final WorkspaceNavigation.UiState tree;

    NavigationState(String filter, WorkspaceNavigation.UiState tree) {
      this.filter = Objects.requireNonNull(filter);
      this.tree = Objects.requireNonNull(tree);
    }

    public String filter() {
      return filter;
    }

    public String query() {
      return tree.query();
    }

    public int cursor() {
      return tree.cursor();
    }

    public int selection() {
      return tree.selection();
    }

    public boolean searchFocused() {
      return tree.searchFocused();
    }

    public int expandedCount() {
      return tree.expanded().size();
    }

    WorkspaceNavigation.UiState tree() {
      return tree;
    }
  }

  public NavigationBookmark {
    Objects.requireNonNull(workflow);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(viewport);
    Objects.requireNonNull(focus);
    Objects.requireNonNull(navigation);
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
