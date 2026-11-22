package design.inherent.reform.fabric.configuration;

import design.inherent.reform.minecraft.configuration.ServerRegistration;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Explicit common-side registration; Fabric has no fabricated FML configuration registry. */
public final class FabricServerRegistration implements ServerRegistration {
  private final ModContainer container;

  private FabricServerRegistration(ModContainer container) {
    this.container = Objects.requireNonNull(container);
    if (FabricLoader.getInstance().getModContainer(container.getMetadata().getId()).orElse(null)
        != container)
      throw new IllegalArgumentException("Use the actual owning Fabric mod container");
  }

  public static FabricServerRegistration of(ModContainer container) {
    return new FabricServerRegistration(container);
  }

  @Override
  public String modId() {
    return container.getMetadata().getId();
  }
}
