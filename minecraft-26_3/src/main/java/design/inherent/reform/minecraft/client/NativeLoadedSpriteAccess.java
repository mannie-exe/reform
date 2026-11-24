package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicLimits;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/** Native atlas lookup only; never uploads, closes, or replaces game-owned textures. */
final class NativeLoadedSpriteAccess {
  static Optional<LoadedSpriteSource> capture(ClientSpriteSubject subject, GraphicLimits limits) {
    var id = Identifier.parse(subject.sprite());
    var sprite =
        Minecraft.getInstance()
            .getAtlasManager()
            .getAtlasOrThrow(
                subject.atlas() == ClientSpriteSubject.Atlas.GUI ? AtlasIds.GUI : AtlasIds.BLOCKS)
            .getSprite(id);
    if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())
        && !id.equals(MissingTextureAtlasSprite.getLocation())) return Optional.empty();
    return Optional.of(LoadedSpriteSource.capture(sprite, subject, limits));
  }

  private NativeLoadedSpriteAccess() {}
}
