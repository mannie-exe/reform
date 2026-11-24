package design.inherent.reform.minecraft.client;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.definition.FieldPath;
import design.inherent.reform.minecraft.reference.NativeReferences;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.*;
import design.inherent.reform.reference.ReferenceStamp;
import design.inherent.reform.serialization.JsonLimits;
import design.inherent.reform.validation.Issue;
import design.inherent.reform.validation.ValidationReport;
import design.inherent.reform.value.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Explicit loaded-client subjects. Never substitutes these resources for a domain draft preview.
 */
public final class ClientLoadedGraphics {
  private static final DocumentId TEXTURE = DocumentId.parse("reform:loaded_texture");
  private static final DocumentId SPRITE = DocumentId.parse("reform:loaded_sprite");
  private static final DocumentId MATERIAL = DocumentId.parse("reform:loaded_texture_surface");
  private static final DocumentId ITEM = DocumentId.parse("reform:loaded_item");

  /**
   * Explicit copied native item with its loaded GUI model. Captures no inventory capability, does
   * not tick a world, and never substitutes this loaded subject for an edited domain preview.
   */
  public static ClientLoadedGraphicProvider<ClientItemSubject> items(GraphicLimits limits) {
    Objects.requireNonNull(limits);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return ITEM;
      }

      public ValueType<ClientItemSubject> subjectType() {
        return ClientItemSubject.type();
      }

      public GraphicLimits limits() {
        return limits;
      }

      public ReferenceStamp resourceStamp() {
        return LoadedGraphicResources.snapshot().stamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<ClientItemSubject> request, ClientGraphicWork work) {
        work.checkpoint();
        var resources = LoadedGraphicResources.snapshot();
        if (!resources.stamp().equals(request.stamp().resources()))
          throw new PolicyRefusal(
              "preview.stale", "Loaded resources changed; prepare the item again");
        if (request.subject().inputBytes() > request.limits().inputBytes())
          throw new PolicyRefusal("preview.budget", "The copied item exceeds its input allowance");
        if (!resources.ready())
          return CompletableFuture.completedFuture(
              new GraphicScenePreparation.Unavailable(
                  problem(
                      "preview.resources_unavailable",
                      "Loaded resources are unavailable; finish or retry the resource reload")));
        return CompletableFuture.completedFuture(
            new NativeGraphicContribution<>(
                NativeLoadedItemAdapter.INSTANCE,
                request.subject(),
                request.stamp(),
                GraphicCamera.STANDARD,
                List.of(
                    new GraphicSceneData.Fact(
                        DocumentId.parse("reform:graphic/source"),
                        "Source",
                        "Copied item and loaded client GUI model"),
                    new GraphicSceneData.Fact(
                        DocumentId.parse("reform:graphic/lighting"),
                        "Rendering",
                        "Native GUI lighting; prepared model, without world ticking or inventory changes"),
                    new GraphicSceneData.Fact(
                        DocumentId.parse("reform:graphic/animation"),
                        "Animation",
                        "Static prepared geometry; loaded shader and atlas effects follow the native renderer"))));
      }
    };
  }

  /** Exact local PNG resource ID, with paired pack metadata and its first declared still frame. */
  public static ClientLoadedGraphicProvider<String> textures(
      GraphicLimits limits, JsonLimits metadataLimits) {
    Objects.requireNonNull(limits);
    Objects.requireNonNull(metadataLimits);
    var subjects = NativeReferences.type(TEXTURE, false, 512);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return TEXTURE;
      }

      public ValueType<String> subjectType() {
        return subjects;
      }

      public GraphicLimits limits() {
        return limits;
      }

      public ReferenceStamp resourceStamp() {
        return LoadedGraphicResources.snapshot().stamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<String> request, ClientGraphicWork work) {
        var resources = LoadedGraphicResources.snapshot();
        if (!resources.stamp().equals(request.stamp().resources()))
          throw new PolicyRefusal(
              "preview.stale", "The loaded resources changed; prepare the image again");
        if (!resources.ready()) {
          var report =
              problem(
                  "preview.resources_unavailable",
                  "Loaded resources are unavailable; finish or retry the resource reload");
          return CompletableFuture.completedFuture(
              resources.failure() == null
                  ? new GraphicScenePreparation.Unavailable(report)
                  : new GraphicScenePreparation.Failed(report, resources.failure()));
        }
        return work.onClient(
                () -> NativeLoadedResourceAccess.texture(resources.manager(), request.subject()))
            .thenCompose(
                source ->
                    source.isEmpty()
                        ? CompletableFuture.completedFuture(
                            new GraphicScenePreparation.Unavailable(
                                problem(
                                    "preview.asset_missing",
                                    "The selected texture is not in the loaded resource packs")))
                        : work.onWorker(
                            () -> capture(request, source.orElseThrow(), metadataLimits, work)));
      }
    };
  }

  /** Explicit static physical source frame; it is not the atlas's current animation state. */
  public static ClientLoadedGraphicProvider<ClientSpriteSubject> sprites(GraphicLimits limits) {
    Objects.requireNonNull(limits);
    return new ClientLoadedGraphicProvider<>() {
      public DocumentId id() {
        return SPRITE;
      }

      public ValueType<ClientSpriteSubject> subjectType() {
        return ClientSpriteSubject.type();
      }

      public GraphicLimits limits() {
        return limits;
      }

      public ReferenceStamp resourceStamp() {
        return LoadedGraphicResources.snapshot().stamp();
      }

      public CompletionStage<GraphicScenePreparation> prepare(
          LoadedGraphicRequest<ClientSpriteSubject> request, ClientGraphicWork work) {
        var resources = LoadedGraphicResources.snapshot();
        if (!resources.stamp().equals(request.stamp().resources()))
          throw new PolicyRefusal(
              "preview.stale", "Loaded resources changed; prepare the sprite again");
        if (!resources.ready()) {
          var report =
              problem(
                  "preview.resources_unavailable",
                  "Loaded resources are unavailable; finish or retry the resource reload");
          return CompletableFuture.completedFuture(
              resources.failure() == null
                  ? new GraphicScenePreparation.Unavailable(report)
                  : new GraphicScenePreparation.Failed(report, resources.failure()));
        }
        return work.onClient(
                () -> NativeLoadedSpriteAccess.capture(request.subject(), request.limits()))
            .thenCompose(
                source ->
                    source.isEmpty()
                        ? CompletableFuture.completedFuture(
                            new GraphicScenePreparation.Unavailable(
                                problem(
                                    "preview.asset_missing",
                                    "The selected sprite is not in this loaded atlas")))
                        : work.onWorker(() -> sprite(request, source.orElseThrow(), work)));
      }
    };
  }

  private static GraphicScenePreparation sprite(
      LoadedGraphicRequest<ClientSpriteSubject> request,
      LoadedSpriteSource source,
      ClientGraphicWork work) {
    work.checkpoint();
    var frame =
        ClientGraphicImages.frame(
            source.stripWidth(),
            source.stripHeight(),
            source.width(),
            source.height(),
            request.subject().sourceFrame());
    var argb = new int[Math.multiplyExact(frame.width(), frame.height())];
    for (int y = 0; y < frame.height(); y++) {
      work.checkpoint();
      for (int x = 0; x < frame.width(); x++) {
        int abgr = source.abgr()[(frame.y() + y) * source.stripWidth() + frame.x() + x];
        argb[y * frame.width() + x] =
            (abgr & 0xff00ff00) | ((abgr & 0xff) << 16) | ((abgr >>> 16) & 0xff);
      }
    }
    var pixels = new GraphicPixels(request.limits(), SPRITE, frame.width(), frame.height(), argb);
    var scene =
        new GraphicScene(
            plane(request.limits(), MATERIAL, frame.width(), frame.height()),
            Map.of(
                MATERIAL,
                new GraphicMaterial(
                    SPRITE,
                    GraphicMaterial.Alpha.TRANSLUCENT,
                    0,
                    GraphicMaterial.Cull.NONE,
                    GraphicMaterial.Filter.NEAREST,
                    GraphicMaterial.Wrap.CLAMP)),
            Map.of(SPRITE, pixels));
    scene.requireBudget(request.limits());
    return new GraphicScenePreparation.Ready(
        new GraphicSceneData(
            request.stamp(),
            scene,
            new GraphicCamera(0, 0, 0.9, 0, 0),
            List.of(
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/source"), "Source", "Loaded client atlas"),
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/atlas"), "Atlas", source.atlas()),
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/resource"), "Sprite", source.sprite()),
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/frame"),
                    "Image",
                    "Physical source frame "
                        + request.subject().sourceFrame()
                        + "; static reference, without animation or world lighting"))));
  }

  private static GraphicScenePreparation capture(
      LoadedGraphicRequest<String> request,
      LoadedTextureSource source,
      JsonLimits metadataLimits,
      ClientGraphicWork work)
      throws IOException {
    work.checkpoint();
    byte[] png;
    try (var input = source.resource().open()) {
      png = read(input, request.limits().inputBytes(), work);
    }
    Optional<byte[]> metadata = Optional.empty();
    if (source.metadata().isPresent()) {
      try (var input = source.metadata().orElseThrow().get()) {
        metadata =
            Optional.of(
                read(
                    input,
                    Math.min(
                        metadataLimits.maximumBytes(), request.limits().inputBytes() - png.length),
                    work));
      }
    }
    work.checkpoint();
    var id = DocumentId.parse(source.id());
    var texture = new GraphicTexture(request.limits(), id, png, metadata);
    // A front-facing unlit plane preserves artwork aspect and alpha; no world lighting is implied.
    var mesh = plane(request.limits(), MATERIAL, texture.encodedWidth(), texture.encodedHeight());
    var pixels =
        ClientGraphicImages.decodeStills(
            List.of(texture),
            work.ledger(),
            metadataLimits,
            mesh.geometryBytes(),
            work::checkpoint);
    // Animation metadata can change the still's dimensions, so fit the decoded frame itself.
    var still = pixels.get(id);
    mesh = plane(request.limits(), MATERIAL, still.width(), still.height());
    var scene =
        new GraphicScene(
            mesh,
            Map.of(
                MATERIAL,
                new GraphicMaterial(
                    id,
                    GraphicMaterial.Alpha.TRANSLUCENT,
                    0,
                    GraphicMaterial.Cull.NONE,
                    GraphicMaterial.Filter.NEAREST,
                    GraphicMaterial.Wrap.CLAMP)),
            pixels);
    scene.requireBudget(request.limits());
    return new GraphicScenePreparation.Ready(
        new GraphicSceneData(
            request.stamp(),
            scene,
            new GraphicCamera(0, 0, 0.9, 0, 0),
            List.of(
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/source"), "Source", "Loaded client resources"),
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/resource"), "Texture", source.id()),
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/pack"), "Source pack", source.packId()),
                new GraphicSceneData.Fact(
                    DocumentId.parse("reform:graphic/frame"),
                    "Image",
                    "First declared still frame; nearest filtering, without world lighting"))));
  }

  private static GraphicMesh plane(
      GraphicLimits limits, DocumentId material, int width, int height) {
    float x = (float) width / Math.max(width, height) / 2,
        y = (float) height / Math.max(width, height) / 2;
    var lighting = new GraphicLighting(GraphicLighting.Shading.UNLIT, Optional.empty(), 0, false);
    return new GraphicMesh(
        limits,
        List.of(
            new GraphicMesh.Vertex(-x, y, 0, 0, 0, -1, 0, 0, 1),
            new GraphicMesh.Vertex(x, y, 0, 1, 0, -1, 0, 0, 1),
            new GraphicMesh.Vertex(x, -y, 0, 1, 1, -1, 0, 0, 1),
            new GraphicMesh.Vertex(-x, -y, 0, 0, 1, -1, 0, 0, 1)),
        List.of(
            new GraphicMesh.Triangle(0, 1, 2, material, lighting),
            new GraphicMesh.Triangle(0, 2, 3, material, lighting)));
  }

  private static byte[] read(InputStream input, long allowance, ClientGraphicWork work)
      throws IOException {
    work.checkpoint();
    if (allowance < 0)
      throw new PolicyRefusal(
          "preview.budget", "The loaded texture exceeds its encoded input allowance");
    var output = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count =
            input.read(
                buffer,
                0,
                allowance - output.size() >= buffer.length
                    ? buffer.length
                    : (int) (allowance - output.size()) + 1))
        != -1) {
      work.checkpoint();
      if (count == 0
          || count > allowance - output.size()
          || output.size() > Integer.MAX_VALUE - 8 - count)
        throw new PolicyRefusal(
            "preview.budget", "The loaded texture exceeds its encoded input allowance");
      output.write(buffer, 0, count);
    }
    work.checkpoint();
    return output.toByteArray();
  }

  private static ValidationReport problem(String code, String fallback) {
    return new ValidationReport(List.of(Issue.error(code, FieldPath.ROOT, fallback)), true);
  }

  private ClientLoadedGraphics() {}
}
