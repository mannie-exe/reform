package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.internal.ClientSettingsRuntime;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * One prepared view interest; close revokes drawing and actual native retirement completes later.
 */
public final class PreparedGraphic implements AutoCloseable {
  private final GraphicSourceStamp stamp;
  private final List<GraphicSceneData.Fact> facts;
  private final GraphicCamera camera;
  private final CompletionStage<RenderLifetime.State> release;
  private ClientGraphicLease graphic;
  private RenderLifetime.State observed;

  PreparedGraphic(GraphicSceneData data, ClientMeshGraphic graphic) {
    this(data.stamp(), data.facts(), data.camera(), graphic);
  }

  PreparedGraphic(NativeGraphicContribution<?> data, ClientNativeGraphic graphic) {
    this(data.stamp(), data.facts(), data.camera(), graphic);
  }

  private PreparedGraphic(
      GraphicSourceStamp stamp,
      List<GraphicSceneData.Fact> facts,
      GraphicCamera camera,
      ClientGraphicLease graphic) {
    this.stamp = stamp;
    this.facts = facts;
    this.camera = camera;
    this.graphic = graphic;
    observed = graphic.state();
    release = graphic.releaseCompletion();
    release.thenAccept(state -> observed = state);
  }

  public GraphicSourceStamp stamp() {
    return stamp;
  }

  public List<GraphicSceneData.Fact> facts() {
    return facts;
  }

  public GraphicCamera camera() {
    return camera;
  }

  public RenderLifetime.State state() {
    ClientSettingsRuntime.requireClientThread();
    return graphic == null ? observed : graphic.state();
  }

  public CompletionStage<RenderLifetime.State> releaseCompletion() {
    return release;
  }

  public ClientMeshGraphic.DrawOutcome draw(
      FormCanvas canvas, int x, int y, int width, int height, GraphicCamera camera) {
    ClientSettingsRuntime.requireClientThread();
    if (graphic == null)
      throw new PolicyRefusal("preview.closed", "This prepared graphic is closed to new uses");
    return graphic.draw(canvas, x, y, width, height, camera);
  }

  @Override
  public void close() {
    ClientSettingsRuntime.requireClientThread();
    var nativeGraphic = graphic;
    graphic = null;
    if (nativeGraphic != null)
      try {
        nativeGraphic.close();
      } finally {
        observed = nativeGraphic.state();
      }
  }
}
