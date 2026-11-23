package design.inherent.reform.presentation.graphics;

/** View-only orthographic orbit, fit zoom, and pan. It never changes the captured subject. */
public record GraphicCamera(double yaw, double pitch, double zoom, double panX, double panY) {
  public static final GraphicCamera STANDARD = new GraphicCamera(135, 30, 0.8, 0, 0);

  public GraphicCamera {
    if (!Double.isFinite(yaw)
        || !Double.isFinite(pitch)
        || !Double.isFinite(zoom)
        || !Double.isFinite(panX)
        || !Double.isFinite(panY)
        || zoom <= 0)
      throw new IllegalArgumentException(
          "A graphic camera needs finite angles/pan and positive finite zoom");
  }
}
