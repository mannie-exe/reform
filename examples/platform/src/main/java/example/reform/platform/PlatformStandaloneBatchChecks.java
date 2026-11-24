package example.reform.platform;

import static example.reform.platform.PlatformWidgets.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.minecraft.client.*;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.DocumentBatch;
import design.inherent.reform.operation.OperationDescriptor.EntryPoint;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Independent standalone host over actual borrowed file editors, including changing READ rights.
 */
final class PlatformStandaloneBatchChecks {
  private final Minecraft client = Minecraft.getInstance();
  private final Screen parent = NativeScreens.current(client);
  private final BiConsumer<String, Boolean> check;
  private ClientEditor<?> first, second;
  private OwnerContext context;
  private DocumentBatch service;
  private BatchReviewScreen screen;
  private DocumentBatch.Review captured;
  private CompletableFuture<Void> loading;
  private boolean readable = true, returned;
  private byte[] firstSource, secondSource;
  private int stage;

  PlatformStandaloneBatchChecks(BiConsumer<String, Boolean> check) {
    this.check = check;
  }

  private byte[] source(String name) throws Exception {
    return Files.readAllBytes(
        client.gameDirectory.toPath().resolve("config/reform_platform_example/" + name + ".json"));
  }

  boolean tick() throws Exception {
    switch (stage) {
      case 0 -> {
        firstSource = source("workflow_preferences");
        secondSource = source("workspace_twin");
        first =
            PlatformWorkflowExample.settings()
                .form(Component.literal("First borrowed file"))
                .open(parent);
        second = PlatformWorkspaceChecks.navigationTwin().open(parent);
        loading =
            CompletableFuture.allOf(
                DocumentActions.result(first.actions().ensureLoaded(EntryPoint.GUI))
                    .toCompletableFuture(),
                DocumentActions.result(second.actions().ensureLoaded(EntryPoint.GUI))
                    .toCompletableFuture());
        stage = 1;
      }
      case 1 -> {
        if (!loading.isDone()) return false;
        loading.join();
        check.accept(
            "standalone batch borrows two actually loaded independent editors",
            first.view().availability() == EditorActor.Availability.READY
                && second.view().availability() == EditorActor.Availability.READY);
        context =
            new OwnerContext(
                client::execute,
                NativeClientProbe.ioPool(),
                () -> {
                  if (!client.isSameThread())
                    throw new AssertionError("Standalone batch left client thread");
                },
                OwnerContext.Limits.STANDARD);
        service =
            new DocumentBatch(
                context,
                context.access(() -> readable, () -> false),
                DocumentId.parse("fixture:standalone_batch"),
                List.of(
                    DocumentBatch.member(first.actions(), DocumentBatch.Intent.CHECK),
                    DocumentBatch.member(second.actions(), DocumentBatch.Intent.CHECK)));
        screen =
            new BatchReviewScreen(
                Component.literal("Standalone complete review"),
                service,
                context::isOpen,
                id ->
                    Component.literal(
                        id.equals(first.document())
                            ? "First borrowed file"
                            : "Second borrowed file"),
                (entry, origin) -> {
                  throw new AssertionError("This check does not invoke field inspection");
                },
                () -> {
                  returned = true;
                  NativeScreens.open(client, parent);
                },
                () -> {
                  throw new AssertionError(
                      "Managed standalone return must not abandon its borrowed sources");
                });
        screen.withFieldPresentations(
            Map.of(
                first.document(),
                    new BatchReviewScreen.Fields(
                        first.form().fieldOrder(), first.form().fieldPresentation()),
                second.document(),
                    new BatchReviewScreen.Fields(
                        second.form().fieldOrder(), second.form().fieldPresentation())));
        NativeScreens.open(client, screen);
        stage = 2;
      }
      case 2 -> {
        if (screen.reviewedInput().isEmpty()) return false;
        captured = screen.reviewedInput().orElseThrow();
        check.accept(
            "standalone read-only batch captures complete CHECK owners without a write",
            NativeScreens.current(client) == screen
                && captured.valid()
                && captured.entries().size() == 2
                && captured.entries().stream()
                    .allMatch(e -> e.intent() == DocumentBatch.Intent.CHECK)
                && !button(screen, "Save reviewed documents").active
                && screen.submission().isEmpty());
        boolean sealed = false;
        try {
          screen.withFieldPresentations(Map.of());
        } catch (IllegalStateException expected) {
          sealed = true;
        }
        check.accept("standalone batch seals presentation at actual native mounting", sealed);
        readable = false;
        stage = 3;
      }
      case 3 -> {
        check.accept(
            "standalone READ loss hides actual cached review and selection",
            screen.reviewedInput().isEmpty()
                && screen.selectedDocument().isEmpty()
                && screen.submission().isEmpty()
                && !button(screen, "Save reviewed documents").active);
        readable = true;
        stage = 4;
      }
      case 4 -> {
        if (screen.reviewedInput().isEmpty()) return false;
        check.accept(
            "standalone READ recovery discloses same capture without implicit refresh",
            screen.reviewedInput().orElseThrow() == captured);
        var back = button(screen, "Back to documents");
        check.accept("standalone return uses an actual native focus target", focus(screen, back));
        click(screen, back);
        stage = 5;
      }
      case 5 -> {
        check.accept(
            "standalone return reaches original parent without closing borrowed editors",
            returned
                && NativeScreens.current(client) == parent
                && first.isOpen()
                && second.isOpen());
        context.close();
        check.accept(
            "batch scope closure does not own or close borrowed file editors",
            first.isOpen() && second.isOpen());
        first.close();
        second.close();
        check.accept(
            "standalone complete checks leave both original source byte strings unchanged",
            Arrays.equals(firstSource, source("workflow_preferences"))
                && Arrays.equals(secondSource, source("workspace_twin")));
        stage = 6;
        return true;
      }
      default -> {
        return true;
      }
    }
    return false;
  }
}
