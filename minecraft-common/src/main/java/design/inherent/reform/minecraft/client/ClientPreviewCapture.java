package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationHandle;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.DraftPreviewInput;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.minecraft.client.Minecraft;

/** One actual READ operation followed by native-input revalidation on the client thread. */
public final class ClientPreviewCapture<T> {
  private final OperationHandle<EditorActor.Preflight<T>> source;
  private final CompletableFuture<DraftPreviewInput<T>> input = new CompletableFuture<>();
  private boolean cancellationRequested;

  ClientPreviewCapture(
      OperationHandle<EditorActor.Preflight<T>> source,
      Function<EditorActor.Preflight<T>, DraftPreviewInput<T>> deliver) {
    ClientSettingsRuntime.requireClientThread();
    this.source = source;
    DocumentActions.result(source)
        .whenComplete(
            (captured, failed) ->
                Minecraft.getInstance()
                    .execute(
                        () -> {
                          if (input.isDone()) return;
                          if (failed != null) {
                            input.completeExceptionally(failed);
                            return;
                          }
                          try {
                            if (cancellationRequested)
                              throw new PolicyRefusal(
                                  "operation.cancelled", "Preview input delivery was cancelled");
                            input.complete(deliver.apply(captured));
                          } catch (RuntimeException | Error refused) {
                            input.completeExceptionally(refused);
                          }
                        }));
  }

  /** Backend inspection receipts remain truthful even if later native input refuses delivery. */
  public OperationHandle<EditorActor.Preflight<T>> sourceOperation() {
    return source;
  }

  /** Completes only after READ checks and native-input freshness pass; no flush or save occurs. */
  public CompletionStage<DraftPreviewInput<T>> completion() {
    return input.minimalCompletionStage();
  }

  /** Request acknowledgement is not proof that the backend stopped or any effect rolled back. */
  public CompletionStage<Void> requestCancellation() {
    ClientSettingsRuntime.requireClientThread();
    if (!input.isDone()) cancellationRequested = true;
    return source.requestCancellation().thenAccept(ignored -> {});
  }

  void abandon() {
    ClientSettingsRuntime.requireClientThread();
    input.completeExceptionally(
        new PolicyRefusal("editor.closed", "This preview input's client editor was closed"));
  }
}
