package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.*;
import design.inherent.reform.presentation.*;
import design.inherent.reform.validation.*;
import java.util.*;

/**
 * Immutable shell declaration; construction and validation never invoke owner or canvas factories.
 */
public record ClientWorkspacePresentation(
    WorkspaceIdentity identity,
    HeaderComposition header,
    Optional<HomePage> home,
    ThemePatch theme,
    Optional<DestinationId> initial) {
  public ClientWorkspacePresentation {
    Objects.requireNonNull(identity);
    Objects.requireNonNull(header);
    Objects.requireNonNull(home);
    Objects.requireNonNull(theme);
    Objects.requireNonNull(initial);
  }

  public static ClientWorkspacePresentation standard(WorkspaceIdentity identity) {
    return new ClientWorkspacePresentation(
        identity,
        HeaderComposition.standard(),
        Optional.of(HomePage.standard()),
        ThemePatch.empty(),
        Optional.empty());
  }

  public ClientWorkspacePresentation withHeader(HeaderComposition header) {
    return new ClientWorkspacePresentation(identity, header, home, theme, initial);
  }

  public ClientWorkspacePresentation withHome(HomePage home) {
    return new ClientWorkspacePresentation(identity, header, Optional.of(home), theme, initial);
  }

  public ClientWorkspacePresentation withoutHome() {
    return new ClientWorkspacePresentation(identity, header, Optional.empty(), theme, initial);
  }

  public ClientWorkspacePresentation withTheme(ThemePatch patch) {
    return new ClientWorkspacePresentation(identity, header, home, patch, initial);
  }

  public ClientWorkspacePresentation withInitialDestination(DestinationId id) {
    return new ClientWorkspacePresentation(identity, header, home, theme, Optional.of(id));
  }

  public ThemeValues values() {
    return ThemeScope.root(theme).values();
  }

  public ValidationReport validate(List<ClientForm> forms, ClientWorkflowLayout layout) {
    var issues = new ArrayList<Issue>();
    var ids = new HashSet<DestinationId>();
    if (home.isPresent()) ids.add(home.orElseThrow().id());
    if (layout == null) {
      for (var form : forms)
        if (!ids.add(new DestinationId(form.route())))
          issues.add(issue("Duplicate destination " + form.route()));
    } else {
      var categories = new HashSet<DocumentId>();
      for (int index = 0; index < layout.groups().size(); index++) {
        var group = layout.groups().get(index);
        if (group.path().isEmpty()) {
          if (!ids.add(DestinationId.parse("reform:workflow_group/" + index)))
            issues.add(issue("Duplicate group destination"));
        } else
          for (var category : group.path())
            if (categories.add(category.id()) && !ids.add(new DestinationId(category.id())))
              issues.add(issue("Duplicate category destination " + category.id()));
        for (var placement : group.placements()) {
          if (!ids.add(new DestinationId(placement.id())))
            issues.add(issue("Duplicate placement destination " + placement.id()));
          if (forms.stream().noneMatch(form -> form.route().equals(placement.route())))
            issues.add(issue("Placement names an absent owner " + placement.route()));
        }
      }
    }
    initial
        .filter(id -> !ids.contains(id))
        .ifPresent(id -> issues.add(issue("Initial destination is absent: " + id)));
    home.ifPresent(
        page ->
            page.featured().stream()
                .filter(id -> !ids.contains(id) || id.equals(page.id()))
                .forEach(
                    id ->
                        issues.add(
                            issue("Featured destination is absent or refers to Home: " + id))));
    return new ValidationReport(issues, true);
  }

  private static Issue issue(String message) {
    return Issue.error("presentation.invalid_declaration", FieldPath.ROOT, message);
  }
}
