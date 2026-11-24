package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.OperationDescriptor;
import design.inherent.reform.presentation.FormPresentation;
import design.inherent.reform.presentation.TaskPresentation;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Public contributed wrapper: declarations freeze when its body enters the actual workspace. */
final class PlatformBorrowedPresentationChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private ClientEditingWorkflow workflow;
  private ClientEditor<?> editor;
  private OperationScreen<?> wrapper;
  private CompletableFuture<NavigationAttempt.Result> navigation;
  private byte[] source;
  private int stage;
  private final ClientTheme declared =
      ClientTheme.standard().withStyle(ClientTheme.TextRole.TITLE, Style.EMPTY.withBold(true));

  PlatformBorrowedPresentationChecks(BiConsumer<String, Boolean> check) {
    this.check = check;
  }

  boolean tick() throws Exception {
    var settings = PlatformWorkflowExample.settings();
    var route = PlatformWorkflowExample.DEFINITION.id();
    var path =
        client
            .gameDirectory
            .toPath()
            .resolve("config/reform_platform_example/workflow_preferences.json");
    switch (stage) {
      case 0 -> {
        source = Files.readAllBytes(path);
        var options =
            new ClientFormOptions<PlatformWorkflowExample.Preferences>(
                FormPresentation.EMPTY,
                Map.of(),
                definition ->
                    FieldEditors.builder(definition)
                        .field(
                            FieldPath.ROOT.field("label"),
                            request -> {
                              var operation =
                                  request.actions().validate(OperationDescriptor.EntryPoint.GUI);
                              wrapper =
                                  new OperationScreen<>(
                                      Component.literal("Declared validation"),
                                      request.contextLabel(),
                                      operation,
                                      () -> editor != null && editor.isOpen(),
                                      ignored ->
                                          new OperationScreen.Outcome(
                                              Component.literal("Validation completed"), List.of()),
                                      List.of(),
                                      request::finish,
                                      request::abandon);
                              wrapper.withTheme(declared);
                              wrapper.withTaskPresentation(TaskPresentation.standard());
                              return wrapper;
                            })
                        .build(),
                definition -> Optional.empty());
        workflow =
            ClientEditingWorkflow.open(
                parent,
                Component.literal("Wrapper admission workspace"),
                List.of(settings.form(Component.literal("Retained preferences"), options)));
        NativeScreens.open(client, workflow.screen());
        navigation =
            workflow
                .navigation()
                .open(new ClientDestination.Document(route))
                .completion()
                .toCompletableFuture();
        stage = 1;
      }
      case 1 -> {
        if (!navigation.isDone()) return false;
        check.accept(
            "wrapper owner mounts through actual acknowledged navigation",
            navigation.join().outcome() == NavigationAttempt.Outcome.REVEALED);
        editor = workflow.editor(route);
        if (editor.busy()) return false;
        var edit = button(workflow.screen(), "Edit…");
        if (!edit.active) return false;
        check.accept(
            "declared native auxiliary action is keyboard reachable",
            focus(workflow.screen(), edit));
        click(workflow.screen(), edit);
        stage = 2;
      }
      case 2 -> {
        if (wrapper == null || workflow.operationInspection().isEmpty()) return false;
        check.accept(
            "supported actual-owner wrapper is borrowed by the original root without another actor",
            NativeScreens.current(client) == workflow.screen()
                && workflow.editor(route) == editor
                && wrapper.theme() == declared);
        boolean themeRefused = false, tasksRefused = false;
        try {
          wrapper.withTheme(ClientTheme.standard());
        } catch (IllegalStateException expected) {
          themeRefused = true;
        }
        try {
          wrapper.withTaskPresentation(TaskPresentation.standard());
        } catch (IllegalStateException expected) {
          tasksRefused = true;
        }
        check.accept(
            "borrowed native wrapper rejects late theme and task declarations",
            themeRefused && tasksRefused && wrapper.theme() == declared);
        check.accept(
            "presentation admission does not write or dirty its original document",
            !editor.dirty() && Arrays.equals(source, Files.readAllBytes(path)));
        workflow.close();
        NativeScreens.open(client, parent);
        stage = 3;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }
}
