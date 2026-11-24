package design.inherent.reform.minecraft.client;

import java.io.InputStream;
import java.util.Optional;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;

/** Borrowed only during explicit preparation; never closes the game's resource owner. */
record LoadedTextureSource(
    String id, Resource resource, Optional<IoSupplier<InputStream>> metadata) {}
