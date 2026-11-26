package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.*;
import java.util.concurrent.CompletionStage;

/** Domain capture/compilation, never a private viewport, writer, executor, or live-manager swap. */
public interface ClientGraphicProvider<T, S> extends ClientGraphicSource<S> {
  enum Inputs {
    DRAFT_ONLY,
    DRAFT_AND_AUDITION
  }

  /** Existing providers remain draft-only until their owner explicitly adopts alternate inputs. */
  default Inputs inputs() {
    return Inputs.DRAFT_ONLY;
  }

  /**
   * Shared host entry. The compatibility bridge refuses auditions rather than substituting a base.
   */
  default CompletionStage<GraphicScenePreparation> prepareCaptured(
      CapturedGraphicRequest<T, S> request, ClientGraphicWork work) {
    final GraphicRequest<T, S> draft;
    try {
      draft = request.asDraft();
    } catch (design.inherent.reform.owner.PolicyRefusal refused) {
      return java.util.concurrent.CompletableFuture.completedStage(
          new GraphicScenePreparation.Refused(refused.report()));
    }
    return prepare(draft, work);
  }

  /** Compose explicit worker/native stages; do not block while waiting for a worker result. */
  CompletionStage<GraphicScenePreparation> prepare(
      GraphicRequest<T, S> request, ClientGraphicWork work);
}
