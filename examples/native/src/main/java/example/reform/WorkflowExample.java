package example.reform;

import design.inherent.reform.minecraft.client.ClientEditingWorkflow;
import design.inherent.reform.minecraft.client.ClientForm;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Four ordinary owners compose without a new parser, executor, protocol or shadow settings file.
 */
public final class WorkflowExample {
  public static List<ClientForm> forms() {
    return List.of(
        ClientExample.settings().form(Component.literal("Client preferences")),
        KeyExample.settings().form(Component.literal("Native Controls")),
        RegisteredExample.client().form(Component.literal("Native client settings")),
        RegisteredExample.common().form(Component.literal("Installation defaults")));
  }

  public static ClientEditingWorkflow open(Screen parent) {
    return ClientEditingWorkflow.open(parent, Component.literal("Composed settings"), forms());
  }

  private WorkflowExample() {}
}
