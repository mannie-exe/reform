package design.inherent.reform.minecraft.client;

import design.inherent.reform.minecraft.mixin.GraphicCanvasAccessor;
import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.RenderLifetime;

/** Bind typed native content to the same actual extraction and GPU-consumption tickets. */
@SuppressWarnings("try")
final class NativeAdapterSubmission {
  static void initialize(RenderLifetime lifetime, Runnable action) {
    var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
    try {
      action.run();
    } finally {
      NativeMeshGraphicRuntime.submitted(ticket, () -> {});
    }
  }

  static ClientMeshGraphic.DrawOutcome draw(
      RenderLifetime lifetime,
      NativeCanvas canvas,
      int x,
      int y,
      int width,
      int height,
      GraphicCamera camera,
      NativeGraphicAdapter.Prepared content) {
    var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
    var graphics = canvas.graphics();
    // Register first: even partial extraction owns this exact state until consumption/reset.
    NativeMeshGraphicRuntime.extracted(
        ((GraphicCanvasAccessor) graphics).reform$graphicState(), ticket);
    try (var scope = new Scope(canvas, x, y, width, height)) {
      return content.draw(new NativeGraphicDrawContext(graphics, x, y, width, height, camera));
    }
  }

  static boolean destroyed() {
    return false;
  }

  static void released(NativeOwnedGraphic graphic, RenderLifetime lifetime) {
    NativeMeshGraphicRuntime.released(lifetime);
    NativeMeshGraphicRuntime.released(graphic);
  }

  private static final class Scope implements AutoCloseable {
    private final NativeCanvas canvas;
    private boolean pose, clip;

    Scope(NativeCanvas canvas, int x, int y, int width, int height) {
      this.canvas = canvas;
      canvas.graphics().pose().pushMatrix();
      pose = true;
      try {
        canvas.enableScissor(x, y, x + width, y + height);
        clip = true;
      } catch (RuntimeException | Error failed) {
        try {
          close();
        } catch (RuntimeException | Error cleanup) {
          failed.addSuppressed(cleanup);
        }
        throw failed;
      }
    }

    @Override
    public void close() {
      boolean pop = pose, unclip = clip;
      pose = false;
      clip = false;
      NativeGraphicCleanup.run(
          () -> {
            if (unclip) canvas.disableScissor();
          },
          () -> {
            if (pop) canvas.graphics().pose().popMatrix();
          });
    }
  }

  private NativeAdapterSubmission() {}
}
