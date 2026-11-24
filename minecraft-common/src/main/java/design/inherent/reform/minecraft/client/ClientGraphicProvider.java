package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.*;
import java.util.concurrent.CompletionStage;

/** Domain capture/compilation, never a private viewport, writer, executor, or live-manager swap. */
public interface ClientGraphicProvider<T, S> extends ClientGraphicSource<S> {
  /** Compose explicit worker/native stages; do not block while waiting for a worker result. */
  CompletionStage<GraphicScenePreparation> prepare(
      GraphicRequest<T, S> request, ClientGraphicWork work);
}
