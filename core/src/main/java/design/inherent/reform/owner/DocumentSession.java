package design.inherent.reform.owner;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.edit.DraftTree;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Editor/operation boundary shared by actual owners; backend execution and effects stay explicit.
 */
public interface DocumentSession<T> extends AutoCloseable {
  enum Operation {
    READ,
    COMMIT,
    APPLY_SAVED
  }

  DocumentId document();

  DocumentDefinition<T> definition();

  UUID context();

  UUID id();

  boolean isOpen();

  Set<Operation> operations();

  CompletionStage<? extends OwnerObservation<T>> read();

  /** Read-authorized complete validation/preparation; the token never grants WRITE authority. */
  CompletionStage<? extends PreparedEdit<T>> prepare(OwnerObservation<T> observed, DraftTree draft);

  /**
   * Consumes this issued proposal on submission, including queue or permission refusal. A retry
   * requires fresh preparation; consumption alone never implies that an effect was attempted.
   */
  CompletionStage<? extends CommitReceipt<T>> commit(PreparedEdit<T> prepared);

  /** Unsupported means no native application-only path, not permission to repeat persistence. */
  default CompletionStage<? extends CommitReceipt<T>> applySaved(OwnerObservation<T> observed) {
    return CompletableFuture.<CommitReceipt<T>>failedFuture(
            new PolicyRefusal("owner.unsupported", "This owner has no application-only operation"))
        .minimalCompletionStage();
  }

  @Override
  void close();
}
