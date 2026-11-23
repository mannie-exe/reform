package design.inherent.reform.presentation.graphics;

import design.inherent.reform.definition.DocumentId;
import java.util.List;
import java.util.Objects;

/** Frozen CPU/native-preparation output; private GPU allocation belongs to the shared host. */
public record GraphicSceneData(
    GraphicRequest.Stamp stamp, GraphicScene scene, GraphicCamera camera, List<Fact> facts) {
  /**
   * Stable machine identity and readable facts, retained alongside the exact subject provenance.
   */
  public record Fact(DocumentId id, String title, String value) {
    public Fact {
      Objects.requireNonNull(id);
      if (Objects.requireNonNull(title).isBlank()
          || title.length() > 256
          || Objects.requireNonNull(value).isBlank()
          || value.length() > 4096)
        throw new IllegalArgumentException("Graphic facts need bounded readable titles and values");
    }
  }

  public GraphicSceneData {
    Objects.requireNonNull(stamp);
    Objects.requireNonNull(scene);
    Objects.requireNonNull(camera);
    if (facts.size() > 64) throw new IllegalArgumentException("Too many graphic facts");
    facts = List.copyOf(facts);
    var ids = new java.util.HashSet<DocumentId>();
    for (var fact : facts)
      if (!ids.add(fact.id())) throw new IllegalArgumentException("Duplicate graphic fact");
  }

  public long outputBytes() {
    long bytes = scene.mesh().geometryBytes();
    for (var texture : scene.textures().values()) bytes = Math.addExact(bytes, texture.bytes());
    for (var fact : facts) {
      bytes =
          Math.addExact(
              bytes, fact.id().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      bytes =
          Math.addExact(
              bytes, fact.title().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
      bytes =
          Math.addExact(
              bytes, fact.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    return bytes;
  }
}
