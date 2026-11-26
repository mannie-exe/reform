package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.*;
import java.util.concurrent.CompletionStage;

/** Explicit provider opt-in to complete draft and read-only audition inputs with truthful basis. */
public interface ClientCapturedGraphicProvider<T, S> extends ClientGraphicProvider<T, S> {
  @Override
  default Inputs inputs() {
    return Inputs.DRAFT_AND_AUDITION;
  }

  @Override
  default CompletionStage<GraphicScenePreparation> prepare(
      GraphicRequest<T, S> request, ClientGraphicWork work) {
    return prepareCaptured(CapturedGraphicRequest.from(request), work);
  }

  @Override
  CompletionStage<GraphicScenePreparation> prepareCaptured(
      CapturedGraphicRequest<T, S> request, ClientGraphicWork work);
}
