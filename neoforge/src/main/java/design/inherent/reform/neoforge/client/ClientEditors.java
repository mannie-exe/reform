package design.inherent.reform.neoforge.client;

import design.inherent.reform.definition.DocumentDefinition;
import design.inherent.reform.edit.DraftLimits;
import design.inherent.reform.operation.DocumentActions;
import design.inherent.reform.operation.OperationScope;
import design.inherent.reform.owner.DocumentSession;
import design.inherent.reform.owner.EditorActor;
import design.inherent.reform.owner.OwnerContext;
import design.inherent.reform.serialization.JsonLimits;
import java.util.Objects;
import java.util.function.Function;

/** Transfer a borrowed-context editing lifetime only after its entry is fully constructed. */
final class ClientEditors {
  static <T, R> R open(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentSession<T> session,
      DocumentDefinition<T> definition,
      JsonLimits json,
      Function<DocumentActions<T>, R> entry) {
    return open(context, access, session, definition, json, DraftLimits.CONFIGURATION, entry);
  }

  static <T, R> R open(
      OwnerContext context,
      OwnerContext.Access access,
      DocumentSession<T> session,
      DocumentDefinition<T> definition,
      JsonLimits json,
      DraftLimits draft,
      Function<DocumentActions<T>, R> entry) {
    EditorActor<T> actor = null;
    DocumentActions<T> actions = null;
    try {
      actor = new EditorActor<>(context, session, definition, json, draft, 64);
      actions =
          new DocumentActions<>(
              context,
              access,
              actor,
              new OperationScope.Limits(32, 64, 128, OperationScope.Limits.STANDARD.input()),
              json.maximumBytes());
      return Objects.requireNonNull(entry.apply(actions));
    } catch (RuntimeException | Error failed) {
      try {
        if (actions != null) actions.close();
        else if (actor != null) actor.close();
        else session.close();
      } catch (RuntimeException | Error cleanup) {
        if (cleanup != failed) failed.addSuppressed(cleanup);
      }
      throw failed;
    }
  }

  private ClientEditors() {}
}
