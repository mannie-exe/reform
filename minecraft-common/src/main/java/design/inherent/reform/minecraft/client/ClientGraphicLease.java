package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.RenderLifetime;
import java.util.concurrent.CompletionStage;

/** Internal common lifetime of mesh and typed native contributions; no second scheduler. */
interface ClientGraphicLease extends AutoCloseable {
  RenderLifetime.State state();

  CompletionStage<RenderLifetime.State> releaseCompletion();

  ClientMeshGraphic.DrawOutcome draw(
      FormCanvas canvas, int x, int y, int width, int height, GraphicCamera camera);

  @Override
  void close();
}
