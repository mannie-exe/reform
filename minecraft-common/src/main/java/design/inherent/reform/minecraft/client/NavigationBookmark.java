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
    private final ReviewView.Bookmark inspection;
    private final OperationView.Bookmark operation;
    private final ConflictView.Bookmark conflict;
    private final ImportView.Bookmark imported;

    NavigationState(String filter, WorkspaceNavigation.UiState tree) {
      this(filter, tree, null);
    }

    NavigationState(
        String filter, WorkspaceNavigation.UiState tree, ReviewView.Bookmark inspection) {
      this(filter, tree, inspection, null);
    }

    NavigationState(
        String filter,
        WorkspaceNavigation.UiState tree,
        ReviewView.Bookmark inspection,
        OperationView.Bookmark operation) {
      this(filter, tree, inspection, operation, null);
    }

    NavigationState(
        String filter,
        WorkspaceNavigation.UiState tree,
        ReviewView.Bookmark inspection,
        OperationView.Bookmark operation,
        ConflictView.Bookmark conflict) {
      this(filter, tree, inspection, operation, conflict, null);
    }

    NavigationState(
        String filter,
        WorkspaceNavigation.UiState tree,
        ReviewView.Bookmark inspection,
        OperationView.Bookmark operation,
        ConflictView.Bookmark conflict,
        ImportView.Bookmark imported) {
      this.imported = imported;
      this.conflict = conflict;
      this.operation = operation;
      this.inspection = inspection;
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

    ImportView.Bookmark imported() {
      return imported;
    }

    ConflictView.Bookmark conflict() {
      return conflict;
    }

    OperationView.Bookmark operation() {
      return operation;
    }

    ReviewView.Bookmark inspection() {
      return inspection;
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
