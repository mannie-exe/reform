package design.inherent.reform.minecraft.client;

import design.inherent.reform.presentation.graphics.GraphicLimits;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** Native atlas lookup only; never uploads, closes, or replaces game-owned textures. */
final class NativeLoadedSpriteAccess {
  static Optional<LoadedSpriteSource> capture(ClientSpriteSubject subject, GraphicLimits limits) {
    var id = ResourceLocation.parse(subject.sprite());
    var sprite =
        subject.atlas() == ClientSpriteSubject.Atlas.GUI
            ? Minecraft.getInstance().getGuiSprites().getSprite(id)
            : Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(id);
    if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())
        && !id.equals(MissingTextureAtlasSprite.getLocation())) return Optional.empty();
    return Optional.of(LoadedSpriteSource.capture(sprite, subject, limits));
  }

  private NativeLoadedSpriteAccess() {}
}
