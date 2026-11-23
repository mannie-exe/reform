package design.inherent.reform.presentation;

import static org.junit.jupiter.api.Assertions.*;

import design.inherent.reform.definition.DocumentId;
import design.inherent.reform.owner.PolicyRefusal;
import design.inherent.reform.presentation.graphics.GraphicLighting;
import design.inherent.reform.presentation.graphics.GraphicLimits;
import design.inherent.reform.presentation.graphics.GraphicMaterial;
import design.inherent.reform.presentation.graphics.GraphicTexture;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GraphicTextureTest {
  private static final DocumentId TEXTURE = DocumentId.parse("fixture:barrel");
  private static final GraphicLimits LIMITS = new GraphicLimits(4096, 4096, 1024, 4, 8192);

  private static byte[] pixel() {
    return Base64.getDecoder()
        .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1XcAAAAASUVORK5CYII=");
  }

  @Test
  void textureCopiesEncodedInputAndMetadataWithoutClaimingNativeDecodeOrFrameSelection() {
    var png = pixel();
    var metadata = "{\"animation\":{\"frames\":[0]}}".getBytes(StandardCharsets.UTF_8);
    var texture = new GraphicTexture(LIMITS, TEXTURE, png, Optional.of(metadata));
    var original = texture.png();
    var originalMetadata = texture.metadata().orElseThrow();
    png[0] = 0;
    metadata[0] = 0;
    texture.png()[0] = 0;
    texture.metadata().orElseThrow()[0] = 0;
    assertArrayEquals(original, texture.png());
    assertArrayEquals(originalMetadata, texture.metadata().orElseThrow());
    assertEquals(1, texture.encodedWidth());
    assertEquals(1, texture.encodedHeight());
    assertEquals(TEXTURE, texture.id());
    assertEquals((long) original.length + originalMetadata.length, texture.encodedBytes());
  }

  @Test
  void pixelBudgetAppliesToTheWholeEncodedStripBeforeAnyNativeDecoderAllocation() {
    // Header-only boundary probe: these bytes are not asserted to be a decodable image.
    var header = pixel();
    ByteBuffer.wrap(header).putInt(16, Integer.MAX_VALUE).putInt(20, Integer.MAX_VALUE);
    var refused =
        assertThrows(
            PolicyRefusal.class,
            () -> new GraphicTexture(LIMITS, TEXTURE, header, Optional.empty()));
    assertEquals("preview.budget", refused.report().issues().getFirst().code());
    var tiny = new GraphicLimits(8, 4096, 1024, 4, 8192);
    assertThrows(
        PolicyRefusal.class, () -> new GraphicTexture(tiny, TEXTURE, pixel(), Optional.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicTexture(LIMITS, TEXTURE, new byte[33], Optional.empty()));
  }

  @Test
  void materialAndLightingRetainAuthoredFactsInsteadOfChoosingAUniversalRenderer() {
    var material =
        new GraphicMaterial(
            TEXTURE,
            GraphicMaterial.Alpha.CUTOUT,
            0.5f,
            GraphicMaterial.Cull.NONE,
            GraphicMaterial.Filter.NEAREST,
            GraphicMaterial.Wrap.REPEAT);
    var lighting =
        new GraphicLighting(
            GraphicLighting.Shading.UNLIT, Optional.of(GraphicLighting.Direction.UP), 7, true);
    assertEquals(0.5f, material.cutoff());
    assertEquals(GraphicMaterial.Wrap.REPEAT, material.wrap());
    assertEquals(GraphicLighting.Direction.UP, lighting.direction().orElseThrow());
    assertEquals(7, lighting.lightEmission());
    assertTrue(lighting.ambientOcclusion());
    assertThrows(
        IllegalArgumentException.class,
        () -> new GraphicLighting(GraphicLighting.Shading.UNLIT, Optional.empty(), 16, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new GraphicMaterial(
                TEXTURE,
                GraphicMaterial.Alpha.OPAQUE,
                Float.NaN,
                GraphicMaterial.Cull.BACK,
                GraphicMaterial.Filter.LINEAR,
                GraphicMaterial.Wrap.CLAMP));
  }
}
