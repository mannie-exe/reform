package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicScenePreparation;
import design.inherent.reform.presentation.graphics.LoadedGraphicRequest;
import java.util.concurrent.CompletionStage;

/** Explicit loaded-resource inspection. Never receives edited settings or publishes resources. */
public interface ClientLoadedGraphicProvider<S> extends ClientGraphicSource<S> {
  CompletionStage<GraphicScenePreparation> prepare(
      LoadedGraphicRequest<S> request, ClientGraphicWork work);
}
