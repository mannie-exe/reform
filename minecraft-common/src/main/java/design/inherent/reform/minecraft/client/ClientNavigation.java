package design.inherent.reform.minecraft.client;

import java.util.Objects;

/** Typed links over the workflow's existing owners, history, and native views. */
public final class ClientNavigation {
  private final ClientEditingWorkflow workflow;

  ClientNavigation(ClientEditingWorkflow workflow) {
    this.workflow = Objects.requireNonNull(workflow);
  }

  public DestinationAvailability inspect(ClientDestination target) {
    return workflow.inspectDestination(Objects.requireNonNull(target));
  }

  public NavigationAttempt open(ClientDestination target) {
    return workflow.openDestination(Objects.requireNonNull(target));
  }

  public java.util.Optional<ClientDestination> current() {
    return workflow.currentDestination();
  }

  /** The declared parent, or rooted Home; absence means this is already a root. */
  public java.util.Optional<NavigationAttempt> up() {
    return workflow.navigateUp();
  }

  /** Preserve presentation state without flushing or accepting a native edit. */
  public NavigationBookmark bookmark() {
    return workflow.navigationBookmark();
  }

  public NavigationAttempt restore(NavigationBookmark bookmark) {
    return workflow.restoreBookmark(Objects.requireNonNull(bookmark));
  }
}
