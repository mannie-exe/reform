package design.inherent.reform.presentation.graphics;

import java.util.Objects;
import java.util.Optional;

/** Authored surface facts, distinct from the chosen preview camera or environment. */
public record GraphicLighting(
    Shading shading, Optional<Direction> direction, int lightEmission, boolean ambientOcclusion) {
  public enum Shading {
    UNLIT,
    DIRECTIONAL
  }

  public enum Direction {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST
  }

  public static final GraphicLighting DIRECTIONAL =
      new GraphicLighting(Shading.DIRECTIONAL, Optional.empty(), 0, false);

  public GraphicLighting {
    Objects.requireNonNull(shading);
    Objects.requireNonNull(direction);
    if (lightEmission < 0 || lightEmission > 15)
      throw new IllegalArgumentException("Mesh light emission must be between 0 and 15");
  }
}
