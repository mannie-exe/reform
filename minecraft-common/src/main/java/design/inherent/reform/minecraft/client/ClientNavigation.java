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
}
