package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import java.util.Map;
import java.util.Objects;

/**
 * Complete immutable texture bindings; no native sprite, resource manager, or world is retained.
 */
public record GraphicScene(
    GraphicMesh mesh,
    Map<DocumentId, GraphicMaterial> materials,
    Map<DocumentId, GraphicPixels> textures) {
  public GraphicScene {
    Objects.requireNonNull(mesh);
    materials = Map.copyOf(materials);
    textures = Map.copyOf(textures);
    if (!materials.keySet().equals(mesh.materials()))
      throw new IllegalArgumentException("A scene needs exactly its mesh's material bindings");
    for (var material : materials.values())
      if (!textures.containsKey(material.texture()))
        throw new IllegalArgumentException(
            "A scene material references an absent captured texture");
    textures.forEach(
        (id, pixels) -> {
          if (!id.equals(pixels.id()))
            throw new IllegalArgumentException(
                "A texture binding needs its actual captured identity");
        });
  }

  public void requireBudget(GraphicLimits limits) {
    long bytes = mesh.geometryBytes(), pixels = 0;
    if (bytes > limits.outputBytes())
      throw new PolicyRefusal(
          "preview.budget", "The mesh exceeds the complete scene output allowance");
    if ((long) materials.size() + textures.size() > limits.resources())
      throw new PolicyRefusal(
          "preview.budget", "The complete scene exceeds its resource allowance");
    for (var texture : textures.values()) {
      long area = (long) texture.width() * texture.height();
      if (texture.bytes() > limits.outputBytes() - bytes || area > limits.decodedPixels() - pixels)
        throw new PolicyRefusal(
            "preview.budget", "The complete scene exceeds its output or pixel allowance");
      bytes += texture.bytes();
      pixels += area;
    }
    if (bytes > limits.outputBytes() || pixels > limits.decodedPixels())
      throw new PolicyRefusal(
          "preview.budget", "The complete scene exceeds its output or pixel allowance");
  }
}
