package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicCamera;
import design.inherent.reform.presentation.graphics.RenderLifetime;

/** Immediate native content uses the same actual GL completion tickets as private meshes. */
@SuppressWarnings("try")
final class NativeAdapterSubmission {
  static void initialize(RenderLifetime lifetime, Runnable action) {
    if (!org.lwjgl.opengl.GL.getCapabilities().OpenGL32
        || org.lwjgl.glfw.GLFW.glfwGetCurrentContext()
            != net.minecraft.client.Minecraft.getInstance().getWindow().getWindow())
      throw new NativeGraphicUnavailable(
          "preview.provider_unavailable",
          "This adapter needs Minecraft's native OpenGL 3.2 window context");
    NativeMeshGraphicRuntime.submit(
        NativeMeshGraphicRuntime.acquire(lifetime),
        () -> {
          try (var state = new NativeGuiGraphicState()) {
            action.run();
          }
        });
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
    var graphics = canvas.graphics();
    graphics.flush();
    var result = new ClientMeshGraphic.DrawOutcome[1];
    var ticket = NativeMeshGraphicRuntime.acquire(lifetime);
    NativeMeshGraphicRuntime.submit(
        ticket,
        () -> {
          try (var state = new NativeGuiGraphicState();
              var scope = new Scope(canvas, x, y, width, height)) {
            result[0] =
                content.draw(new NativeGraphicDrawContext(graphics, x, y, width, height, camera));
          }
        });
    return java.util.Objects.requireNonNull(result[0]);
  }

  static boolean destroyed() {
    return NativeMeshGraphicRuntime.destroyed();
  }

  static void released(NativeOwnedGraphic graphic, RenderLifetime lifetime) {
    NativeMeshGraphicRuntime.released(graphic);
  }

  private static final class Scope implements AutoCloseable {
    private final NativeCanvas canvas;
    private boolean pose, clip;

    Scope(NativeCanvas canvas, int x, int y, int width, int height) {
      this.canvas = canvas;
      canvas.graphics().pose().pushPose();
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
          canvas.graphics()::flush,
          () -> {
            if (unclip) canvas.disableScissor();
          },
          () -> {
            if (pop) canvas.graphics().pose().popPose();
          });
    }
  }

  private NativeAdapterSubmission() {}
}
