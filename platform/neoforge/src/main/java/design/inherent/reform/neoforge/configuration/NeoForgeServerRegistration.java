package design.inherent.reform.neoforge.configuration;

import design.inherent.reform.minecraft.configuration.ServerRegistration;
import java.util.Objects;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;

/** Explicit common-side registration from the installed NeoForge owner. */
public final class NeoForgeServerRegistration implements ServerRegistration {
  private final ModContainer container;

  private NeoForgeServerRegistration(ModContainer container) {
    this.container = Objects.requireNonNull(container);
    if (ModList.get().getModContainerById(container.getModId()).orElse(null) != container)
      throw new IllegalArgumentException("Use the actual owning NeoForge mod container");
  }

  public static NeoForgeServerRegistration of(ModContainer container) {
    return new NeoForgeServerRegistration(container);
  }

  @Override
  public String modId() {
    return container.getModId();
  }
}
