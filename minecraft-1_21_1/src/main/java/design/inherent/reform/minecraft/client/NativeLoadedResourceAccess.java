package design.inherent.reform.minecraft.client;

import design.inherent.reform.owner.PolicyRefusal;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * One exact borrowed resource and its own pack's metadata, never filesystem or remote discovery.
 */
final class NativeLoadedResourceAccess {
  static Optional<LoadedTextureSource> texture(ResourceManager resources, String value) {
    var id = ResourceLocation.tryParse(value);
    if (id == null || !id.getPath().endsWith(".png"))
      throw new PolicyRefusal("preview.resource_id", "Choose a local PNG texture resource ID");
    return resources
        .getResource(id)
        .map(
            resource ->
                new LoadedTextureSource(
                    id.toString(),
                    resource,
                    Optional.ofNullable(
                        resource
                            .source()
                            .getResource(
                                PackType.CLIENT_RESOURCES,
                                ResourceLocation.parse(id.toString() + ".mcmeta")))));
  }

  private NativeLoadedResourceAccess() {}
}
